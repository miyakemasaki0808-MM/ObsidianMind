package com.example.newproject

import com.example.newproject.model.ReadingTraceLimits
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上限どうしの整合を固定する。
 *
 * **保存側と読み込み側で上限が食い違うのが、いちばん気づきにくい壊れ方。**
 * 各フィールドの上限を全部満たして保存したファイルが、
 * ファイル全体の読み込み上限を超えて**次回読めなくなる**。
 * 保存は成功し、テストも緑で、実機で「痕跡が消えた」としてだけ現れる。
 *
 * 返事の上限を 1536 → 25,600 バイトへ上げたときに実際に危なかったので、
 * 数えるのをやめて計算で固定する。
 */
class ReadingTraceLimitsTest {

    /**
     * 全フィールドを上限まで詰めた痕跡の、おおよそのバイト数。
     *
     * JSON のキー名・区切り・整形の空白ぶんは [JSON_OVERHEAD] でまとめて見込む。
     * 正確な値を求めるのが目的ではなく、**上限を上げたときに気づくこと**が目的。
     */
    private val worstCaseBytes: Int
        get() = with(ReadingTraceLimits) {
            val perVisit = VISIT_NUMERIC_BYTES + MAX_SECTION_TITLE_BYTES
            MAX_RELATIVE_PATH_BYTES +
                MAX_NOTE_TITLE_BYTES +
                MAX_DOCUMENT_ID_BYTES +
                MAX_AI_SUMMARY_BYTES +
                MAX_REMARK_BYTES +
                MAX_REPLY_BYTES +
                MAX_MIRRORED_BYTES +
                perVisit * MAX_VISITS +
                JSON_OVERHEAD
        }

    @Test
    fun `全フィールドを上限まで詰めてもファイル上限に収まる`() {
        assertTrue(
            "上限どうしが食い違っている。保存できたファイルを次回読めなくなる" +
                "（最悪ケース $worstCaseBytes バイト > 上限 ${ReadingTraceLimits.MAX_FILE_BYTES}）。" +
                "どれかの上限を上げたなら MAX_FILE_BYTES も見直すこと。",
            worstCaseBytes <= ReadingTraceLimits.MAX_FILE_BYTES
        )
    }

    /**
     * 返事の上限は、**AIへ渡す抜粋の上限より十分大きい**こと。
     *
     * 2つが同じ値だった頃は、ローカルLLMへ渡せる長さがそのまま
     * ユーザーの文章の上限になっていた。分離したことを固定する。
     */
    @Test
    fun `保存の上限はAI入力の上限より大きい`() {
        assertTrue(
            "保存とAI入力の予算が分かれていない",
            ReadingTraceLimits.MAX_REPLY_BYTES > REPLY_EXCERPT_BYTES_ESTIMATE
        )
    }

    private companion object {
        /** 訪問1件の数値フィールド（日時・到達率）とJSONの器のぶん。 */
        const val VISIT_NUMERIC_BYTES = 96

        /** キー名・引用符・カンマ・整形の空白。フィールド数から見た概算。 */
        const val JSON_OVERHEAD = 4 * 1024

        /** AIへ渡す返事の抜粋（400文字≒1200バイト）の概算。 */
        const val REPLY_EXCERPT_BYTES_ESTIMATE = 1_200
    }
}
