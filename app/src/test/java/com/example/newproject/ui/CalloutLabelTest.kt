package com.example.newproject.ui

import com.example.newproject.domain.markdown.Callout
import com.example.newproject.domain.markdown.CalloutRole
import com.example.newproject.ui.markdown.CalloutLabel
import com.example.newproject.ui.markdown.calloutLabelOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** コールアウトの頭の記号と言葉（→ docs/dev/system/markdown_rendering.md §8）。 */
class CalloutLabelTest {

    @Test
    fun `役割ごとに日本語の言葉と記号を出す`() {
        assertEquals(
            listOf(
                CalloutLabel("❝", "引用"),
                CalloutLabel("≡", "まとめ"),
                CalloutLabel("？", "問い"),
                CalloutLabel("⚠︎", "注意"),
                CalloutLabel("☞", "例・ヒント")
            ),
            CalloutRole.entries.map { calloutLabelOf(Callout(it.name, it, "")) }
        )
    }

    @Test
    fun `知らない種類は、まとめの記号に書かれたままの種類名を添える`() {
        assertEquals(CalloutLabel("≡", "Custom"), calloutLabelOf(Callout("Custom", null, "題")))
    }

    /** 記号は言葉と同じ色で塗る文字で、絵文字の形を指定しない（U+FE0F を付けない）。 */
    @Test
    fun `記号は絵文字の形を指定しない`() {
        CalloutRole.entries.forEach { role ->
            assertFalse(role.name, '️' in calloutLabelOf(Callout(role.name, role, "")).mark)
        }
    }
}
