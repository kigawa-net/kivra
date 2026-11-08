package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.PROMOTION_FAILED_TEXT
import net.kigawa.kivra.ui.PROMOTION_NOTE_TEXT
import net.kigawa.kivra.ui.PROMOTION_NOT_FETCHED_TEXT
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiPromotionReadout
import net.kigawa.kivra.ui.promotionLine
import net.kigawa.kivra.ui.promotionShapeOk
import net.kigawa.kivra.ui.promotionSummary

/**
 * 段階 1 → 2 の昇格条件(`#/autopilot` の中、自律運転が有効なときだけ呼ばれる)。読み取りのみで、この画面は何も変えない。
 * 条件の名前は固定の表から引く。応答の形が合わない・読めない時は固定の文言だけを出す(サーバーの文字は出さない)。
 */
@Composable
internal fun PromotionSection() {
    val loaded = rememberLoad("promotion") {
        val readout: ApiPromotionReadout = ApiClient.getPromotion()
        if (!promotionShapeOk(readout.conditions)) {
            throw ApiException(null, "response is not the expected format")
        }
        readout
    }
    Column {
        Text("段階 1 → 2 の昇格条件（ADR 0011 §6.3）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val readout = loaded.value
        when {
            loaded.error != null -> Text(PROMOTION_FAILED_TEXT, color = MaterialTheme.colorScheme.error)
            readout == null -> Text(PROMOTION_NOT_FETCHED_TEXT)
            else -> {
                Text(promotionSummary(readout.conditions))
                readout.conditions.forEach { c ->
                    Spacer(Modifier.height(4.dp))
                    Text("・" + promotionLine(c))
                }
                Spacer(Modifier.height(8.dp))
                Text(PROMOTION_NOTE_TEXT, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
