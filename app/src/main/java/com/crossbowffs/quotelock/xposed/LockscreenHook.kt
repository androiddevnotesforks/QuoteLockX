package com.crossbowffs.quotelock.xposed

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.Resources.NotFoundException
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.annotation.RequiresApi
import com.crossbowffs.quotelock.consts.*
import com.crossbowffs.quotelock.data.api.buildReadableSource
import com.crossbowffs.quotelock.data.modules.collections.database.QuoteCollectionContract
import com.crossbowffs.quotelock.provider.ActionProvider
import com.crossbowffs.quotelock.provider.PreferenceProvider
import com.crossbowffs.quotelock.utils.*
import com.crossbowffs.quotelock.xposed.XSafeModuleResources.Companion.createInstance
import com.crossbowffs.remotepreferences.RemotePreferences
import de.robv.android.xposed.*
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam
import de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.*

class LockscreenHook : IXposedHookZygoteInit, IXposedHookInitPackageResources,
    IXposedHookLoadPackage, OnSharedPreferenceChangeListener {

    private lateinit var quotesGeneratedByApp: Set<String>

    private var mLayoutTranslation = -(16f + 32f + 16f + 32f + 16f)
    private lateinit var mQuoteContainer: LinearLayout
    private lateinit var mQuoteTextView: TextView
    private lateinit var mSourceTextView: TextView
    private lateinit var mActionContainer: LinearLayout
    private lateinit var mRefreshImageView: ImageView
    private lateinit var mCollectImageView: ImageView
    private lateinit var mAodQuoteContainer: LinearLayout
    private lateinit var mAodQuoteTextView: TextView
    private lateinit var mAodSourceTextView: TextView
    private lateinit var mCommonPrefs: RemotePreferences
    private lateinit var mQuotePrefs: RemotePreferences
    private var mDisplayOnAod = false
    private var mAodHandler: Handler? = null

    private val typefaceCache = HashMap<String, Typeface>()

    private fun isQuoteGeneratedByApp(text: String?, source: String?, author: String?): Boolean =
        text.isNullOrBlank() || quotesGeneratedByApp.contains(text)
                || quotesGeneratedByApp.contains(source) || quotesGeneratedByApp.contains(author)

    private fun loadTypeface(
        font: String?,
        style: Int = Typeface.NORMAL,
    ): Typeface = when (font) {
        PREF_COMMON_FONT_FAMILY_LEGACY_DEFAULT,
        PREF_COMMON_FONT_FAMILY_DEFAULT_SANS_SERIF,
        null,
        -> Typeface.SANS_SERIF
        PREF_COMMON_FONT_FAMILY_DEFAULT_SERIF,
        -> Typeface.SERIF
        else -> runCatching {
            typefaceCache.getOrElse("$font&$style") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Typeface.Builder(File(font))
                        .setFontVariationSettings(getFontVariationSettings())
                        .build()
                } else {
                    Typeface.createFromFile(font)
                }
            }
        }.onFailure {
            Xlog.e(TAG, "Failed to load typeface: $font", it)
        }.getOrNull() ?: Typeface.DEFAULT
    }

    private fun refreshLockscreenQuote() {
        Xlog.d(TAG, "Quote changed, updating lockscreen layout")

        // Update quote text
        var text = mQuotePrefs.getString(PREF_QUOTES_TEXT, null)
        var source = mQuotePrefs.getString(PREF_QUOTES_SOURCE, null)
        val originalSource = source
        val author = mQuotePrefs.getString(PREF_QUOTES_AUTHOR, null)
        val isQuoteGeneratedByApp = isQuoteGeneratedByApp(text, source, author)
        if (isQuoteGeneratedByApp) {
            mCollectImageView.visibility = View.GONE
            mLayoutTranslation = -(16f + 32f + 16f + 1f)
        } else {
            mCollectImageView.visibility = View.VISIBLE
            mLayoutTranslation = -(16f + 32f + 16f + 32f + 16f + 1f)
        }
        val collectionState = mQuotePrefs.getBoolean(PREF_QUOTES_COLLECTION_STATE, false)
        if (text.isNullOrBlank()) {
            try {
                text = sModuleRes.getString(RES_STRING_OPEN_APP_1)
                source = sModuleRes.getString(RES_STRING_OPEN_APP_2)
            } catch (e: NotFoundException) {
                Xlog.e(TAG, "Could not load string resource", e)
                text = null
                source = null
            }
        } else {
            source = buildReadableSource(source, author, !isQuoteGeneratedByApp)
        }
        val params = mActionContainer.layoutParams as RelativeLayout.LayoutParams
        params.rightMargin = (mLayoutTranslation + 16f).dp2px().toInt()
        mActionContainer.layoutParams = params
        mQuoteTextView.text = text
        // Cache author information in the quote TextView's hint attribute.
        mQuoteTextView.hint = author
        mSourceTextView.text = source
        // Cache original source information in the source TextView's hint attribute.
        mSourceTextView.hint = originalSource

        // Use animation vector drawable to show collection state
        val stateSet = intArrayOf(android.R.attr.state_checked * if (collectionState) 1 else -1)
        mCollectImageView.isSelected = collectionState
        mCollectImageView.setImageState(stateSet, true)

        // Hide source textview if there is no source
        if (TextUtils.isEmpty(source)) {
            mSourceTextView.visibility = View.GONE
        } else {
            mSourceTextView.visibility = View.VISIBLE
        }

        // Update layout padding
        val paddingTop = mCommonPrefs.getString(
            PREF_COMMON_PADDING_TOP, PREF_COMMON_PADDING_TOP_DEFAULT)!!.toInt()
        val paddingBottom = mCommonPrefs.getString(
            PREF_COMMON_PADDING_BOTTOM, PREF_COMMON_PADDING_BOTTOM_DEFAULT)!!.toInt()
        mQuoteContainer.setPadding(mQuoteContainer.paddingStart,
            paddingTop.dp2px().toInt(),
            mQuoteContainer.paddingEnd,
            paddingBottom.dp2px().toInt())

        // Quote spacing
        val quoteSpacing = mCommonPrefs.getString(PREF_COMMON_QUOTE_SPACING,
            PREF_COMMON_QUOTE_SPACING_DEFAULT)!!.toInt()
        (mSourceTextView.layoutParams as LinearLayout.LayoutParams).topMargin = quoteSpacing.dp2px()
            .toInt()

        // Update font size
        val textFontSize = mCommonPrefs.getString(
            PREF_COMMON_FONT_SIZE_TEXT, PREF_COMMON_FONT_SIZE_TEXT_DEFAULT)!!.toFloat()
        val sourceFontSize = mCommonPrefs.getString(
            PREF_COMMON_FONT_SIZE_SOURCE, PREF_COMMON_FONT_SIZE_SOURCE_DEFAULT)!!.toFloat()
        mQuoteTextView.textSize = textFontSize
        mSourceTextView.textSize = sourceFontSize

        // Font properties
        val quoteStyles = mCommonPrefs.getStringSet(PREF_COMMON_FONT_STYLE_TEXT, null)
        val sourceStyles = mCommonPrefs.getStringSet(PREF_COMMON_FONT_STYLE_SOURCE, null)
        val quoteStyle = getTypefaceStyle(quoteStyles)
        val sourceStyle = getTypefaceStyle(sourceStyles)
        val font = mCommonPrefs.getString(
            PREF_COMMON_FONT_FAMILY, PREF_COMMON_FONT_FAMILY_DEFAULT_SANS_SERIF)
        val quoteTypeface = loadTypeface(font, quoteStyle)
        val sourceTypeface = loadTypeface(font, sourceStyle)
        mQuoteTextView.setTypeface(quoteTypeface, quoteStyle)
        mSourceTextView.setTypeface(sourceTypeface, sourceStyle)
    }

    private fun refreshAodQuote() {
        Xlog.d(TAG, "Quote changed, updating aod layout. " +
                "Current thread [" + Thread.currentThread().id + "]" +
                if (Looper.myLooper() == Looper.getMainLooper()) " is UI-Thread" else "")
        mDisplayOnAod = mCommonPrefs.getBoolean(PREF_COMMON_DISPLAY_ON_AOD, false)
        if (!isAodViewAvailable) {
            return
        }
        if (!mDisplayOnAod) {
            mAodQuoteContainer.visibility = View.GONE
            return
        } else {
            mAodQuoteContainer.visibility = View.VISIBLE
        }

        // Update quote text
        var text = mQuotePrefs.getString(PREF_QUOTES_TEXT, null)
        var source = mQuotePrefs.getString(PREF_QUOTES_SOURCE, null)
        val author = mQuotePrefs.getString(PREF_QUOTES_AUTHOR, null)
        if (text.isNullOrBlank()) {
            try {
                text = sModuleRes.getString(RES_STRING_OPEN_APP_1)
                source = sModuleRes.getString(RES_STRING_OPEN_APP_2)
            } catch (e: NotFoundException) {
                Xlog.e(TAG, "Could not load string resource", e)
                text = null
                source = null
            }
        } else {
            source =
                buildReadableSource(source, author, !isQuoteGeneratedByApp(text, source, author))
        }
        mAodQuoteTextView.text = text
        mAodSourceTextView.text = source

        // Hide source textview if there is no source
        if (TextUtils.isEmpty(source)) {
            mAodSourceTextView.visibility = View.GONE
        } else {
            mAodSourceTextView.visibility = View.VISIBLE
        }

        // Update layout padding
        val paddingTop = mCommonPrefs.getString(
            PREF_COMMON_PADDING_TOP, PREF_COMMON_PADDING_TOP_DEFAULT)!!.toInt()
        val paddingBottom = mCommonPrefs.getString(
            PREF_COMMON_PADDING_BOTTOM, PREF_COMMON_PADDING_BOTTOM_DEFAULT)!!.toInt()
        mAodQuoteContainer.setPadding(mAodQuoteContainer.paddingStart,
            paddingTop.dp2px().toInt(),
            mAodQuoteContainer.paddingEnd,
            paddingBottom.dp2px().toInt())

        // Quote spacing
        val quoteSpacing = mCommonPrefs.getString(PREF_COMMON_QUOTE_SPACING,
            PREF_COMMON_QUOTE_SPACING_DEFAULT)!!.toInt()
        (mAodSourceTextView.layoutParams as LinearLayout.LayoutParams).topMargin =
            quoteSpacing.dp2px().toInt()

        // Update font size
        val textFontSize = mCommonPrefs.getString(
            PREF_COMMON_FONT_SIZE_TEXT, PREF_COMMON_FONT_SIZE_TEXT_DEFAULT)!!.toFloat()
        val sourceFontSize = mCommonPrefs.getString(
            PREF_COMMON_FONT_SIZE_SOURCE, PREF_COMMON_FONT_SIZE_SOURCE_DEFAULT)!!.toFloat()
        mAodQuoteTextView.textSize = textFontSize
        mAodSourceTextView.textSize = sourceFontSize

        // Font properties
        val quoteStyles = mCommonPrefs.getStringSet(PREF_COMMON_FONT_STYLE_TEXT, null)
        val sourceStyles = mCommonPrefs.getStringSet(PREF_COMMON_FONT_STYLE_SOURCE, null)
        val quoteStyle = getTypefaceStyle(quoteStyles)
        val sourceStyle = getTypefaceStyle(sourceStyles)
        val font = mCommonPrefs.getString(
            PREF_COMMON_FONT_FAMILY, PREF_COMMON_FONT_FAMILY_LEGACY_DEFAULT)
        val quoteTypeface = loadTypeface(font, quoteStyle)
        val sourceTypeface = loadTypeface(font, sourceStyle)
        mAodQuoteTextView.setTypeface(quoteTypeface, quoteStyle)
        mAodSourceTextView.setTypeface(sourceTypeface, sourceStyle)
    }

    private val isAodViewAvailable: Boolean
        get() = ::mAodQuoteContainer.isInitialized && ::mAodQuoteTextView.isInitialized
                && ::mAodSourceTextView.isInitialized

    private fun refreshQuoteRemote(context: Context) {
        val uri = Uri.parse("content://${ActionProvider.AUTHORITY}").buildUpon()
            .appendPath("refresh").build()
        context.contentResolver.query(uri, null, null, null, null)?.close()
    }

    private fun collectQuoteRemote(context: Context) {
        val text = mQuoteTextView.text.toString()
        val source = mSourceTextView.hint?.toString() ?: ""
        val author = mQuoteTextView.hint?.toString() ?: ""
        val uri = ActionProvider.CONTENT_URI.buildUpon().appendPath("collect").build()
        val values = ContentValues(3)
        values.put(QuoteCollectionContract.TEXT, text)
        values.put(QuoteCollectionContract.SOURCE, source)
        values.put(QuoteCollectionContract.AUTHOR, author)
        values.put(QuoteCollectionContract.MD5, ("$text$source$author").md5())
        val resolver = context.contentResolver
        val resultUri = resolver.insert(uri, values)
        if (resultUri?.lastPathSegment == "-1") {
            resetTranslationAnimator()
        }
    }

    private fun deleteCollectedQuoteRemote(context: Context) {
        val text = mQuoteTextView.text.toString()
        val source = mSourceTextView.hint?.toString() ?: ""
        val author = mQuoteTextView.hint?.toString() ?: ""
        val uri = ActionProvider.CONTENT_URI.buildUpon().appendPath("collect").build()
        val resolver = context.contentResolver
        val result = resolver.delete(uri, QuoteCollectionContract.MD5 + "=?",
            arrayOf(("$text$source$author").md5()))
        if (result < 0) {
            resetTranslationAnimator()
        }
    }

    private fun setRefreshAnimator() {
        mRefreshImageView.rotation = 0f
        mRefreshImageView.animate()
            .rotationBy(360f)
            .setInterpolator(LinearInterpolator())
            .withEndAction(object : Runnable {
                override fun run() {
                    mRefreshImageView.animate()
                        .rotationBy(360f)
                        .setInterpolator(LinearInterpolator())
                        .withEndAction(this)
                        .setDuration(LAYOUT_ANIMATION_DURATION)
                        .start()
                }
            })
            .setDuration(LAYOUT_ANIMATION_DURATION)
            .start()
    }

    private fun resetRefreshAnimator() {
        mRefreshImageView.animate().cancel()
        if (mRefreshImageView.rotation > 0) {
            val remainDegree = 360 - mRefreshImageView.rotation % 360
            mRefreshImageView.animate()
                .setDuration((LAYOUT_ANIMATION_DURATION / 360f * remainDegree).toLong())
                .setInterpolator(DecelerateInterpolator())
                .rotationBy(remainDegree)
                .withEndAction { mRefreshImageView.rotation = 0f }
                .start()
        }
    }

    private fun setTranslationAnimator() {
        mQuoteContainer.animate()
            .translationX(mLayoutTranslation.dp2px())
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setDuration(LAYOUT_ANIMATION_DURATION)
            .start()
        mActionContainer.animate()
            .translationX(mLayoutTranslation.dp2px())
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setDuration(LAYOUT_ANIMATION_DURATION)
            .start()
    }

    private fun resetTranslationAnimator() {
        if (!::mQuoteContainer.isInitialized) return
        resetRefreshAnimator()
        mQuoteContainer.animate().cancel()
        mActionContainer.animate().cancel()
        mQuoteContainer.animate()
            .translationX(0f)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setDuration(LAYOUT_ANIMATION_DURATION)
            .start()
        mActionContainer.animate()
            .translationX(0f)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setDuration(LAYOUT_ANIMATION_DURATION)
            .start()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        if (key != PREF_QUOTES_COLLECTION_STATE) {
            // Block translation animation resetting when the quote is collecting
            resetTranslationAnimator()
        }
        refreshLockscreenQuote()
        // Refresh the injected AOD quote views on thread of AOD layout.
        mAodHandler?.post { refreshAodQuote() }
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (PACKAGE_SYSTEM_UI != lpparam.packageName) {
            return
        }
        hookLockscreenLayout(lpparam)
        hookLockscreenClick(lpparam)
        if (XposedUtils.isAodHookAvailable) {
            hookAodLayout(lpparam)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Notifications on Android S will cover a part of quote view.
            // Added a padding to avoid this.
            hookKeyguardClockPositionAlgorithm(lpparam)
        }
        Xlog.i(TAG, "QuoteLockX Xposed module initialized!")
    }

    /**
     * Hook and modify `com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout#updateTopPadding()`
     * will cause the animation stuck when clicking on the blank area of the lockscreen on Android S.
     * So we need to hook `com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm#loadDimens()`
     * to modify `mStatusViewBottomMargin` field to change the margin between
     * the bottom of the status view and the notification shade.
     *
     * @see [com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/packages/SystemUI/src/com/android/systemui/statusbar/phone/KeyguardClockPositionAlgorithm.java)
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun hookKeyguardClockPositionAlgorithm(lpparam: LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm",
                lpparam.classLoader,
                "loadDimens",
                "android.content.res.Resources",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Xlog.d(TAG,
                            "KeyguardClockPositionAlgorithm#loadDimens calling...")
                        param.thisObject.apply {
                            getReflectionField<Int>("mStatusViewBottomMargin")?.let {
                                setReflectionField("mStatusViewBottomMargin",
                                    it + 28F.dp2px().toInt())
                            }
                        }
                    }
                })
        }
    }

    private fun hookLockscreenLayout(lpparam: LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "com.android.keyguard.KeyguardStatusView", lpparam.classLoader,
            "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    Xlog.i(TAG, "KeyguardStatusView#onFinishInflate() called, injecting views...")
                    val self = param.thisObject as GridLayout
                    if (self.childCount != 1) {
                        return
                    }
                    val linearLayout = self.getChildAt(0) as LinearLayout
                    val context = linearLayout.context
                    val layoutInflater = LayoutInflater.from(context)
                    val parser: XmlPullParser = try {
                        sModuleRes.getLayout(RES_LAYOUT_QUOTE_LAYOUT)
                    } catch (e: NotFoundException) {
                        Xlog.e(TAG, "Could not find quote layout, aborting", e)
                        return
                    }
                    val view = layoutInflater.inflate(parser, null)
                    linearLayout.addView(view)
                    try {
                        mQuoteContainer =
                            sModuleRes.findViewById(view, RES_ID_QUOTE_CONTAINER) as LinearLayout
                        mQuoteTextView =
                            sModuleRes.findViewById(view, RES_ID_QUOTE_TEXTVIEW) as TextView
                        mSourceTextView =
                            sModuleRes.findViewById(view, RES_ID_SOURCE_TEXTVIEW) as TextView
                        mActionContainer =
                            sModuleRes.findViewById(view, RES_ID_ACTION_CONTAINER) as LinearLayout
                        mRefreshImageView =
                            sModuleRes.findViewById(view, RES_ID_REFRESH_IMAGE_VIEW) as ImageView
                        val refreshIcon = sModuleRes.getDrawable(RES_ID_REFRESH_ICON)
                        mRefreshImageView.setImageDrawable(refreshIcon)
                        mCollectImageView =
                            sModuleRes.findViewById(view, RES_ID_COLLECT_IMAGE_VIEW) as ImageView
                        val collectIcon = sModuleRes.getDrawable(RES_ID_COLLECT_ICON)
                        mCollectImageView.setImageDrawable(collectIcon)
                    } catch (e: NotFoundException) {
                        Xlog.e(TAG, "Could not find text views, aborting", e)
                        return
                    }
                    mQuoteContainer.setOnLongClickListener {
                        Xlog.d(TAG, "QuoteContainer onLongClick")
                        if (mQuoteContainer.translationX != 0f) {
                            resetTranslationAnimator()
                        } else {
                            setTranslationAnimator()
                        }
                        true
                    }
                    mRefreshImageView.setOnClickListener { v: View ->
                        setRefreshAnimator()
                        refreshQuoteRemote(v.context)
                    }
                    mCollectImageView.setOnClickListener { v: View ->
                        if (v.isSelected) {
                            deleteCollectedQuoteRemote(v.context)
                        } else {
                            collectQuoteRemote(v.context)
                        }
                    }
                    Xlog.i(TAG, "View injection complete, registering preferences...")
                    if (!::mCommonPrefs.isInitialized) {
                        mCommonPrefs =
                            RemotePreferences(context, PreferenceProvider.AUTHORITY, PREF_COMMON)
                        mCommonPrefs.registerOnSharedPreferenceChangeListener(this@LockscreenHook)
                    }
                    if (!::mQuotePrefs.isInitialized) {
                        mQuotePrefs =
                            RemotePreferences(context, PreferenceProvider.AUTHORITY, PREF_QUOTES)
                        mQuotePrefs.registerOnSharedPreferenceChangeListener(this@LockscreenHook)
                    }
                    Xlog.i(TAG, "Preferences registered, performing initial refresh...")
                    refreshLockscreenQuote()
                }
            })
    }

    private fun hookLockscreenClick(lpparam: LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                "com.android.systemui.statusbar.phone.PanelView"
            else "com.android.systemui.statusbar.phone.PanelViewController",
            lpparam.classLoader,
            "onEmptySpaceClick", Float::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    Xlog.i(TAG,
                        "PanelViewController#onEmptySpaceClick() called, reset QuoteContainer position...")
                    if (!::mQuoteContainer.isInitialized || !mQuoteContainer.isAttachedToWindow) {
                        Xlog.e(TAG, "QuoteContainer is empty or not attached to window")
                        return
                    }
                    resetTranslationAnimator()
                }
            })
    }

    private fun hookAodLayout(lpparam: LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.oneplus.aod.OpClockViewCtrl", lpparam.classLoader,
                "initViews", ViewGroup::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Xlog.i(TAG,
                            "com.oneplus.aop.OpClockViewCtrl#initViews() called, injecting views...")
                        // This method is not called on UI thread, so the injected views should be refreshed on current thread.
                        mAodHandler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
                        val root = param.args[0] as ViewGroup
                        Xlog.d(TAG, "OpClockViewCtrl root $root")
                        val context = root.context
                        val opAodContainer = root.findViewById<View>(
                            context.resources.getIdentifier(RES_ID_OP_AOD_CONTAINER,
                                "id", PACKAGE_SYSTEM_UI)) as LinearLayout
                        Xlog.d(TAG, "OpClockViewCtrl opAodContainer$opAodContainer")
                        val layoutInflater = LayoutInflater.from(context)
                        val parser: XmlPullParser = try {
                            sModuleRes.getLayout(RES_LAYOUT_QUOTE_LAYOUT)
                        } catch (e: NotFoundException) {
                            Xlog.e(TAG, "Could not find quote layout, aborting", e)
                            return
                        }
                        val view = layoutInflater.inflate(parser, null)
                        opAodContainer.addView(view)
                        try {
                            mAodQuoteContainer =
                                sModuleRes.findViewById(view,
                                    RES_ID_QUOTE_CONTAINER) as LinearLayout
                            mAodQuoteTextView =
                                sModuleRes.findViewById(view, RES_ID_QUOTE_TEXTVIEW) as TextView
                            mAodSourceTextView =
                                sModuleRes.findViewById(view, RES_ID_SOURCE_TEXTVIEW) as TextView
                            val aodActionContainer =
                                sModuleRes.findViewById(view,
                                    RES_ID_ACTION_CONTAINER) as LinearLayout
                            aodActionContainer.visibility = View.GONE
                        } catch (e: NotFoundException) {
                            Xlog.e(TAG, "Could not find text views, aborting", e)
                        }
                    }
                })
        }.onFailure {
            Xlog.e(TAG, "Failed to hook com.oneplus.aod.OpClockViewCtrl#initViews()", it)
        }
    }

    @Throws(Throwable::class)
    override fun handleInitPackageResources(resparam: InitPackageResourcesParam) {
        sModuleRes = createInstance(sModulePath, resparam.res)
        if (PACKAGE_SYSTEM_UI == resparam.packageName && !::quotesGeneratedByApp.isInitialized) {
            // Load predefined quotes from app in all locales
            val locale = resparam.res.configuration.locale
            val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                resparam.res.configuration.locales
            } else null
            quotesGeneratedByApp = arrayOf(Locale.ENGLISH,
                Locale.SIMPLIFIED_CHINESE,
                Locale.TRADITIONAL_CHINESE).flatMap {
                resparam.res.configuration.setLocale(it)
                createInstance(sModulePath, resparam.res).run {
                    listOf(
                        getString(RES_STRING_OPEN_APP_1),
                        getString(RES_STRING_OPEN_APP_2),
                        getString(RES_STRING_CUSTOM_SETUP_1),
                        getString(RES_STRING_CUSTOM_SETUP_2),
                        getString(RES_STRING_COLLECTIONS_SETUP_1),
                        getString(RES_STRING_COLLECTIONS_SETUP_2)
                    )
                }
            }.toSet()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                resparam.res.configuration.setLocales(locales)
            } else {
                resparam.res.configuration.setLocale(locale)
            }
        }
    }

    @Throws(Throwable::class)
    override fun initZygote(startupParam: StartupParam) {
        sModulePath = startupParam.modulePath
    }

    companion object {
        private val TAG = className<LockscreenHook>()
        const val PACKAGE_SYSTEM_UI = "com.android.systemui"
        private const val RES_LAYOUT_QUOTE_LAYOUT = "quote_layout"
        private const val RES_STRING_OPEN_APP_1 = "open_quotelock_app_line1"
        private const val RES_STRING_OPEN_APP_2 = "open_quotelock_app_line2"
        private const val RES_STRING_CUSTOM_SETUP_1 = "module_custom_setup_line1"
        private const val RES_STRING_CUSTOM_SETUP_2 = "module_custom_setup_line2"
        private const val RES_STRING_COLLECTIONS_SETUP_1 = "module_collections_setup_line1"
        private const val RES_STRING_COLLECTIONS_SETUP_2 = "module_collections_setup_line2"
        private const val RES_ID_QUOTE_CONTAINER = "quote_container"
        private const val RES_ID_QUOTE_TEXTVIEW = "quote_textview"
        private const val RES_ID_SOURCE_TEXTVIEW = "source_textview"
        private const val RES_ID_ACTION_CONTAINER = "action_container"
        private const val RES_ID_REFRESH_IMAGE_VIEW = "refresh_image_view"
        private const val RES_ID_COLLECT_IMAGE_VIEW = "collect_image_view"

        /** For OnePlus AOD  */
        private const val RES_ID_OP_AOD_CONTAINER = "op_aod_system_info_container"
        private const val RES_ID_REFRESH_ICON = "ic_round_refresh_24dp"
        private const val RES_ID_COLLECT_ICON = "anim_star"
        private const val LAYOUT_ANIMATION_DURATION: Long = 500
        private var sModulePath: String? = null
        private lateinit var sModuleRes: XSafeModuleResources
    }
}