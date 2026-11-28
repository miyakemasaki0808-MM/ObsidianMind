package com.example.newproject.ui

import com.example.newproject.model.state.NoteState
import com.example.newproject.ui.component.DRAW_NOTE_LABEL
import com.example.newproject.ui.component.SELECT_VAULT_LABEL
import com.example.newproject.ui.component.emptyPlaceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 最初の1枚を引く前の場の案内の文（→ `docs/dev/features/note_typesetting.md` §5.7・判断11）。 */
class EmptyPlaceTextTest {

    @Test
    fun `状態ごとに文は1つで、読み込み中とノートを出している間は出さない`() {
        assertEquals("「$SELECT_VAULT_LABEL」から、読むノートのフォルダを選びます", emptyPlaceText(NoteState.Idle, vaultSelected = false))
        assertEquals("「$DRAW_NOTE_LABEL」で、今日の1枚を引きます", emptyPlaceText(NoteState.Idle, vaultSelected = true))
        assertEquals("このVaultにMarkdownノートが見つかりませんでした", emptyPlaceText(NoteState.Empty, vaultSelected = true))
        assertEquals("Vaultを読み込めませんでした", emptyPlaceText(NoteState.Error("失敗"), vaultSelected = true))
        assertNull(emptyPlaceText(NoteState.Loading, vaultSelected = true))
        assertNull(emptyPlaceText(NoteState.Success("題", "本文"), vaultSelected = true))
    }

    /**
     * 文が `「」` で指す名前は、画面のボタンの文字と同じ定数から作る。**ボタンがその定数を文字として描いていること**を、
     * 描く呼び出しの形で確かめる（名前が書いてあるだけでは、ボタンが別の文字を描いても通る → lessons L55）。
     */
    @Test
    fun `案内が指すボタンの名前は、ボタンが描く文字の定数である`() {
        val source = listOf("src/main/java", "app/src/main/java").map { File(it, "com/example/newproject/ui/screen/NoteReaderTab.kt") }
            .first { it.isFile }.readText()
        assertTrue("「Vaultを選択」のボタンが定数を描いていない", "Text(SELECT_VAULT_LABEL, color = OnButtonPrimary)" in source)
        assertTrue("「別のノートをひらく」のボタンが定数を描いていない", Regex("""Text\(\s*DRAW_NOTE_LABEL,""").containsMatchIn(source))
        assertTrue("古い名前「ランダム表示」が残っている", "ランダム表示」" !in source)
    }
}
