package com.example.newproject.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 要約カバレッジの計測器そのものを検査する。
 *
 * **ここで見るのは「落としたことを検出できるか」であって、要約の良し悪しではない。**
 * 閾値が良い要約とわざと落とした要約を分離できるかは
 * `SummaryCoverageCalibrationTest` が固定コーパスで確かめる。
 */
class SummaryCoverageTest {

    private val travelNote = """
        # 旅の記録

        ## 出発

        早朝の電車で東京駅まで出て、そこから新幹線に乗り換えた。窓際の席が取れた。

        ## 宿

        港の近くにある小さな宿に泊まった。朝食に焼き魚と味噌汁が出た。

        ## 帰り

        高速バスで戻った。事故の渋滞に巻き込まれて二時間遅れた。
    """.trimIndent()

    @Test
    fun `原文の全セクションに触れた要約は落としたセクションを出さない`() {
        val summary = "早朝の電車で東京駅まで出て新幹線に乗り換えた。" +
            "港の近くの小さな宿に泊まり、朝食に焼き魚と味噌汁が出た。" +
            "帰りは高速バスで、事故の渋滞に巻き込まれて二時間遅れた。"

        val report = measureSummaryCoverage(travelNote, summary)

        assertEquals(emptyList<String>(), report.untouchedSections)
        assertEquals(emptyList<String>(), report.unsupportedSentences)
    }

    @Test
    fun `1つのセクションに触れない要約は、そのセクションを落としたと出す`() {
        val summary = "早朝の電車で東京駅まで出て新幹線に乗り換えた。" +
            "帰りは高速バスで、事故の渋滞に巻き込まれて二時間遅れた。"

        val report = measureSummaryCoverage(travelNote, summary)

        assertEquals(listOf("宿"), report.untouchedSections)
    }

    @Test
    fun `原文に無いことを書いた要約文は裏付けが弱いと出る`() {
        val summary = "早朝の電車で東京駅まで出て新幹線に乗り換えた。" +
            "去年の夏は北海道で自転車を借り、湖のまわりを一周した。"

        val report = measureSummaryCoverage(travelNote, summary)

        assertEquals(
            listOf("去年の夏は北海道で自転車を借り、湖のまわりを一周した。"),
            report.unsupportedSentences
        )
    }

    @Test
    fun `見出しだけで本文を持たない節は、落としたと数えない`() {
        val nested = """
            # 手順

            ## 1. 準備

            ### 1.1 道具をそろえる

            はさみと定規と接着剤を机の上に並べておく。作業中に探すと手が止まる。

            ### 1.2 場所をつくる

            新聞紙を広げてから始める。接着剤が垂れても机を傷めない。
        """.trimIndent()
        val summary = "はさみと定規と接着剤を机の上に並べ、新聞紙を広げてから始める。"

        val report = measureSummaryCoverage(nested, summary)

        val parent = report.sections.single { it.title == "1. 準備" }
        assertFalse("見出しだけの節は測る対象から外れます", parent.measured)
        assertFalse("落としたセクションに親見出しが混ざっています", "1. 準備" in report.untouchedSections)
    }

    @Test
    fun `見出しが1つも無いノートは全体を1セクションとして扱う`() {
        val note = "先週見た写真展のこと。港湾労働者を三十年撮り続けた作品が並んでいた。"
        val report = measureSummaryCoverage(note, "港湾労働者を三十年撮り続けた写真が並ぶ展示だった。")

        assertEquals(listOf(WHOLE_NOTE_TITLE), report.sections.map { it.title })
        assertTrue(report.sections.single().touched)
    }

    @Test
    fun `同じ見出しが2つあるとき、触れていない側だけが落としたと出る`() {
        val note = """
            # 打ち合わせ

            ## メモ

            次回までに見積もりを三社から取り直すことになった。

            ## 決定

            発注は来月の第一週に行う。

            ## メモ

            会場の駐車場は台数が足りないので、電車で行くほうがよい。
        """.trimIndent()
        val summary = "次回までに見積もりを三社から取り直すことになった。発注は来月の第一週に行う。"

        val report = measureSummaryCoverage(note, summary)

        val memos = report.sections.filter { it.title == "メモ" }
        assertEquals(2, memos.size)
        assertTrue("先に現れた「メモ」は触れられています", memos[0].touched)
        assertFalse("後の「メモ」は触れられていません", memos[1].touched)
        assertEquals(listOf("メモ"), report.untouchedSections)
    }

    @Test
    fun `コードフェンスの中身は材料に含めない`() {
        val note = """
            # 同期の手順

            ```bash
            rsync -av --delete --exclude=cache ~/Documents/ /Volumes/Backup/
            ```

            外付けのディスクへ、月末に手で同期している。
        """.trimIndent()

        // 地の文を写した要約は裏付けを得る。
        val fromBody = measureSummaryCoverage(note, "外付けのディスクへ月末に手で同期している。")
        assertEquals(emptyList<String>(), fromBody.unsupportedSentences)

        // コードの中身だけを写した要約は裏付けを得られない（前処理で落としているため）。
        val fromCode = measureSummaryCoverage(
            note,
            "rsync -av --delete --exclude=cache を使って Documents を Backup へ送る。"
        )
        assertEquals(1, fromCode.unsupportedSentences.size)
    }

    @Test
    fun `見出しの無いノートでも frontmatter は材料に含めない`() {
        val note = """
            ---
            tags: [備忘, 検討中]
            updated: 2026-04-18
            ---

            外付けのディスクへ、月末に手で同期している。
        """.trimIndent()

        val report = measureSummaryCoverage(note, "tags には 備忘 と 検討中 が付いていて updated は 2026-04-18 である。")

        assertEquals(1, report.unsupportedSentences.size)
    }

    @Test
    fun `包含率はセクションの長さで割らない`() {
        val longSection = """
            # 記録

            ## 観察

            ${(1..40).joinToString("\n") { "${it}日目は朝から曇りで、風はほとんど無く、気温は前日と変わらなかった。" }}
        """.trimIndent()

        val report = measureSummaryCoverage(longSection, "毎日ほとんど風が無く、気温は前日と変わらなかった。")

        val support = report.sentences.single().support
        assertTrue("長いセクションでも忠実な1文は高く出ます（実際は $support）", support >= 0.6)
    }

    /**
     * **一致が0の節へ割り当てない**（2026-09-13 机上レビュー P2-1）。
     *
     * 「計測対象の節から最大値を必ず選ぶ」形だと、短い節を写しただけの文が
     * 無関係な「買い物」を被覆済みにし、本当に落ちた節が未反映一覧から消えた。
     */
    @Test
    fun `計測対象外の短い節を写した文は、無関係な節を被覆済みにしない`() {
        val note = """
            # メモ

            ## 連絡

            電話する。

            ## 買い物

            週末に市場で野菜と果物を購入する。
        """.trimIndent()

        val report = measureSummaryCoverage(note, "電話する。")

        val shopping = report.sections.single { it.title == "買い物" }
        assertTrue("買い物は計測対象のはずです", shopping.measured)
        assertFalse("根拠の無い節が被覆済みになっています", shopping.touched)
        assertEquals(listOf("買い物"), report.untouchedSections)
        // 語彙上の裏付けは、割り当てが付かなくても残る
        assertEquals(emptyList<String>(), report.unsupportedSentences)
        assertEquals(null, report.sentences.single().assignedSection)
    }

    /**
     * **先頭の区間が計測対象の節になるノートで確かめる。** `# 旅の記録` のような本文の無い見出しが
     * 先頭にあると、同点の先頭が計測対象外になり、ガードが無くても偶然に通ってしまう。
     */
    @Test
    fun `すべての区間と一致しない文は、閾値を0にしてもどの節にも割り当てない`() {
        val note = travelNote.substringAfter("# 旅の記録").trimStart()

        val report = measureSummaryCoverage(note, "去年の夏は北海道で自転車を借りた。", threshold = 0.0)

        assertTrue("先頭の節が計測対象であることが前提です", report.sections.first().measured)
        assertEquals(null, report.sentences.single().assignedSection)
        assertTrue("一致の無い文が節を触れています", report.sections.none { it.touched })
    }

    /**
     * **計測対象外の節も割り当ての候補に入れる。** 候補を計測対象の節だけに絞ると、
     * 短い節を写した文が、語を1つ共有するだけの長い節へ流れて被覆済みにする。
     */
    @Test
    fun `短い節を写した文は、語を共有する長い節へ流れない`() {
        val note = """
            ## 連絡

            市場に電話する。

            ## 買い物

            週末に市場で野菜と果物を購入する。
        """.trimIndent()

        val report = measureSummaryCoverage(note, "市場に電話する。")

        assertFalse("短い節は計測対象外のはずです", report.sections.single { it.title == "連絡" }.measured)
        assertFalse("語を共有するだけの節が被覆済みになっています", report.sections.single { it.title == "買い物" }.touched)
    }

    /**
     * **親の区間はブロックの位置で切る**（2026-09-13 机上レビュー P2-2）。
     *
     * 子の語集合を差し引く形だと、親と子で共有する「冷蔵庫内」「温度」が親から消え、
     * 子を足しただけで本文を持つ親が計測対象から外れた。
     */
    @Test
    fun `子の節を足しても、本文を持つ親は計測対象のまま残る`() {
        val parentOnly = """
            # 冷蔵管理

            冷蔵庫内の温度は四度を維持する。
        """.trimIndent()
        val withChild = """
            # 冷蔵管理

            冷蔵庫内の温度は四度を維持する。

            ## 点検

            冷蔵庫内の温度を毎朝記録して担当者に報告する。
        """.trimIndent()

        val alone = measureSummaryCoverage(parentOnly, "").sections.single { it.title == "冷蔵管理" }
        val nested = measureSummaryCoverage(withChild, "")

        assertTrue("子が無いとき親は計測対象です", alone.measured)
        assertTrue("子を足すと親が計測対象から外れました", nested.sections.single { it.title == "冷蔵管理" }.measured)
        assertEquals(listOf("冷蔵管理", "点検"), nested.untouchedSections)
    }

    @Test
    fun `子の話をした文は子へ割り当てられ、親を触れたことにしない`() {
        val note = """
            # 冷蔵管理

            冷蔵庫内の温度は四度を維持する。

            ## 点検

            冷蔵庫内の温度を毎朝記録して担当者に報告する。
        """.trimIndent()

        val report = measureSummaryCoverage(note, "毎朝記録して担当者に報告する。")

        assertEquals("点検", report.sentences.single().assignedSection)
        assertEquals(listOf("冷蔵管理"), report.untouchedSections)
    }

    /**
     * **見出しの語だけで下限を超えても、本文が無ければ測らない。**
     * 長い親見出しが「落とした」と数えられると、要約が触れようのない節が分母に入る。
     */
    @Test
    fun `見出しが長くても直下に本文が無ければ計測対象にしない`() {
        val note = """
            ## 引越し前日までに済ませる手続きと荷造りの確認事項

            ### 転出届

            引越しの十四日前から役所の窓口で出せる。
        """.trimIndent()

        val report = measureSummaryCoverage(note, "")

        val parent = report.sections.first()
        assertFalse("本文の無い長い見出しが計測対象になっています", parent.measured)
        assertEquals(listOf("転出届"), report.untouchedSections)
    }

    @Test
    fun `裏付けを得られなかった文は、節の語を含んでいても被覆に数えない`() {
        val summary = "港の近くの宿は来年から料金が倍になり、予約は電話だけになるらしい。"

        val report = measureSummaryCoverage(travelNote, summary)

        assertEquals(listOf(summary), report.unsupportedSentences)
        assertTrue("裏付けの無い文が節を触れています", report.sections.none { it.touched })
    }

    /**
     * **裏付けの材料は原文全体から作る**（2026-09-13 机上レビュー P2-3）。
     *
     * 見出し配下だけから作ると、先頭の見出しより前の本文をそのまま写した文が裏付け0になった。
     */
    @Test
    fun `見出し前の本文を写した文は裏付けを得る`() {
        val note = """
            避難場所は市民会館の屋上で、緊急連絡先は防災担当者とする。

            ## 持ち物

            懐中電灯と携帯電話と飲料水を鞄に入れる。
        """.trimIndent()

        val report = measureSummaryCoverage(note, "避難場所は市民会館の屋上で、緊急連絡先は防災担当者とする。")

        assertEquals(1.0, report.sentences.single().support, 0.0)
        assertEquals(emptyList<String>(), report.unsupportedSentences)
        // 見出し前の本文は被覆の対象ではないので、どの節も触れない
        assertEquals(null, report.sentences.single().assignedSection)
        assertEquals(listOf("持ち物"), report.untouchedSections)
    }

    @Test
    fun `要約が空なら全セクションが落としたと出る`() {
        val report = measureSummaryCoverage(travelNote, "   ")

        assertEquals(emptyList<SummarySentenceSupport>(), report.sentences)
        assertEquals(listOf("出発", "宿", "帰り"), report.untouchedSections)
    }
}
