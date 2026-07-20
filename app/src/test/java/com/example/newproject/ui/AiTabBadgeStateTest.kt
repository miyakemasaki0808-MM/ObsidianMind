package com.example.newproject.ui

import com.example.newproject.AnnotationState
import org.junit.Assert.assertEquals
import org.junit.Test

// AIタブバッジは補記メモのみを対象とする
// （Q&Aは読書画面の吹き出しへ移動し、AIタブから開けなくなったため）。
class AiTabBadgeStateTest {

    @Test
    fun `未確認エラーを最優先で表示する`() {
        val badge = resolveAiTabBadgeState(
            annotationState = AnnotationState.Error("補記エラー", "対象ノート")
        )

        assertEquals(AiTabBadgeState.Error, badge)
    }

    @Test
    fun `生成中は生成中バッジを表示する`() {
        val badge = resolveAiTabBadgeState(
            annotationState = AnnotationState.Loading("対象ノート")
        )

        assertEquals(AiTabBadgeState.Loading, badge)
    }

    @Test
    fun `確認済みエラーなら通常表示へ戻る`() {
        val badge = resolveAiTabBadgeState(
            annotationState = AnnotationState.Error(
                message = "補記エラー",
                sourceTitle = "対象ノート",
                isViewed = true
            )
        )

        assertEquals(AiTabBadgeState.None, badge)
    }

    // Success バッジは AnnotationState.Success が android.net.Uri を要求するため
    // JVMユニットテストでは検証しない（Uriスタブは実行時例外になる）。実機確認で担保する。
}
