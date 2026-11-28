package com.example.newproject.ui

import com.example.newproject.ui.screen.SEARCH_ALL_SCOPE_NOTE
import com.example.newproject.ui.screen.SEARCH_IDLE_TEXT
import com.example.newproject.ui.screen.SEARCH_KEYWORD_NOTICE
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** さがすの注記と空の場（→ `docs/dev/features/ai_picker.md` 判断1・`docs/dev/system/ui_copy.md` §2・§5）。 */
class SearchCopyTest {

    @Test
    fun `注記は内部の本数を言わない`() {
        assertFalse(SEARCH_ALL_SCOPE_NOTE, SEARCH_ALL_SCOPE_NOTE.any { it.isDigit() })
    }

    @Test
    fun `空の場と断り書きは件数と理由を言わない`() {
        assertFalse(SEARCH_IDLE_TEXT, SEARCH_IDLE_TEXT.any { it.isDigit() })
        assertFalse(SEARCH_KEYWORD_NOTICE, SEARCH_KEYWORD_NOTICE.contains("ため"))
    }

    @Test
    fun `注記は失敗の色で出さない`() {
        val source = File("src/main/java/com/example/newproject/ui/screen/SearchScreen.kt").readText()
        assertFalse("「すべて」の注記が ErrorText で出ている", Regex("""ScopeNote\(SEARCH_ALL_SCOPE_NOTE[^)]*ErrorText""").containsMatchIn(source))
    }
}
