package com.example.newproject.ui

import com.example.newproject.ui.vigilith.VigilithDistillPhase
import com.example.newproject.ui.vigilith.VigilithMascotMotion
import com.example.newproject.ui.vigilith.VigilithMode
import com.example.newproject.ui.vigilith.VigilithPresentation
import com.example.newproject.ui.vigilith.vigilithMascotMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VigilithMascotMotionTest {

    @Test
    fun `Idleはレンズとコアだけが低強度で呼吸する`() {
        val start = motion(VigilithMode.Idle, 0f, 1f)
        val peak = motion(VigilithMode.Idle, 0.5f, 1f)

        assertTrue(peak.lensGlowAlpha > start.lensGlowAlpha)
        assertTrue(peak.coreGlowAlpha > start.coreGlowAlpha)
        assertEquals(0f, peak.summaryGuideFraction, 0f)
        assertEquals(0f, peak.wingCloseFraction, 0f)
        assertEquals(0f, peak.candidateGatherFraction, 0f)
        assertEquals(0f, peak.underlineFraction, 0f)
        assertEquals(0f, peak.messengerGlowAlpha, 0f)
    }

    @Test
    fun `Summaryは片翼を広げて結果を案内する`() {
        val start = motion(VigilithMode.Summarizing, 0f, 1f)
        val focus = motion(VigilithMode.Summarizing, 0.5f, 1f)

        assertTrue(focus.summaryGuideFraction > start.summaryGuideFraction)
        assertTrue(focus.lensGlowAlpha > start.lensGlowAlpha)
        assertEquals(0f, focus.wingCloseFraction, 0f)
        assertEquals(0f, focus.candidateGatherFraction, 0f)
        assertEquals(0f, focus.underlineFraction, 0f)
    }

    @Test
    fun `Summarizingのレンズ輝度は常にIdleより強い`() {
        // モード内の変化は上2件が見ているが、モードを跨いだ大小関係は誰も固定していなかった。
        // 「要約中はIdleより明るく光る」は意図的な差（ベース 0.18 vs 0.08）なので、
        // 係数を触った時に崩れたら気付けるよう同一 loopFraction 同士で比較する。
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { loop ->
            val idle = motion(VigilithMode.Idle, loop, 1f)
            val summarizing = motion(VigilithMode.Summarizing, loop, 1f)

            assertTrue(
                "loop=$loop で Summarizing(${summarizing.lensGlowAlpha}) が " +
                    "Idle(${idle.lensGlowAlpha}) を上回らなかった",
                summarizing.lensGlowAlpha > idle.lensGlowAlpha
            )
        }
    }

    @Test
    fun `蒸留の候補探索は断片を中央へ集め両翼を寄せる`() {
        val frame = motion(
            mode = VigilithMode.Distilling,
            loop = 0.5f,
            entrance = 1f,
            phase = VigilithDistillPhase.FindingCandidates
        )

        assertEquals(0.45f, frame.wingCloseFraction, 0.0001f)
        assertEquals(1f, frame.candidateGatherFraction, 0f)
        assertEquals(0f, frame.underlineFraction, 0f)
    }

    @Test
    fun `蒸留候補表示では両翼で一節を保持する`() {
        val frame = motion(
            mode = VigilithMode.Distilling,
            loop = 0.9f,
            entrance = 1f,
            phase = VigilithDistillPhase.HoldingCandidate
        )

        assertEquals(1f, frame.wingCloseFraction, 0f)
        assertEquals(1f, frame.candidateGatherFraction, 0f)
        assertEquals(0f, frame.underlineFraction, 0f)
    }

    @Test
    fun `蒸留保存では翼を止めて下線を一度だけ伸ばす`() {
        val halfway = motion(
            mode = VigilithMode.Distilling,
            loop = 0.3f,
            entrance = 0.5f,
            phase = VigilithDistillPhase.Underlining
        )
        val end = motion(
            mode = VigilithMode.Distilling,
            loop = 0.8f,
            entrance = 1f,
            phase = VigilithDistillPhase.Underlining
        )

        assertEquals(1f, halfway.wingCloseFraction, 0f)
        assertEquals(0.5f, halfway.underlineFraction, 0f)
        assertEquals(1f, end.underlineFraction, 0f)
    }

    @Test
    fun `Messengerは下から着地する`() {
        val start = motion(VigilithMode.Messenger, 0f, 0f)
        val end = motion(VigilithMode.Messenger, 0f, 1f)

        assertEquals(1f, start.bodyLiftFraction, 0f)
        assertEquals(0f, end.bodyLiftFraction, 0f)
    }

    @Test
    fun `Messengerのカプセルは登場中に一度だけ光って静止する`() {
        val before = motion(VigilithMode.Messenger, 0f, 0f)
        val flash = motion(VigilithMode.Messenger, 0f, 0.32f)
        val settled = motion(VigilithMode.Messenger, 0f, 1f)

        assertEquals(0f, before.messengerGlowAlpha, 0f)
        assertEquals(1f, flash.messengerGlowAlpha, 0f)
        assertEquals(0f, settled.messengerGlowAlpha, 0f)
    }

    @Test
    fun `入力は0から1へ丸める`() {
        assertEquals(
            motion(
                VigilithMode.Distilling,
                0f,
                1f,
                VigilithDistillPhase.FindingCandidates
            ),
            motion(
                VigilithMode.Distilling,
                -10f,
                10f,
                VigilithDistillPhase.FindingCandidates
            )
        )
    }

    @Test
    fun `全モードの出力は0から1に収まる`() {
        VigilithMode.entries.forEach { mode ->
            listOf(0f, 0.1f, 0.32f, 0.5f, 0.62f, 1f).forEach { fraction ->
                val frame = motion(
                    mode,
                    fraction,
                    fraction,
                    if (mode == VigilithMode.Distilling) {
                        VigilithDistillPhase.Underlining
                    } else {
                        null
                    }
                )
                listOf(
                    frame.bodyLiftFraction,
                    frame.lensGlowAlpha,
                    frame.coreGlowAlpha,
                    frame.summaryGuideFraction,
                    frame.wingCloseFraction,
                    frame.candidateGatherFraction,
                    frame.underlineFraction,
                    frame.messengerGlowAlpha
                ).forEach { value ->
                    assertTrue("$mode produced $value", value in 0f..1f)
                }
            }
        }
    }

    private fun motion(
        mode: VigilithMode,
        loop: Float,
        entrance: Float,
        phase: VigilithDistillPhase? = null
    ): VigilithMascotMotion = vigilithMascotMotion(
        presentation = VigilithPresentation(
            isVisible = true,
            mode = mode,
            distillPhase = phase
        ),
        loopFraction = loop,
        entranceFraction = entrance
    )
}
