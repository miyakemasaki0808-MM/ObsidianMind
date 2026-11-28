package com.example.newproject.architecture

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * 文字のボタンは高さを固定しない（→ `docs/dev/system/ui_copy.md`・`docs/dev/system/ui_design_principles.md` のタッチの大きさ）。
 * 高さを 48dp に固定すると、大きい文字や狭い窓で2行に折り返せず「別のノートをひ」のように途中で切れる。最小の高さだけを決める。
 */
class ButtonLabelWrapScanTest {

    @Test
    fun `読む面・データ管理・蒸留の文字のボタンは最小の高さだけを決める`() {
        listOf("NoteReaderTab.kt", "DataManagementScreen.kt", "AiTab.kt").forEach { name ->
            val source = File("src/main/java/com/example/newproject/ui/screen/$name").readText()
            assertFalse("$name の文字のボタンが高さを固定している", Regex("""\.(weight\(1f\)|fillMaxWidth\(\))\.height\(48\.dp\)""").containsMatchIn(source))
        }
    }
}
