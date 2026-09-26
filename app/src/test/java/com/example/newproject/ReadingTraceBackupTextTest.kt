package com.example.newproject

import com.example.newproject.domain.readingTraceBackupFileName
import com.example.newproject.model.ReadingTraceImportPlan
import com.example.newproject.model.ReadingTraceImportWithholdReason
import com.example.newproject.model.WithheldImport
import com.example.newproject.model.state.ReadingTraceBackupState
import com.example.newproject.ui.exportSummary
import com.example.newproject.ui.backupProtectedDataText
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

    /**
     * **退避が守るものの名前が、いま保護している欄と一致していること。**
     *
     * これが無かったので、v7 で廃止した「ひとことと返事」を書き出すと案内する文が
     * **実機検証まで残った**。文言の検査は補助的に見えるが、
     * ここは**ユーザーが「自分の言葉は守られるのか」を判断する唯一の説明**である。
     */
    @Test
    fun `退避の説明はいま守っているものを名前で挙げる`() {
        val text = backupProtectedDataText()

        listOf("訪問", "要約", "まだ考えたい", "余白メモ").forEach { name ->
            assertTrue("守っているのに名前が出ていない: $name", text.contains(name))
        }
    }

    /**
     * **廃止した欄を「復元できる」と案内しない。**
     *
     * 守られないものを守ると言うのは、守られるものを言い忘れるより悪い
     * （ユーザーが退避を信じて元を消しうる）。
     * **欄を落としたら、その名前をここへ足す。**
     */
    @Test
    fun `退避の説明は廃止した欄を案内しない`() {
        val text = backupProtectedDataText()

        RETIRED_FIELD_NAMES.forEach { name ->
            assertTrue("廃止した欄を書き出せると案内している: $name", !text.contains(name))
        }
    }

    private fun plan(
        added: Int = 3,
        merged: Int = 2,
        withheld: List<WithheldImport> = emptyList()
    ) = ReadingTraceImportPlan(added = added, merged = merged, withheld = withheld)

    private fun overCapacity(path: String) =
        WithheldImport(path, ReadingTraceImportWithholdReason.MEMOS_OVER_CAPACITY)

    private companion object {
        /** schema v7 で読み捨てた欄。**画面の説明へ二度と出さない。** */
        val RETIRED_FIELD_NAMES = listOf("ひとこと", "返事", "映し返し")
    }

    /**
     * **保留は損失ではない。** 「消えません」まで言い切らないと、
     * 上限に当たったユーザーは自分のメモが捨てられたと読む。
     */
    @Test
    fun `上限で保留したノートは件数より先に、どちらも消えないと言う`() {
        val text = importPlanSummary(plan(withheld = listOf(overCapacity("ideas/habit.md"))))

        assertTrue("保留を言っていない: $text", text.contains("そのままにします"))
        assertTrue("消えないと言っていない: $text", text.contains("どちらのメモも消えません"))
        assertTrue(
            "件数より先に保留を言っていない: $text",
            text.indexOf("そのままにします") < text.indexOf("新しく増える")
        )
    }

    /** 保留が無ければ、失われるものは1つも無い。 */
    @Test
    fun `保留が無ければ失われるメモは無いと言う`() {
        val text = importPlanSummary(plan())

        assertTrue("失われないことを言っていない: $text", text.contains("失われるメモはありません"))
    }

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
