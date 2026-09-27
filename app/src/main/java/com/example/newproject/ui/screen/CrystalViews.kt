package com.example.newproject.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.crystalsForNote
import com.example.newproject.model.Crystal
import com.example.newproject.model.state.CrystalLogState
import com.example.newproject.ui.CRYSTAL_PANEL_TITLE
import com.example.newproject.ui.crystalListEntryLabel
import com.example.newproject.ui.crystalNoteLabel
import com.example.newproject.ui.crystalSourcesLine
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.PanelBlue

/**
 * ✨タブの要約の下に出す結晶（→ `docs/dev/features/reflect_crystal.md` §3・§5「表示」）。
 *
 * **このノートを根拠に含む結晶**だけを出し、無ければ一覧への入口1行だけにする。
 * Vault に結晶が1件も無ければ何も出さない。生成中・失敗の表示は持たない（判断8）。
 * 面は要約と同じパネルを使い、新しい色・形の意味を足さない。押せる行は見出しと同じ
 * `AccentText` に `›` を添え、色だけで押せることを伝えない（→ ui_design_principles §1 の 1.4.1）。
 */
@Composable
internal fun CrystalPanel(
    crystalLog: CrystalLogState,
    notePath: String?,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier
) {
    val crystals = (crystalLog as? CrystalLogState.Loaded)?.crystals ?: return
    if (crystals.isEmpty()) return
    val shown = crystalsForNote(crystals, notePath)

    Surface(modifier = modifier.fillMaxWidth(), color = PanelBlue, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            if (shown.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = CRYSTAL_PANEL_TITLE, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentText)
                shown.forEach { crystal ->
                    Spacer(modifier = Modifier.height(8.dp))
                    CrystalCard(crystal = crystal)
                }
            }
            Text(
                text = crystalListEntryLabel(crystals.size) + " ›",
                fontSize = 13.sp,
                color = AccentText,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = onOpenList)
                    .padding(vertical = 14.dp)
            )
        }
    }
}

/**
 * 結晶1件。1文と「A・B から」を出し、押すと根拠（ノート名とAIへ渡した断片）を開く。
 *
 * [onOpenSource] を渡したときだけ、根拠のノート名を押せる。[canOpen] が偽のノート
 * （改名・移動・削除で今の走査に無い）は押せない表示にする。
 */
@Composable
internal fun CrystalCard(
    crystal: Crystal,
    dateLabel: String? = null,
    onOpenSource: ((String) -> Unit)? = null,
    canOpen: (String) -> Boolean = { false }
) {
    var expanded by rememberSaveable(crystal.fileName) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (dateLabel != null) {
            Text(text = dateLabel, fontSize = 12.sp, color = OnSurfaceMuted)
        }
        Text(text = crystal.sentence, fontSize = 14.sp, lineHeight = 22.sp, color = OnSurface)
        Text(
            text = crystalSourcesLine(crystal) + if (expanded) "  ▴" else "  ▾",
            fontSize = 12.sp,
            color = OnSurfaceMuted,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { stateDescription = if (expanded) "根拠を開いています" else "根拠を閉じています" }
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 14.dp)
        )
        if (expanded) {
            crystal.sources.forEach { source ->
                val openable = onOpenSource != null && canOpen(source.vaultRelativePath)
                Text(
                    text = crystalNoteLabel(source.noteTitle) + if (openable) " ›" else "",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (openable) AccentText else OnSurface,
                    modifier = if (openable) {
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(role = Role.Button) { onOpenSource?.invoke(source.vaultRelativePath) }
                            .padding(vertical = 14.dp)
                    } else {
                        Modifier.padding(top = 6.dp)
                    }
                )
                Text(text = source.fragment, fontSize = 12.sp, lineHeight = 18.sp, color = OnSurfaceMuted)
            }
        }
    }
}
