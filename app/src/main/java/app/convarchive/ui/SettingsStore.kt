package app.convarchive.ui

import android.content.Context
import app.convarchive.export.ExportFormat
import app.convarchive.ui.browser.UserAgentMode
import app.convarchive.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS = "settings"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_DEFAULT_EXPORT_FORMAT = "default_export_format"
private const val KEY_UA_MODE = "ua_mode"

/**
 * App settings backed by SharedPreferences ("settings").
 * Theme mode is exposed as a StateFlow so MainActivity recomposes on change.
 */
object SettingsStore {

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _themeMode.value = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun getDefaultExportFormat(context: Context): ExportFormat {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching {
            ExportFormat.valueOf(prefs.getString(KEY_DEFAULT_EXPORT_FORMAT, ExportFormat.JSON.name)!!)
        }.getOrDefault(ExportFormat.JSON)
    }

    fun setDefaultExportFormat(context: Context, format: ExportFormat) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEFAULT_EXPORT_FORMAT, format.name).apply()
    }

    /** User-agent mode for the in-app browsers (v1.2.0). Defaults to STANDARD. */
    fun getUserAgentMode(context: Context): UserAgentMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching {
            UserAgentMode.valueOf(prefs.getString(KEY_UA_MODE, UserAgentMode.STANDARD.name)!!)
        }.getOrDefault(UserAgentMode.STANDARD)
    }

    fun setUserAgentMode(context: Context, mode: UserAgentMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_UA_MODE, mode.name).apply()
    }
}
