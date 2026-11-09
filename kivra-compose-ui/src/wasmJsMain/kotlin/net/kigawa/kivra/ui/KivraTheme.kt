package net.kigawa.kivra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import net.kigawa.kivra.kivra_compose_ui.generated.resources.Res

/** 同梱の日本語フォント(`composeResources/font/`。Noto Sans JP のサブセット、OFL)。 */
private const val FONT_FILE = "font/noto_sans_jp_regular.ttf"

/** フォントの取得と読み込みを待つ上限。過ぎたら既定フォントで描く(豆腐は出るが、画面は出す)。 */
private const val FONT_LOAD_TIMEOUT_MS = 15_000L

private sealed interface FontState {
    /** まだ読めていない。何も描かない(豆腐を一瞬出さないため)。 */
    data object Loading : FontState

    /** 読めた([family])、または読めなかった(null: 既定フォント)。どちらでも描き始める。 */
    class Ready(val family: FontFamily?) : FontState
}

/**
 * Compose Wasm は canvas に描くため、ブラウザのシステムフォントは使えない。同梱の日本語フォントを読み終えてから、
 * それを既定のフォントにして描く。
 *
 * フォントの取得は [Res.readBytes] で行う(バイト列が届いた時点で読めたとわかる)。取得・読み込みに失敗しても、
 * 時間切れでも、例外は出さずに既定フォントで描く。その場合は日本語が豆腐になる。
 */
@Composable
fun KivraTheme(content: @Composable () -> Unit) {
    val resolver = LocalFontFamilyResolver.current
    val state by produceState<FontState>(FontState.Loading, resolver) {
        value = loadKivraFont(resolver)
    }
    when (val s = state) {
        is FontState.Loading -> Box(modifier = Modifier.fillMaxSize().background(Color.White))
        is FontState.Ready -> {
            val typography = s.family?.let { Typography().withFontFamily(it) } ?: Typography()
            MaterialTheme(typography = typography) { content() }
        }
    }
}

private suspend fun loadKivraFont(resolver: FontFamily.Resolver): FontState {
    return try {
        val bytes = withTimeoutOrNull(FONT_LOAD_TIMEOUT_MS) { Res.readBytes(FONT_FILE) }
        if (bytes == null) return FontState.Ready(null)
        val family = FontFamily(Font("noto_sans_jp_regular", bytes))
        // 字形を使える状態にしてから描く。時間切れでも描き始める(その場合は読み終えた時点で自動的に差し替わる)。
        withTimeoutOrNull(FONT_LOAD_TIMEOUT_MS) { resolver.preload(family) }
        FontState.Ready(family)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        FontState.Ready(null)
    }
}

/** The default Material 3 typography with every text style on [family] (keeps each style's size and weight). */
private fun Typography.withFontFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
