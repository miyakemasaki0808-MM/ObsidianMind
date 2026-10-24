package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.ui.screen.BodyPosition
import com.example.newproject.ui.screen.NoteSpread
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * **OS のアニメーションを無効（倍率0）にしたら、見開きの寄せの途中を省く**（→ features/note_spread.md 判断7）。
 * 指で引いている間は途中の位置（＝途中の角度）を描き、指を離したら次のフレームで行き先の見開きへ着いて、その後に途中の位置を出さない。
 * 倍率1では寄せの途中が出て、行き着く見開きは倍率0と同じ。読み上げからの送りも倍率0で途中を出さない。
 *
 * 倍率は端末の設定を切り替える代わりに、Compose の実行コンテキストへ渡す（本番では窓の設定がここへ届く）。
 * 位置はページャの意味情報が持つ横の送りの量（見開きの幅の何倍か）で読む。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SpreadReducedMotionTest {

    private val motion = AdjustableMotion()

    @get:Rule
    val composeRule = createComposeRule(effectContext = motion)

    @Test
    fun 倍率0で次へ速く払うと_引いている間は途中で_離すと次のフレームで次の見開きに着く() {
        motion.factor = 0f
        setSpread()
        val span = span()
        composeRule.mainClock.autoAdvance = false
        swipe(towardNext = true, fast = true)
        assertHeldBetween(0f, span)

        val after = release()
        assertLandsAt(span, after)
    }

    @Test
    fun 倍率0で前へ速く払うと_離すと次のフレームで前の見開きに着く() {
        motion.factor = 0f
        setSpread()
        val span = span()
        sendNext()
        composeRule.waitForIdle()
        assertEquals("前提: 2番目の見開きから始める", span, offset(), 1f)

        composeRule.mainClock.autoAdvance = false
        swipe(towardNext = false, fast = true)
        assertHeldBetween(0f, span)

        val after = release()
        assertLandsAt(0f, after)
    }

    @Test
    fun 倍率0でゆっくり引いて止めてから離すと_次のフレームで元の見開きに戻る() {
        motion.factor = 0f
        setSpread()
        val span = span()
        composeRule.mainClock.autoAdvance = false
        swipe(towardNext = true, fast = false)
        assertHeldBetween(0f, span)

        val after = release()
        assertLandsAt(0f, after)
    }

    /** 倍率1では寄せの途中の位置が出る（通常の寄せを消さない）。同じ払い方なら、行き着く見開きは倍率0と同じ（次の見開き）。 */
    @Test
    fun 倍率1では寄せの途中が出て_行き着く見開きは倍率0と同じ() {
        motion.factor = 1f
        setSpread()
        val span = span()
        composeRule.mainClock.autoAdvance = false
        swipe(towardNext = true, fast = true)
        val held = offset()

        val after = release()
        assertTrue("倍率1で寄せの途中が出ない（離した後 $after）", after.any { it > held + 1f && it < span - 1f })
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("倍率1で行き着いた見開きが倍率0と違う", span, offset(), 1f)
    }

    /** 倍率0の読み上げからの送りも途中を出さず、止まった見開きの位置を一覧へ書き戻して、何番目かを伝える。 */
    @Test
    fun 倍率0で読み上げから送ると_途中を出さずに次の見開きに着き_位置を書き戻す() {
        motion.factor = 0f
        val listState = LazyListState()
        setSpread(listState)
        val span = span()
        composeRule.mainClock.autoAdvance = false
        sendNext()
        val after = (1..FRAMES_AFTER).map {
            composeRule.mainClock.advanceTimeByFrame()
            offset()
        }
        assertLandsAt(span, after)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("2番目の見開き", composeRule.onNode(PAGER).fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        composeRule.runOnIdle { assertTrue("送った見開きの位置を一覧へ書き戻していない", listState.firstVisibleItemIndex > 0) }
    }

    private fun setSpread(listState: LazyListState = LazyListState()) {
        val model = buildNoteSectionModel(BODY)
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = 800.dp, height = 600.dp).testTag(SPREAD_TAG)) {
                    NoteSpread(
                        noteKey = NOTE,
                        title = "見開き",
                        sectionModel = model,
                        position = remember { BodyPosition(listState) },
                        paperColor = Color.White,
                        imageLoader = null,
                        imageMeasurements = null,
                        headingAccessory = null,
                        onLink = null,
                        onReadingProgress = { _, _, _, _ -> },
                        pageWidth = 392.dp,
                        gutter = 16.dp,
                        leftPageTop = null
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * 右ページの中ほどから横へ引き、指を置いたまま止める。速く払うと（8msごとに24dp）寄せは払った向きへ進み、
     * ゆっくり引いて止めると（50msごとに8dp、最後に300ms止める）寄せは近いほうの見開き（ここでは元の見開き）へ戻る。
     */
    private fun swipe(towardNext: Boolean, fast: Boolean) {
        val sign = if (towardNext) -1f else 1f
        composeRule.onNodeWithTag(SPREAD_TAG).performTouchInput {
            down(Offset(centerX + sign * -100.dp.toPx(), centerY))
            if (fast) {
                repeat(10) { moveBy(Offset(sign * 24.dp.toPx(), 0f), delayMillis = 8) }
            } else {
                repeat(12) { moveBy(Offset(sign * 8.dp.toPx(), 0f), delayMillis = 50) }
                moveBy(Offset.Zero, delayMillis = 300)
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    /** 指を離し、その後のフレームごとの位置を返す。 */
    private fun release(): List<Float> {
        composeRule.onNodeWithTag(SPREAD_TAG).performTouchInput { up() }
        return (1..FRAMES_AFTER).map {
            composeRule.mainClock.advanceTimeByFrame()
            offset()
        }
    }

    private fun sendNext() {
        val next = composeRule.onNode(PAGER).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "次の見開きへ" }
        composeRule.runOnUiThread { assertTrue(next.action()) }
    }

    /** 指で引いている間は、2つの見開きの途中にいる（めくる紙が途中の角度で描かれる）。 */
    private fun assertHeldBetween(from: Float, to: Float) {
        val held = offset()
        assertTrue("指で引いている間に途中の位置へ動いていない（$held、${from}〜$to）", held > minOf(from, to) + 1f && held < maxOf(from, to) - 1f)
    }

    /** 離した後、寄せを始める1フレームを含めて2フレームまでに [target] へ着き、その後は動かない。 */
    private fun assertLandsAt(target: Float, after: List<Float>) {
        val landed = after.drop(1)
        assertTrue("倍率0で離した後に途中の位置が出た（$after、行き先 $target）", landed.all { abs(it - target) <= 1f })
    }

    /** ページャの横の送りの量（px）。見開き1つぶんが [span]。 */
    private fun offset(): Float = composeRule.onNode(PAGER).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

    /** 見開き1つぶんの送りの量。ページャは見開きの幅いっぱいに1つずつ並べる。 */
    private fun span(): Float = composeRule.onNode(PAGER).fetchSemanticsNode().size.width.toFloat()

    /** 倍率を試験ごとに替えられる [MotionDurationScale]。組み立てる前に決める。 */
    private class AdjustableMotion : MotionDurationScale {
        var factor = 1f
        override val scaleFactor: Float get() = factor
    }

    private companion object {
        const val SPREAD_TAG = "見開き"
        const val NOTE = "content://vault/spread.md"
        const val FRAMES_AFTER = 6
        val PAGER = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        val BODY = (0 until 80).joinToString("\n\n") { "段落%02d の本文です。読みながら思ったことを、本文の横に短く残す。".format(it) }
    }
}
