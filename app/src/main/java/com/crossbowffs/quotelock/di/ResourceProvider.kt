package com.crossbowffs.quotelock.di

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import com.crossbowffs.quotelock.app.font.FontInfo
import com.crossbowffs.quotelock.data.api.isQuoteGeneratedByApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @author Yubyf
 */
@Singleton
class ResourceProvider @Inject constructor(@ApplicationContext private val context: Context) {

    fun getString(@StringRes resId: Int): String {
        return context.getString(resId)
    }

    fun getString(@StringRes resId: Int, vararg args: Any): String {
        return context.getString(resId, *args)
    }

    fun getStringArray(@ArrayRes resId: Int): Array<String> {
        return context.resources.getStringArray(resId)
    }

    fun getDrawable(@DrawableRes resId: Int): Drawable? {
        return AppCompatResources.getDrawable(context, resId)
    }

    fun isQuoteJustForDisplay(text: String, source: String?, author: String?) =
        context.isQuoteGeneratedByApp(text, source, author)

    fun getFontLocaleName(fontInfo: FontInfo) =
        with(fontInfo) { context.resources.configuration.localeName }
}