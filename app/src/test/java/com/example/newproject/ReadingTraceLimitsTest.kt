package com.example.newproject

import com.example.newproject.data.ReadingTraceJson
import com.example.newproject.model.BesideNote
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.QuoteContext
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.ReunionKind
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
 * ## 計算をやめて実測で固定する
 *
 * かつてここは**生のバイト上限を足し**、JSONの器を定数で見込んでいた。
 * **それは保証になっていなかった** — JSONは `"` と `\` を2バイトへ、
 * 制御文字を `\u00XX` の6バイトへ広げるので、
 * **全欄が上限内なのにファイルの上限を超えたファイルを書ける。**
 *
 * そこで推定値を置くのをやめ、**本物の [ReadingTraceJson.encode] に通して数える。**
 * 欄が増えても計算式を直し忘れられない（→ features/reflect_margin_memo.md 判断11）。
 *
 * **詰めるのはエスケープで膨らむ文字。** 日本語で埋めると3バイト文字が
 * そのまま3バイトで出るので、**最悪ケースを測ったことにならない。**
 */
class ReadingTraceLimitsTest {

    @Test
    fun `全欄を上限まで詰めた痕跡が、実際にencodeしてもファイル上限に収まる`() {
        val encoded = ReadingTraceJson.encode(worstCaseTrace())

        assertTrue(
            "上限どうしが食い違っている。保存できたファイルを次回読めなくなる" +
                "（実測 ${encoded.size} バイト > 上限 ${ReadingTraceLimits.MAX_FILE_BYTES}）。" +
                "どれかの上限を上げたなら MAX_FILE_BYTES か MAX_MEMOS を見直すこと。",
            encoded.size <= ReadingTraceLimits.MAX_FILE_BYTES
        )
    }

    /**
     * **書けたものは読めること。** 上の測定は「書けるか」しか見ておらず、
     * 読み戻しが同じ上限で弾かないことまでは言えない。
     */
    @Test
    fun `上限まで詰めた痕跡は同じ上限で読み戻せる`() {
        val encoded = ReadingTraceJson.encode(worstCaseTrace())

        val decoded = ReadingTraceJson.decode(encoded)

        assertTrue(
            "上限まで詰めた痕跡を読み戻せない: $decoded",
            decoded is ReadingTraceReadResult.Valid
        )
    }

    /**
     * **6倍に膨らむ文字を入れた並べた相手は、検証で弾かれる。** 弾かないと、検証も読み戻しも通るのに
     * 上限を超え、その訪問とメモの保存が断られる（→ features/desk_memory.md §6）。
     * 「検証は通るが容量の保証の外」という値を残さないことを、最悪の痕跡の相手だけを差し替えて確かめる。
     */
    @Test
    fun `6倍に膨らむ文字の並べた相手は検証で弾かれ、上限の外の値を残さない`() {
        val worst = worstCaseTrace()
        val inflatingTitles = worst.copy(visits = worst.visits.map { it.copy(beside = it.beside!!.copy(title = "a" + "\u0001".repeat(ReadingTraceLimits.MAX_NOTE_TITLE_BYTES - 1))) })
        val inflatingPaths = worst.copy(visits = worst.visits.map { it.copy(beside = it.beside!!.copy(vaultRelativePath = "\u0001".repeat(ReadingTraceLimits.MAX_RELATIVE_PATH_BYTES))) })

        listOf(inflatingTitles, inflatingPaths).forEach { trace ->
            try {
                ReadingTraceJson.encode(trace)
                throw AssertionError("6倍に膨らむ相手を検証が通した")
            } catch (expected: IllegalArgumentException) {
                // 検証で弾かれた。
            }
        }
    }

    /**
     * 全欄を上限まで詰めた痕跡。
     *
     * **印とAI要約は同居しうる**（印は枠に出ていた文をそのまま控えるため）ので、
     * どちらも上限まで入れる。
     */
    private fun worstCaseTrace(): ReadingTrace = with(ReadingTraceLimits) {
        ReadingTrace(
            vaultRelativePath = escaping(MAX_RELATIVE_PATH_BYTES),
            noteTitle = escaping(MAX_NOTE_TITLE_BYTES),
            documentId = escaping(MAX_DOCUMENT_ID_BYTES),
            visits = List(MAX_VISITS) { index ->
                ReadingVisit(
                    atEpochMillis = Long.MAX_VALUE - index,
                    deepestSectionTitle = escaping(MAX_SECTION_TITLE_BYTES),
                    progressPercent = 100,
                    // 並べた相手は訪問ごとに1件。全訪問に上限いっぱいの相手を置く（→ features/desk_memory.md §6）。
                    // 相手は改行も含めて制御文字を許さない（検証で弾く）ので、制御文字でない2倍の文字で詰める。
                    beside = BesideNote(
                        escaping(MAX_RELATIVE_PATH_BYTES, ESCAPING_WITHOUT_CONTROL),
                        escaping(MAX_NOTE_TITLE_BYTES, ESCAPING_WITHOUT_CONTROL)
                    )
                )
            },
            aiSummary = escaping(MAX_AI_SUMMARY_BYTES),
            aiSummaryVisitCount = Int.MAX_VALUE,
            aiSummaryKind = ReunionKind.Overview,
            totalVisitCount = Int.MAX_VALUE,
            memos = List(MAX_MEMOS) { index ->
                MarginMemo(
                    text = escaping(MAX_MEMO_BYTES),
                    writtenAtEpochMillis = Long.MAX_VALUE - index,
                    sectionTitle = escaping(MAX_SECTION_TITLE_BYTES),
                    // 控えた位置より引用のほうが大きいので、全件を前後つきの最大の引用にする。
                    passage = MemoPassage.Quoted(
                        text = escaping(MAX_QUOTE_BYTES),
                        context = QuoteContext(escaping(MAX_QUOTE_CONTEXT_BYTES), escaping(MAX_QUOTE_CONTEXT_BYTES)),
                        truncated = true
                    )
                )
            },
            markedAtEpochMillis = Long.MAX_VALUE,
            markedSummary = escaping(MAX_AI_SUMMARY_BYTES),
            markedKind = ReunionKind.Overview
        )
    }

    /**
     * JSON化で**2倍に膨らむ**文字だけで [bytes] バイトを埋める。
     *
     * 引用符・バックスラッシュ・改行はいずれも1バイトの入力が2バイトの出力になる。
     * 入力側で制御文字（改行・タブを除く）を落とす契約があるので、
     * **2倍がこのアプリで到達しうる最悪の膨張率**である。
     */
    private fun escaping(bytes: Int, chars: List<String> = ESCAPING): String =
        generateSequence(0) { it + 1 }.take(bytes).map { chars[it % chars.size] }.joinToString("")

    private companion object {
        private val ESCAPING = listOf("\"", "\\", "\n")

        /** 制御文字を許さない欄（並べた相手）を詰める文字。改行を除いても2倍に膨らむ。 */
        private val ESCAPING_WITHOUT_CONTROL = listOf("\"", "\\")
    }
}
