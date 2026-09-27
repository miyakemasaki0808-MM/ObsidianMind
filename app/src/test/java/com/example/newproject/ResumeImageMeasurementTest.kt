package com.example.newproject

import com.example.newproject.controller.ReadingTraceController
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.NoteImageFailure
import com.example.newproject.ui.markdown.NoteImageContent
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.ui.markdown.NoteImageMeasurement
import com.example.newproject.ui.markdown.NoteImageMeasurements
import com.example.newproject.ui.markdown.firstUnmeasuredImageIndex
import com.example.newproject.ui.markdown.imageBlockRefs
import com.example.newproject.ui.markdown.imagesSkippedBy
import com.example.newproject.ui.markdown.measureRequestedSkips
import com.example.newproject.ui.markdown.measureSkippedImages
import com.example.newproject.ui.shouldReportReadingProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 続きから読むで画像を飛び越したときの進捗（→ features/reunion_card.md 判断6「続きから読む」）。
 *
 * 画像は描画されたときにしか測られない。未測定の画像より後ろの報告は止める契約なので
 * （→ features/note_image_rendering.md 判断5）、飛び越した画像を測らないと、
 * 再開した後に読んだところが痕跡へ残らない。**止める検査は緩めず、飛び越した画像を測る**ことで解く。
 *
 * ここは可視位置を注入して状態遷移を通す。描画と送りの配線は `NoteReadingFlowTest` が見る。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumeImageMeasurementTest {

    @Test
    fun `飛び越す画像のうち、未測定のものを1つずつ選ぶ`() {
        val a = MarkdownBlock.Image(alt = "", target = "a.png", isEmbed = false)
        val b = MarkdownBlock.Image(alt = "", target = "b.png", isEmbed = false)
        val c = MarkdownBlock.Image(alt = "", target = "c.png", isEmbed = false)
        val blocks = listOf(MarkdownBlock.Paragraph("段落"), a, b, a, MarkdownBlock.Paragraph("段落"), c)

        assertEquals(listOf(a), imagesSkippedBy(blocks, target = 5, measuredReferences = setOf("b.png")))
    }

    /** 画像（第20ブロック）を飛び越して第88ブロックへ送り、末尾まで読むと 100% が残る。 */
    @Test
    fun `画像を飛び越して再開し、末尾まで読むと最後まで読んだと残る`() = runTest {
        val env = Env(this)
        env.open()
        env.read(0..7)

        // 送った先。測る前は、未測定の画像より後ろなので報告しない。
        env.read(88..95)
        measureSkippedImages(env.blocks, target = 88, loader = MeasuredLoader, measurements = env.measurements)
        env.read(88..99)

        assertEquals(100, env.leave())
    }

    /** 送った直後の1画面だけでは末尾まで読んだことにしない。 */
    @Test
    fun `送った直後の画面だけなら、そこまでしか残らない`() = runTest {
        val env = Env(this)
        env.open()
        measureSkippedImages(env.blocks, target = 88, loader = MeasuredLoader, measurements = env.measurements)
        env.read(88..95)

        assertEquals(96, env.leave())
    }

    /** 飛ばずに先頭から読むときは、未測定の画像より後ろを引き続き報告しない（仮の高さによる水増しを防ぐ）。 */
    @Test
    fun `飛ばないときは、未測定の画像より後ろを報告しない`() = runTest {
        val env = Env(this)
        env.open()
        env.read(0..99)

        assertEquals(20, env.leave())
    }

    /**
     * **測定待ちの間に画面が破棄されても、依頼は残り、移った先の画面が測り終える**（修正レビューで固定した受理条件）。
     *
     * 通常画面のスコープで測っていたころは、全画面へ移っただけで測定がキャンセルされ、
     * 飛び越した画像は画面外なので二度と測られず、報告が止まり続けた。
     */
    @Test
    fun `測定待ちで画面が破棄されても、移った先の画面が測り終える`() = runTest {
        val env = Env(this)
        env.open()
        env.measurements.requestSkippedMeasurement(88)
        val loader = GatedLoader()

        // 通常画面が測り始め、待っている間に破棄される。
        val tab = launch { measureRequestedSkips(env.blocks, loader, env.measurements) }
        runCurrent()
        tab.cancel()
        loader.open()
        runCurrent()
        env.read(88..95)
        assertEquals("破棄された測定が記録されている", setOf<String>(), env.measurements.measuredReferences())

        // 全画面が同じ依頼を拾う。
        launch { measureRequestedSkips(env.blocks, loader, env.measurements) }
        advanceUntilIdle()
        env.read(88..99)

        assertEquals(100, env.leave())
    }

    /** 依頼はノート単位の入れ物にある。**別のノートの入れ物へは持ち越さない。** */
    @Test
    fun `別のノートの入れ物には依頼を持ち越さない`() = runTest {
        val env = Env(this)
        env.measurements.requestSkippedMeasurement(88)
        val next = NoteImageMeasurements()
        val loader = GatedLoader().apply { open() }

        measureRequestedSkips(env.blocks, loader, next)

        assertNull(next.skipTarget)
        assertEquals(0, loader.calls)
    }

    @Test
    fun `依頼は一番先の位置を残す`() {
        val measurements = NoteImageMeasurements()
        measurements.requestSkippedMeasurement(88)
        measurements.requestSkippedMeasurement(40)

        assertEquals(88, measurements.skipTarget)
    }

    private class Env(private val scope: TestScope) {
        val blocks = buildNoteSectionModel(IMAGE_BODY).blocks
        val measurements = NoteImageMeasurements()
        private val refs = imageBlockRefs(blocks)
        private val clock = TestClock()
        private val persistence = FakePersistence()
        private val visits = ReadingTraceController(
            persistScope = scope,
            persistence = persistence,
            currentVaultKey = { FakeVault().key },
            clock = clock::now,
            ioDispatcher = StandardTestDispatcher(scope.testScheduler)
        )

        fun open() {
            visits.onNoteOpened(TRACE_PATH, "画像のあるノート", "doc-1")
        }

        /** 画面の最終可視ブロックとして順に報告する。**本番と同じ判定を通す。** */
        fun read(range: IntRange) {
            range.forEach { index ->
                val firstUnmeasured = firstUnmeasuredImageIndex(refs, measurements.measuredReferences())
                if (shouldReportReadingProgress(index, firstUnmeasured)) {
                    visits.onReadingProgress(index, 1f, blocks.size, null)
                }
            }
        }

        /** 10秒以上いてから離れ、保存された最後の訪問の到達率を返す。 */
        fun leave(): Int {
            clock.advance(10_000L)
            visits.flush()
            scope.advanceUntilIdle()
            return persistence.stored(TRACE_PATH)!!.visits.last().progressPercent
        }
    }

    /** 開けるまで測定を返さないローダ。 */
    private class GatedLoader : NoteImageLoader {
        private val gate = CompletableDeferred<Unit>()
        var calls = 0
            private set

        fun open() {
            gate.complete(Unit)
        }

        override suspend fun measure(image: MarkdownBlock.Image): NoteImageMeasurement {
            calls++
            gate.await()
            return NoteImageMeasurement.Measured(width = 800, height = 2_400)
        }

        override suspend fun load(image: MarkdownBlock.Image, targetWidthPx: Int): NoteImageContent =
            NoteImageContent.Failed(NoteImageFailure.Broken)
    }

    private object MeasuredLoader : NoteImageLoader {
        override suspend fun measure(image: MarkdownBlock.Image): NoteImageMeasurement =
            NoteImageMeasurement.Measured(width = 800, height = 2_400)

        override suspend fun load(image: MarkdownBlock.Image, targetWidthPx: Int): NoteImageContent =
            NoteImageContent.Failed(NoteImageFailure.Broken)
    }

    private companion object {
        const val TRACE_PATH = "images/long.md"

        /** 100ブロック。第20ブロックだけが画像。 */
        val IMAGE_BODY = (0 until 100).joinToString("\n\n") { index ->
            if (index == 20) "![](assets/tall.png)" else "段落${index}の本文。"
        }
    }
}
