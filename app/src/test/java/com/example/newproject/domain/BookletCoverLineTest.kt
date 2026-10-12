package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扉（冊子の代表文）の受け入れ条件を固定する。
 *
 * **ここが冊子の印象をほぼ決める**ので、除外・決定性・長さ・フォールバックを
 * 設計の受け入れ条件（features/booklet_mode.md §10）と1対1で並べている。
 */
class BookletCoverLineTest {

    @Test
    fun `frontmatter は扉にしない`() {
        val content = """
            ---
            tags: [読書, 設計]
            created: 2026-08-30
            ---
            本文の最初の文である。
        """.trimIndent()

        assertEquals("本文の最初の文である。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `見出しは扉にしない`() {
        val content = """
            # 大見出し
            ## 小見出し
            見出しではない文がここにある。
        """.trimIndent()

        assertEquals("見出しではない文がここにある。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `コードフェンスの中は扉にしない`() {
        val content = """
            ```kotlin
            val answer = 42
            ```
            コードの外の文を選ぶ。
        """.trimIndent()

        assertEquals("コードの外の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    /**
     * **4本で開いたフェンスの中の3本行を閉じと読まない。**
     * 反転だけで判定すると、ここでコードの中身が扉へ出てくる。
     */
    @Test
    fun `長いフェンスの中の短いフェンス行は閉じにならない`() {
        val content = """
            ````
            ```
            コードの中身である。
            ````
            フェンスの外の文を選ぶ。
        """.trimIndent()

        assertEquals("フェンスの外の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `短いフェンスの中の長いフェンス行は閉じになる`() {
        val content = """
            ```
            コードの中身である。
            ````
            フェンスの外の文を選ぶ。
        """.trimIndent()

        assertEquals("フェンスの外の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    /**
     * **閉じ行に情報文字列は書けない。** 記号の後ろを見ないと、コードの中の
     * ```` ```` not-close ```` が閉じになり、続きの本文が扉へ出てくる。
     */
    @Test
    fun `記号の後ろに文字がある行は閉じにならない`() {
        val content = """
            ````
            ```` not-close
            コードの中身である。
            ````
            フェンスの外の文を選ぶ。
        """.trimIndent()

        assertEquals("フェンスの外の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    /** 4空白以上の字下げはフェンスではなく、字下げコードブロックの中身。 */
    @Test
    fun `4空白字下げの記号列はフェンスにしない`() {
        val content = "    ```" + "\n" + "本文の文である。"

        assertEquals("本文の文である。", selectCoverLine(content, "タイトル"))
    }

    /** 3空白までの字下げはフェンスとして扱う。 */
    @Test
    fun `3空白字下げのフェンスは効く`() {
        val content = """
            &nbsp;&nbsp;&nbsp;```
            コードの中身である。
            &nbsp;&nbsp;&nbsp;```
            フェンスの外の文を選ぶ。
        """.trimIndent().replace("&nbsp;", " ")

        assertEquals("フェンスの外の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `チルダのフェンスも落とす`() {
        val content = """
            ~~~
            コードの中身である。
            ~~~
            フェンスの外の文を選ぶ。
        """.trimIndent()

        assertEquals("フェンスの外の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    /** 記号が違えば閉じにならない（`~~~` は ``` を閉じない）。 */
    @Test
    fun `別の記号のフェンス行では閉じない`() {
        val content = """
            ```
            コードの中身である。
            ~~~
            これもコードの続きとみなす。
        """.trimIndent()

        assertEquals("タイトル", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `閉じていないフェンス以降は本文とみなさない`() {
        val content = """
            ```
            val leaked = "これは扉にならない"
            これもコードの続きとみなす。
        """.trimIndent()

        assertEquals("タイトル", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `リンクだけの行は扉にしない`() {
        val content = """
            [[別のノート]]
            [参考](https://example.com/a)
            ![図](image.png)
            リンクではない文を選ぶ。
        """.trimIndent()

        assertEquals("リンクではない文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `リンクを含むだけの文は扉にできる`() {
        val content = "詳しくは [[設計メモ]] に書いた。"

        assertEquals("詳しくは 設計メモ に書いた。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `表の区切り行は扉にしない`() {
        val content = """
            |---|:--|
            区切りの後の文を選ぶ。
        """.trimIndent()

        assertEquals("区切りの後の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `表は見出し行も本文の行も扉にせず表の後の文へ進む`() {
        val content = """
            | ID | タグ | タイトル | 概要 |
            |---|---|---|---|
            | 0210_0001 | #資格 | ビジネス法務 | 試験の記録 |
            | 0210_0002 | #片付け | 小物 | 見直し |

            予定は月の初めに見直す。
        """.trimIndent()

        assertEquals("予定は月の初めに見直す。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `表しか無い索引ノートはタイトルを出す`() {
        val content = """
            | ID | タグ | タイトル |
            |---|---|---|
            | 0210_0001 | #資格 | ビジネス法務 |
        """.trimIndent()

        assertEquals("0210.Future_Event", selectCoverLine(content, "0210.Future_Event"))
    }

    @Test
    fun `頭のラベル行の塊は扉にしない`() {
        val content = """
            # 📘 0500_0001_Peopleware

            ## 🕓 概要
            - 開始日　　：2025/05/23
            - 現在位置　：第2章から
            - ステータス：📖 読書中

            ## 💡 要点メモ
            - 人材活用
        """.trimIndent()

        assertEquals("人材活用", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `強調したラベルの段落も頭の塊として落とす`() {
        val content = """
            # 設計書

            **状態:** 実装済み・稼働中
            **最終検証:** 2026-09-12 / `23dce6b`
            **関連コード:** `NoteViewModel.kt`

            **対象領域:** ノートを開いたときの要約
        """.trimIndent()

        assertEquals("対象領域: ノートを開いたときの要約", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `ラベルの無い日付の後ろにあっても頭の塊として落とす`() {
        val content = """
            2026-03-28
            - 開始日　　：-
            - 現在位置　：-
            - ステータス：✅ 完読

            ストラテジの基礎知識。
        """.trimIndent()

        assertEquals("ストラテジの基礎知識。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `頭のラベル行が2行なら塊にしない`() {
        // 日付は飛ばし、何のノートかを言うラベル行を扉にする。
        val content = """
            **日付:** 2026-04-29
            **プロジェクト:** 気象庁 API を用いた天気概況取得プログラム

            本文の文。
        """.trimIndent()

        assertEquals("プロジェクト: 気象庁 API を用いた天気概況取得プログラム", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `本文の中に並ぶ用語と説明は扉にできる`() {
        val content = """
            ## 概要
            - 科目：科目A テクノロジ系
            - 出題数目安：約3〜5問
            - 難易度：★★★☆☆

            ## 信頼性設計
            - **フォールトトレラント**：障害が起きても継続稼働
            - **フェールセーフ**：障害時に安全な状態へ移行
            - **フェールソフト**：一部機能を落としてでも継続
        """.trimIndent()

        assertEquals("フォールトトレラント：障害が起きても継続稼働", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `空行で離れたラベル行は塊にしない`() {
        val content = """
            ゴール: 試験に合格する。

            書籍名：ピープルウェア

            著者：デマルコ
        """.trimIndent()

        assertEquals("ゴール: 試験に合格する。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `時刻やURLが続く行はラベルの塊にしない`() {
        val times = """
            07:00 起床
            07:30 朝食
            08:00 出発
        """.trimIndent()
        val urls = """
            https://example.com/a
            https://example.com/b
            https://example.com/c
        """.trimIndent()

        assertEquals("07:00 起床", selectCoverLine(times, "タイトル"))
        assertEquals("https://example.com/a", selectCoverLine(urls, "タイトル"))
    }

    @Test
    fun `頭のラベル行しか無いノートはタイトルを出す`() {
        val content = """
            - 開始日　　：
            - 現在位置　：
            - ステータス：
        """.trimIndent()

        assertEquals("読書ノート", selectCoverLine(content, "読書ノート"))
    }

    @Test
    fun `見出し行だけの表ならタイトルを出す`() {
        val content = """
            | 項目 | 値 |
            |---|:--|
        """.trimIndent()

        assertEquals("タイトル", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `次の行が区切りでなければ縦棒を含む行は扉にできる`() {
        val content = """
            A | B を比べて、B を選んだ。
            次の行の文。
        """.trimIndent()

        assertEquals("A B を比べて、B を選んだ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `値が日付だけの行は扉にしない`() {
        val content = """
            開始日　　：2025/05/23
            作成日: 2026-03-28
            - **更新日**: 2026年3月28日（土）
            2026-03-28 10:30
            本文の最初の文である。
        """.trimIndent()

        assertEquals("本文の最初の文である。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `日付に曜日の括弧が付いた行は扉にしない`() {
        val content = """
            締切: 2026-03-28（土）
            2026/03/29 (Sun)
            更新: 2026年3月30日（月曜日）
            契約を整理する。
        """.trimIndent()

        assertEquals("契約を整理する。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `日付に説明の括弧が付いた行は扉にできる`() {
        assertEquals(
            "締切: 2026-03-28（契約更新の判断）",
            selectCoverLine("締切: 2026-03-28（契約更新の判断）\n次の文。", "タイトル")
        )
        assertEquals(
            "2026-03-28（設計を見直した理由）",
            selectCoverLine("2026-03-28（設計を見直した理由）", "タイトル")
        )
    }

    @Test
    fun `値が読めるラベル行は扉にできる`() {
        assertEquals("ゴール: 2026-03-01 までに合格する。", selectCoverLine("ゴール: 2026-03-01 までに合格する。", "タイトル"))
        assertEquals("書籍名：ピープルウェア", selectCoverLine("書籍名：ピープルウェア", "タイトル"))
    }

    @Test
    fun `ナビの行は扉にしない`() {
        val content = """
            🧭 クイックナビ: [[1000.License]] ｜ [[1000_0001.Python3_Basic_Exam]]
            > 🧭 関連ノート: 1000.License
            会社の種類と出資者の責任を整理する。
        """.trimIndent()

        assertEquals("会社の種類と出資者の責任を整理する。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `ほかの絵文字で始まる行は扉にできる`() {
        assertEquals("📌 ゴール: 試験に合格する。", selectCoverLine("📌 ゴール: 試験に合格する。", "タイトル"))
    }

    @Test
    fun `罫線だけの行は扉にしない`() {
        val content = """
            ---
            ***
            罫線の後の文を選ぶ。
        """.trimIndent()

        assertEquals("罫線の後の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `Markdown記法は表示文字列に残らない`() {
        val content = "- **強調**した `コード` と ~~取り消し~~ を含む文。"

        assertEquals("強調した コード と 取り消し を含む文。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `1行に複数の文があれば最初の1文だけを出す`() {
        val content = "最初の文である。次の文は出さない。"

        assertEquals("最初の文である。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `括弧の内側の終止符では切らない`() {
        val content = "問いは「これでいいのか？」だった。"

        assertEquals("問いは「これでいいのか？」だった。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `同じ本文からは何度呼んでも同じ文を返す`() {
        val content = """
            # 見出し
            最初の文である。
            二番目の文である。
        """.trimIndent()

        val results = (1..5).map { selectCoverLine(content, "タイトル") }

        assertEquals(setOf("最初の文である。"), results.toSet())
    }

    /**
     * **上限そのものを固定する。** 他のテストは `BOOKLET_COVER_MAX_CHARS` を期待値に使うので
     * 定数を変えても一緒に動いてしまい、**40という受け入れ条件は誰も見ていなかった**
     * （変異確認で 40→39 が緑のまま通った）。正本は features/booklet_mode.md §10。
     */
    @Test
    fun `扉の上限は全角40字`() {
        assertEquals(40, BOOKLET_COVER_MAX_CHARS)
    }

    @Test
    fun `上限ちょうどは切らない`() {
        val content = "あ".repeat(BOOKLET_COVER_MAX_CHARS)

        val cover = selectCoverLine(content, "タイトル")

        assertEquals(content, cover)
        assertEquals(BOOKLET_COVER_MAX_CHARS, cover.length)
    }

    @Test
    fun `上限を超えたら省略記号つきで上限に収める`() {
        val content = "あ".repeat(BOOKLET_COVER_MAX_CHARS + 10)

        val cover = selectCoverLine(content, "タイトル")

        assertEquals(BOOKLET_COVER_MAX_CHARS, cover.length)
        assertTrue("末尾が省略記号ではない: $cover", cover.endsWith("…"))
    }

    @Test
    fun `絵文字は途中で割らない`() {
        // 上限をまたぐ位置がサロゲートペア（1文字が2 UTF-16 単位）になるよう並べる。
        val content = "あ".repeat(20) + "🌱".repeat(BOOKLET_COVER_MAX_CHARS - 19)

        val cover = selectCoverLine(content, "タイトル")

        assertEquals(BOOKLET_COVER_MAX_CHARS, cover.codePointCount(0, cover.length))
        assertTrue("片割れのサロゲートが残っている: $cover", cover.hasNoLoneSurrogate())
        assertTrue(cover.endsWith("…"))
    }

    /** **絵文字だけの行は文字を含まないので扉にしない。** 記号だけの行と同じ扱い。 */
    @Test
    fun `絵文字だけの行は扉にしない`() {
        val content = """
            🌱🌱🌱
            絵文字の後の文を選ぶ。
        """.trimIndent()

        assertEquals("絵文字の後の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `選べる文が無ければタイトルを出す`() {
        val content = """
            ---
            tags: [空]
            ---
            # 見出しだけ
            [[リンクだけ]]
        """.trimIndent()

        assertEquals("見出しだけのノート", selectCoverLine(content, "見出しだけのノート"))
    }

    @Test
    fun `本文が空でもタイトルを出す`() {
        assertEquals("空のノート", selectCoverLine("", "空のノート"))
    }

    @Test
    fun `フォールバックしたタイトルも上限で切る`() {
        val title = "長".repeat(BOOKLET_COVER_MAX_CHARS + 5)

        val cover = selectCoverLine("", title)

        assertEquals(BOOKLET_COVER_MAX_CHARS, cover.length)
        assertTrue(cover.endsWith("…"))
    }

    /** 片割れだけのサロゲートが残っていないか。**位置ごとに見る**（同じ絵文字が並ぶため）。 */
    private fun String.hasNoLoneSurrogate(): Boolean {
        var index = 0
        while (index < length) {
            val char = this[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                char.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }
}
