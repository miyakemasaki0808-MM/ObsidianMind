package com.example.newproject.domain

import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.AiStatusNotice
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.SectionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 部分要約の4状態の導出（[sectionSummaryStatus]）を固定する。見出しの要約ボタンの記号がこの導出で決まる。
 */
class SectionSummaryStatusTest {

    private fun chat(
        summary: String? = null,
        isSummaryLoading: Boolean = false,
        error: String? = null,
        summaryProblem: SectionChatProblem? = error?.let(SectionChatProblem::GenerationFailed)
    ) = SectionSummary(
        section = SectionRef("設計"),
        requestId = 1L,
        sectionTitle = "設計",
        sectionContext = "本文",
        summary = summary,
        isSummaryLoading = isSummaryLoading,
        summaryProblem = summaryProblem
    )

    private fun aiStatus(action: AiNoticeAction) = SectionChatProblem.AiStatus(
        AiStatusNotice("いま使えません。", action, canTryAgainLater = action != AiNoticeAction.None)
    )

    /**
     * **走っていないのに Working にしない。**
     *
     * 端末AIが使えず要約も無い状態で最後の `else` に落ち、
     * 生成していないのに入口が「AI生成中」を出し続けていた。
     */
    @Test
    fun `端末AIが使えず要約も無いならIdle`() {
        assertEquals(
            SectionSummaryStatus.Idle,
            sectionSummaryStatus(chat(summaryProblem = aiStatus(AiNoticeAction.None)))
        )
        assertEquals(
            SectionSummaryStatus.Idle,
            sectionSummaryStatus(chat(summaryProblem = aiStatus(AiNoticeAction.Retry)))
        )
    }

    /** 状態の説明は失敗ではないので、Error にもしない。 */
    @Test
    fun `端末AIが使えないことをエラーとして扱わない`() {
        assertNotEquals(
            SectionSummaryStatus.Error,
            sectionSummaryStatus(chat(summaryProblem = aiStatus(AiNoticeAction.None)))
        )
    }

    /** 説明が出ていても、生成が走っているならそちらが優先される。 */
    @Test
    fun `説明が出ていても生成中ならWorking`() {
        assertEquals(
            SectionSummaryStatus.Working,
            sectionSummaryStatus(
                chat(isSummaryLoading = true, summaryProblem = aiStatus(AiNoticeAction.Retry))
            )
        )
    }

    @Test
    fun `その節の要約を持っていなければIdle`() {
        assertEquals(SectionSummaryStatus.Idle, sectionSummaryStatus(null))
    }

    @Test
    fun `エラーは生成中や要約済みより優先される`() {
        assertEquals(
            SectionSummaryStatus.Error,
            sectionSummaryStatus(chat(summary = "要約", isSummaryLoading = true, error = "失敗"))
        )
    }

    @Test
    fun `要約生成中はWorking`() {
        assertEquals(SectionSummaryStatus.Working, sectionSummaryStatus(chat(isSummaryLoading = true)))
    }

    @Test
    fun `要約済みで停止していればReady`() {
        assertEquals(SectionSummaryStatus.Ready, sectionSummaryStatus(chat(summary = "要約")))
    }

    @Test
    fun `要求はあるが要約もエラーも無い状態はこれから始まるWorking`() {
        assertEquals(SectionSummaryStatus.Working, sectionSummaryStatus(chat()))
    }

    /** 入口の読み上げ名は「この節を要約」を核にし、撤去した自由な質問を期待させない。 */
    @Test
    fun `要約ボタンの読み上げ名はどの状態でもこの節の要約を指す`() {
        assertEquals("この節を要約", sectionSummaryEntryDescription(SectionSummaryStatus.Idle))
        SectionSummaryStatus.entries.forEach { status ->
            val description = sectionSummaryEntryDescription(status)
            assertTrue("$status: $description", description.startsWith("この節"))
            assertFalse("$status: 質問を期待させない", description.contains("質問"))
        }
    }

    /** 状態は記号で見分けられる。4状態が同じ記号へ潰れると、生成中と完了の区別が消える。 */
    @Test
    fun `要約ボタンの記号は4状態で異なる`() {
        val symbols = SectionSummaryStatus.entries.map(::sectionSummaryEntrySymbol)
        assertEquals(symbols.size, symbols.toSet().size)
        assertEquals("💬", sectionSummaryEntrySymbol(SectionSummaryStatus.Idle))
    }
}
