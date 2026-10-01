package com.example.newproject.ui

import androidx.compose.runtime.saveable.SaverScope
import com.example.newproject.domain.MarginMemoInput
import com.example.newproject.ui.screen.MarginMemoInputSaver
import org.junit.Assert.assertEquals
import org.junit.Test

// 画面の保存値へ残す一組（→ rememberMarginMemoInput）。**ノートの識別が保存をくぐって残る**ことが照合の前提。
class MarginMemoInputSaverTest {

    private val scope = SaverScope { true }

    @Test
    fun `保存して戻すと一組がそのまま戻る`() {
        listOf(
            MarginMemoInput("content://vault-a/a.md", "書きかけ", submitted = "書きかけ", seenAcceptedCount = 4),
            MarginMemoInput(null)
        ).forEach { input ->
            val saved = with(MarginMemoInputSaver) { scope.save(input) }!!
            assertEquals(input, MarginMemoInputSaver.restore(saved))
        }
    }
}
