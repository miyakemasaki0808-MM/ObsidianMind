package com.example.newproject.ui

import com.example.newproject.ui.screen.SEARCH_ALL_SCOPE_NOTE
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** さがすの「すべて」の注記（→ `docs/dev/features/ai_picker.md` 判断1・`docs/dev/system/ui_copy.md` §5）。 */
class SearchScopeNoteTest {

    @Test
    fun `注記は内部の本数を言わない`() {
        assertFalse(SEARCH_ALL_SCOPE_NOTE, SEARCH_ALL_SCOPE_NOTE.any { it.isDigit() })
    }

    @Test
    fun `注記は失敗の色で出さない`() {
        val source = File("src/main/java/com/example/newproject/ui/screen/SearchScreen.kt").readText()
        assertFalse("「すべて」の注記が ErrorText で出ている", Regex("""ScopeNote\(SEARCH_ALL_SCOPE_NOTE[^)]*ErrorText""").containsMatchIn(source))
    }
}
