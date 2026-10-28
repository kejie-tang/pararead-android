package org.pararead.android

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Menu language is independent of the source/target language and translation cache. */
@Composable fun AppLanguage(language: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val system = LocalConfiguration.current
    val tag = when (language) {
        "en" -> "en"
        "zh-CN" -> "zh-CN"
        else -> if (system.locales[0].language == "zh") "zh-CN" else "en"
    }
    val configuration = remember(system, tag) { Configuration(system).apply { setLocale(Locale.forLanguageTag(tag)) } }
    val context = remember(base, configuration) { ContextThemeWrapper(base, 0).apply { applyOverrideConfiguration(configuration) } }
    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, content = content)
}

internal fun Context.withAppLanguage(): Context {
    val language = Settings(this).uiLanguage
    val tag = if (language == "system") {
        if (resources.configuration.locales[0].language == "zh") "zh-CN" else "en"
    } else language
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
}
