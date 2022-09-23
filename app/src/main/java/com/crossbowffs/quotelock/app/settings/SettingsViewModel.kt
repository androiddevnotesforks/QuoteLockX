package com.crossbowffs.quotelock.app.settings

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crossbowffs.quotelock.app.SnackBarEvent
import com.crossbowffs.quotelock.app.emptySnackBarEvent
import com.crossbowffs.quotelock.consts.PREF_COMMON_QUOTE_MODULE_DEFAULT
import com.crossbowffs.quotelock.data.ConfigurationRepository
import com.crossbowffs.quotelock.data.api.QuoteModule
import com.crossbowffs.quotelock.data.api.QuoteModuleData
import com.crossbowffs.quotelock.data.modules.ModuleNotFoundException
import com.crossbowffs.quotelock.data.modules.Modules
import com.crossbowffs.quotelock.data.modules.QuoteRepository
import com.crossbowffs.quotelock.di.ResourceProvider
import com.crossbowffs.quotelock.utils.WorkUtils
import com.crossbowffs.quotelock.utils.XposedUtils
import com.crossbowffs.quotelock.utils.findProcessAndKill
import com.crossbowffs.quotelock.xposed.LockscreenHook
import com.yubyf.quotelockx.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * UI state for the settings screen.
 */
data class SettingsUiState(
    val enableAod: Boolean,
    val displayOnAod: Boolean,
    val unmeteredOnly: Boolean,
    val moduleData: QuoteModuleData?,
    val updateInfo: String,
)

/**
 * UI state for the settings screen.
 */
sealed class SettingsDialogUiState {
    data class ModuleProviderDialog(
        val modules: Pair<Array<String>, Array<String?>>,
        val currentModule: String,
    ) : SettingsDialogUiState()

    data class RefreshIntervalDialog(
        val currentInterval: Int,
    ) : SettingsDialogUiState()

    object None : SettingsDialogUiState()
}

/**
 * @author Yubyf
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val configurationRepository: ConfigurationRepository,
    private val quoteRepository: QuoteRepository,
    private val resourceProvider: ResourceProvider,
) : ViewModel() {

    private val _uiEvent = MutableSharedFlow<SnackBarEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    private val _uiState = mutableStateOf(
        SettingsUiState(
            // Only enable DisplayOnAOD on tested devices.
            XposedUtils.isAodHookAvailable,
            configurationRepository.displayOnAod,
            configurationRepository.isUnmeteredNetworkOnly,
            null,
            resourceProvider.getString(
                R.string.pref_refresh_info_summary, "-"))
    )
    val uiState: State<SettingsUiState> = _uiState

    private val _uiDialogState = mutableStateOf<SettingsDialogUiState>(SettingsDialogUiState.None)
    val uiDialogState: State<SettingsDialogUiState> = _uiDialogState

    init {
        viewModelScope.launch {
            configurationRepository.quoteConfigsFlow.onEach {
                _uiState.value = _uiState.value.copy(
                    displayOnAod = it.displayOnAod,
                    unmeteredOnly = it.unmeteredOnly,
                )
            }.launchIn(this)
            configurationRepository.quoteModuleNotifyFlow.onEach {
                onSelectedModuleChanged()
            }.launchIn(this)
            configurationRepository.quoteRefreshRateNotifyFlow.onEach {
                WorkUtils.createQuoteDownloadWork(context,
                    configurationRepository.refreshInterval,
                    configurationRepository.isRequireInternet,
                    configurationRepository.isUnmeteredNetworkOnly,
                    true)
            }.launchIn(this)
            quoteRepository.lastUpdateFlow.onEach {
                _uiState.value = _uiState.value.copy(
                    updateInfo = resourceProvider.getString(
                        R.string.pref_refresh_info_summary,
                        if (it > 0) DATE_FORMATTER.format(Date(it)) else "-")
                )
            }.launchIn(this)
            val updateTime = quoteRepository.getLastUpdateTime()
            _uiState.value = _uiState.value.copy(
                updateInfo = resourceProvider.getString(
                    R.string.pref_refresh_info_summary,
                    if (updateTime > 0) DATE_FORMATTER.format(Date(updateTime)) else "-")
            )
            updateCurrentModule()
        }
    }

    fun switchDisplayOnAod(checked: Boolean) {
        configurationRepository.displayOnAod = checked
    }

    private fun getModuleList(): Pair<Array<String>, Array<String?>> {
        // Get quote module list
        val quoteModules = quoteRepository.getAllModules()
        return quoteModules.map { module ->
            val moduleData = quoteRepository.getQuoteModuleData(module)
            moduleData.displayName to module::class.qualifiedName
        }.unzip().let {
            it.first.toTypedArray() to it.second.toTypedArray()
        }
    }

    fun loadModuleProviders() {
        _uiDialogState.value =
            SettingsDialogUiState.ModuleProviderDialog(getModuleList(),
                configurationRepository.currentModuleName)
    }

    fun selectModule(moduleName: String) {
        configurationRepository.currentModuleName = moduleName
    }

    fun loadRefreshInterval() {
        _uiDialogState.value =
            SettingsDialogUiState.RefreshIntervalDialog(configurationRepository.refreshInterval)
    }

    fun switchUnmeteredOnly(checked: Boolean) {
        configurationRepository.isUnmeteredNetworkOnly = checked
    }

    fun selectRefreshInterval(interval: Int) {
        configurationRepository.refreshInterval = interval
    }

    fun restartSystemUi() {
        findProcessAndKill(LockscreenHook.PACKAGE_SYSTEM_UI)
    }

    fun cancelDialog() {
        _uiDialogState.value = SettingsDialogUiState.None
    }

    fun snackBarShown() = viewModelScope.launch {
        _uiEvent.emit(emptySnackBarEvent)
    }

    private suspend fun onSelectedModuleChanged() {
        if (updateCurrentModule()) {
            // Update quotes.
            quoteRepository.downloadQuote()
        }
    }

    /**
     * @return true if current module is changed.
     */
    private suspend fun updateCurrentModule(): Boolean {
        val module = loadSelectedModule()

        val quoteModuleData = quoteRepository.getQuoteModuleData(module)
        if (quoteModuleData == _uiState.value.moduleData) {
            return false
        }
        _uiState.value = _uiState.value.copy(moduleData = quoteModuleData)
        configurationRepository.updateConfiguration(quoteModuleData)
        return true
    }

    private suspend fun loadSelectedModule(): QuoteModule {
        val moduleClsName = configurationRepository.currentModuleName
        return try {
            Modules[moduleClsName]
        } catch (e: ModuleNotFoundException) {
            // Reset to the default module if the currently
            // selected one was not found. Change through the
            // ListPreference so that it updates its value.
            selectModule(PREF_COMMON_QUOTE_MODULE_DEFAULT)
            _uiEvent.emit(SnackBarEvent(message = resourceProvider.getString(R.string.selected_module_not_found)))
            loadSelectedModule()
        }
    }

    companion object {
        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}