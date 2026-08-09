package com.example.newproject.ui

import com.example.newproject.model.Reflection
import com.example.newproject.model.state.RemarkState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AIタブのバッジは「ひとことの生成中」だけを示す。
 *
 * 旧補記は結果が専用画面にあったため未確認管理（`isViewed`）を持ち、
 * 完了✓と失敗!の塗りバッジを出していた。ひとことは結果がAIタブへ直接出るので
 * 未確認という概念が無く、**確認して消すバッジは対象ごと消えた**。
 * 副産物として、下部ナビ帯の上で判別できなかった塗り（Success 1.61 / Error 1.04）も消えた。
 */
class AiTabBadgeStateTest {

    @Test
    fun `生成中は生成中バッジを表示する`() {
        assertEquals(
            AiTabBadgeState.Loading,
            resolveAiTabBadgeState(RemarkState.Loading("対象ノート"))
        )
    }

    @Test
    fun `未生成なら何も出さない`() {
        assertEquals(AiTabBadgeState.None, resolveAiTabBadgeState(RemarkState.Idle))
    }

    // 結果が出てもバッジは出ない。ここが旧補記との一番の違いで、
    // 「確認するまで残るバッジ」を作らないことを固定する。
    @Test
    fun `結果が出てもバッジは残らない`() {
        assertEquals(
            AiTabBadgeState.None,
            resolveAiTabBadgeState(
                RemarkState.Ready(
                    "対象ノート",
                    Reflection("この考えの根拠は何だろう？", remarkedAtEpochMillis = 1L)
                )
            )
        )
        assertEquals(
            AiTabBadgeState.None,
            resolveAiTabBadgeState(RemarkState.Empty("対象ノート"))
        )
        assertEquals(
            AiTabBadgeState.None,
            resolveAiTabBadgeState(RemarkState.Error("失敗", "対象ノート"))
        )
    }
}
