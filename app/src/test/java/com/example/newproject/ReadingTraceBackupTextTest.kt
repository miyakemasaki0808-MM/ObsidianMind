package com.example.newproject

import com.example.newproject.domain.readingTraceBackupFileName
import com.example.newproject.model.ReadingTraceImportPlan
import com.example.newproject.model.ReadingTraceImportWithholdReason
import com.example.newproject.model.WithheldImport
import com.example.newproject.model.state.ReadingTraceBackupState
import com.example.newproject.ui.exportSummary
import com.example.newproject.ui.importPlanSummary
import com.example.newproject.ui.importResultSummary
import com.example.newproject.ui.revisedPlanNotice
import com.example.newproject.ui.unreadableTraceLocation
import com.example.newproject.ui.withheldImportText
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 退避画面の文面。**「自分の言葉が失われるか」に先に答えているか**を見る。 */
class ReadingTraceBackupTextTest {

    private fun plan(
        added: Int = 3,
        merged: Int = 2,
        localReplyReplaced: Int = 0,
        importedReplyDropped: Int = 0
    ) = ReadingTraceImportPlan(
        added = added,
        merged = merged,
        localReplyReplaced = localReplyReplaced,
        importedReplyDropped = importedReplyDropped,
        withheld = emptyList()
    )

    @Test
    fun `端末側の返事が消えるときは件数より先にその事実を言う`() {
        val text = importPlanSummary(plan(localReplyReplaced = 1))

        assertTrue("この端末の返事が置き換わることを言っていない: $text", text.contains("この端末に書いた返事"))
        assertTrue(
            "件数より先に損失を言っていない: $text",
            text.indexOf("返事") < text.indexOf("新しく増える")
        )
    }

    /**
     * **通常の往復（書き出したあとに返事を書き足す）では、失われるのは退避ファイル側。**
     * 方向を取り違えると「あなたの返事が置き換わります」と逆の告知になる。
     */
    @Test
    fun `退避側の返事が使われないときは端末側が消えると言わない`() {
        val text = importPlanSummary(plan(importedReplyDropped = 1))

        assertTrue("退避側が使われないことを言っていない: $text", text.contains("退避ファイル側の返事が使われません"))
        assertTrue(
            "端末側の返事が消えると誤告知している: $text",
            !text.contains("この端末に書いた返事が退避ファイル側の返事に置き換わります")
        )
    }

    @Test
    fun `両方向あるときは両方を言う`() {
        val text = importPlanSummary(plan(localReplyReplaced = 2, importedReplyDropped = 3))

        assertTrue(text.contains("2件のノートで、この端末に書いた返事"))
        assertTrue(text.contains("3件のノートでは"))
    }

    @Test
    fun `失われる返事が無いことも明示する`() {
        val text = importPlanSummary(plan())

        assertTrue(text.contains("失われる返事はありません"))
    }

    // 二度目の確認を求められた利用者が最初に知りたいのは「どこまで進んだか」。
    @Test
    fun `作り直した下見はまだ書いていないことを先に言う`() {
        assertTrue(revisedPlanNotice().contains("まだ1件も書き戻していません"))
    }

    // **「無かった」と読ませない。** 読めなかっただけで、そこには痕跡がある。
    @Test
    fun `端末側を読めなかった保留は不在と言わない`() {
        val text = withheldImportText(
            WithheldImport("ideas/habit.md", ReadingTraceImportWithholdReason.LOCAL_UNREADABLE)
        )

        assertTrue(text.contains("読み取れませんでした"))
        assertTrue("不在として説明している: $text", !text.contains("ありません"))
    }

    // 読めなかった分を隠すと、退避できていないものを「できた」と誤解する。
    @Test
    fun `書き出しの報告は読めなかった件数を含む`() {
        val text = exportSummary(ReadingTraceBackupState.Exported(written = 8, unreadableKeys = listOf("a", "b")))

        assertTrue(text.contains("8件"))
        assertTrue(text.contains("2件"))
    }

    @Test
    fun `読めなかった痕跡はファイル名で指す`() {
        assertEquals("_ReadingTraces/abc123.json", unreadableTraceLocation("abc123"))
    }

    @Test
    fun `中断した読み戻しは適用済みの件数を伴って伝える`() {
        val text = importResultSummary(
            ReadingTraceBackupState.Imported(added = 4, merged = 1, withheld = emptyList(), interrupted = true)
        )

        assertTrue(text.contains("中止"))
        assertTrue(text.contains("4件"))
    }

    @Test
    fun `どのノートか分からない保留はその旨を言う`() {
        val text = withheldImportText(
            WithheldImport(null, ReadingTraceImportWithholdReason.UNREADABLE_ENTRY)
        )

        assertTrue(text.contains("分かりません"))
    }

    /**
     * **既定のファイル名は64文字未満でなければならない。**
     *
     * 痕跡の置き場の索引はフォルダ内の全ファイルの先頭64文字をキーとして解釈し、
     * 64文字に満たない名前は索引に載せない。長い名前へ変えると、退避ファイルを
     * `_ReadingTraces/` へ保存したときに**痕跡として索引に載り、孤児スキャンの
     * 削除候補に出る**（→ reading_trace_backup §11）。
     */
    @Test
    fun `既定のファイル名は痕跡の索引に載らない長さである`() {
        val name = readingTraceBackupFileName(1_755_900_000_000L, ZoneId.of("Asia/Tokyo"))

        assertTrue("退避ファイルが痕跡として索引に載る長さ: $name", name.length < 64)
        assertTrue(name.endsWith(".json"))
    }
}
