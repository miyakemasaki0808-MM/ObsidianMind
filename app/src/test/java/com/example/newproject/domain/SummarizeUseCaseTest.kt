package com.example.newproject.domain

import com.example.newproject.ai.AiAvailability
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.fakes.passDwell
import com.example.newproject.fakes.InMemorySummaryCache
import com.example.newproject.model.NoteExcerptLimits
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 要約の保存と、生成失敗の文言を固定する（→ `docs/dev/features/note_summary.md` 判断6〜判断9）。
 *
 * 見ているのは3つ — **鍵がAIへ渡した完成プロンプトであること**、**保存してよい結果**、
 * **保存済みを引いてよい状態**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummarizeUseCaseTest {

    @Test
    fun `同じノートを開き直すと生成し直さず同じ要約を出す`() = runTest {
        val ai = FakeAiClient.returning("要約結果")
        val useCase = useCase(ai)

        val first = useCase.summarize("ノートA", "Aの本文", passDwell)
        val second = useCase.summarize("ノートA", "Aの本文", passDwell)

        assertEquals(SummaryResult.Success("要約結果"), first)
        assertEquals(SummaryResult.Success("要約結果"), second)
        assertEquals(1, ai.generateCalls)
    }

    /**
     * **鍵が部品の組ではなく完成したプロンプトであること。** 指示文・省略の注記・予算による切り詰めは
     * すべてプロンプトに載るので、この等式が保たれている限り、どれを変えても保存済みは当たらなくなる。
     */
    @Test
    fun `保存の鍵はAIへ渡したプロンプトそのもの`() = runTest {
        val ai = FakeAiClient.returning("要約結果")
        val cache = InMemorySummaryCache()

        useCase(ai, cache).summarize("ノートA", "Aの本文", passDwell)

        assertEquals(listOf(ai.lastPrompt), cache.entries.keys.toList())
    }

    @Test
    fun `タイトルが変わると生成し直す`() = runTest {
        val ai = FakeAiClient.returning("要約結果")
        val useCase = useCase(ai)

        useCase.summarize("ノートA", "同じ本文", passDwell)
        useCase.summarize("ノートB", "同じ本文", passDwell)

        assertEquals(2, ai.generateCalls)
    }

    @Test
    fun `抜粋に載る本文が変わると生成し直す`() = runTest {
        val ai = FakeAiClient.returning("要約結果")
        val useCase = useCase(ai)

        useCase.summarize("ノートA", "Aの本文", passDwell)
        useCase.summarize("ノートA", "Aの本文を書き直した", passDwell)

        assertEquals(2, ai.generateCalls)
    }

    /** AIへ渡るものが変わっていないので、生成し直さないのが正しい。 */
    @Test
    fun `抜粋に載らない真ん中だけを直しても生成し直さない`() = runTest {
        val filler = "同じ文を繰り返す段落。".repeat(300)
        val before = "冒頭の段落。\n\n$filler 中央の一文。 $filler\n\n末尾の段落。"
        val after = "冒頭の段落。\n\n$filler 中央を書き換えた一文。 $filler\n\n末尾の段落。"
        val beforeExcerpt = buildNoteExcerpt(before, NoteExcerptLimits.SUMMARY)
        val afterExcerpt = buildNoteExcerpt(after, NoteExcerptLimits.SUMMARY)
        assertEquals(
            "前提: 2つの本文の抜粋が同じであること（抜粋の作り方が変わったら、この入力から見直す）",
            beforeExcerpt.text to beforeExcerpt.isAbridged,
            afterExcerpt.text to afterExcerpt.isAbridged
        )
        val ai = FakeAiClient.returning("要約結果")
        val useCase = useCase(ai)

        useCase.summarize("ノートA", before, passDwell)
        useCase.summarize("ノートA", after, passDwell)

        assertEquals(1, ai.generateCalls)
    }

    @Test
    fun `失敗した要約は保存せず、次に開くと生成し直す`() = runTest {
        var attempts = 0
        val ai = FakeAiClient {
            attempts++
            if (attempts == 1) throw IllegalStateException("生成に失敗") else "要約結果"
        }
        val cache = InMemorySummaryCache()
        val useCase = useCase(ai, cache)

        assertTrue(useCase.summarize("ノートA", "Aの本文", passDwell) is SummaryResult.Error)
        assertTrue(cache.entries.isEmpty())
        assertEquals(SummaryResult.Success("要約結果"), useCase.summarize("ノートA", "Aの本文", passDwell))
        assertEquals(2, ai.generateCalls)
    }

    @Test
    fun `空の要約は保存しない`() = runTest {
        val ai = FakeAiClient.returning("  \n")
        val cache = InMemorySummaryCache()
        val useCase = useCase(ai, cache)

        useCase.summarize("ノートA", "Aの本文", passDwell)
        useCase.summarize("ノートA", "Aの本文", passDwell)

        assertTrue(cache.entries.isEmpty())
        assertEquals(2, ai.generateCalls)
    }

    /** 生成は決定的なので、保存済みは生成し直した結果と同じ。生成できない状態でも出してよい。 */
    @Test
    fun `DL中や一時的に使えないときも保存済みの要約を出す`() = runTest {
        val ai = FakeAiClient.returning("要約結果")
        val useCase = useCase(ai)
        useCase.summarize("ノートA", "Aの本文", passDwell)

        ai.availability = AiAvailability.Downloading
        assertEquals(SummaryResult.Success("要約結果"), useCase.summarize("ノートA", "Aの本文", passDwell))

        ai.availability = AiAvailability.TemporarilyUnavailable(IllegalStateException("構成の取得待ち"))
        assertEquals(SummaryResult.Success("要約結果"), useCase.summarize("ノートA", "Aの本文", passDwell))

        assertEquals(1, ai.generateCalls)
        assertEquals("DL中に download() を呼ばないこと", 0, ai.downloadCalls)
    }

    @Test
    fun `保存済みが無ければ一時的に使えないときは黙って諦める`() = runTest {
        val ai = FakeAiClient.returning(
            "要約結果",
            availability = AiAvailability.TemporarilyUnavailable(IllegalStateException("構成の取得待ち"))
        )

        assertEquals(SummaryResult.AiUnavailable, useCase(ai).summarize("ノートA", "Aの本文", passDwell))
        assertEquals(0, ai.generateCalls)
    }

    /**
     * **非対応では抜粋を作らず、未取得ではDLの契機を変えない。** どちらも保存済みを引く前に返す。
     * 未取得のときはDL完了後の再開で引くので、ここで引く必要が無い。
     */
    @Test
    fun `非対応とモデル未取得では保存済みを引かない`() = runTest {
        val ai = FakeAiClient.returning("要約結果")
        val cache = InMemorySummaryCache()
        val useCase = useCase(ai, cache)
        useCase.summarize("ノートA", "Aの本文", passDwell)
        val findsBefore = cache.findCalls

        ai.availability = AiAvailability.Unsupported
        assertEquals(SummaryResult.AiUnavailable, useCase.summarize("ノートA", "Aの本文", passDwell))
        ai.availability = AiAvailability.NeedsDownload
        assertEquals(SummaryResult.AiNeedsDownload, useCase.summarize("ノートA", "Aの本文", passDwell))

        assertEquals(findsBefore, cache.findCalls)
    }

    @Test
    fun `AICoreに回数制限で断られたらSDKの英文ではなく開き直しを促す`() = runTest {
        val ai = FakeAiClient.failingGeneration {
            GenAiException(
                RuntimeException("Request cannot be processed. Either your app is out of usage quota"),
                GenAiException.ErrorCode.BUSY
            )
        }

        val result = useCase(ai).summarize("ノートA", "Aの本文", passDwell)

        assertEquals(SummaryResult.Error(SummarizeUseCase.BUSY_MESSAGE), result)
    }

    @Test
    fun `回数制限以外の失敗は例外の文言のまま出す`() = runTest {
        val error = GenAiException(
            RuntimeException("別の失敗"),
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR
        )
        val ai = FakeAiClient.failingGeneration { error }

        val result = useCase(ai).summarize("ノートA", "Aの本文", passDwell)

        assertEquals(SummaryResult.Error(requireNotNull(error.message)), result)
    }

    private fun TestScope.useCase(
        ai: FakeAiClient,
        cache: InMemorySummaryCache = InMemorySummaryCache()
    ) = SummarizeUseCase(ai, cache, excerptDispatcher = StandardTestDispatcher(testScheduler))
}
