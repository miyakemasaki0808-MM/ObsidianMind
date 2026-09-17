package com.example.newproject.domain

/**
 * 保存済みの要約（→ `docs/dev/features/note_summary.md` 判断6・判断7）。
 *
 * **鍵は完成したプロンプトそのもの。** タイトル・抜粋・省略の注記・予算による切り詰め・指示文が
 * すべて載っているので、どれを変えても当たらなくなる。部品を並べて版の定数を人が上げる形にしないのは、
 * **上げ忘れると古い要約が出続け、しかも誰も気づけない**ため。
 *
 * **I/O の失敗は投げない。** 無くても要約は生成できるので、読めなければ当たらなかったことにし、
 * 書けなければ黙って諦める。`CancellationException` だけは通す。
 */
interface SummaryCache {

    /** そのプロンプトで保存した要約。無ければ null。 */
    suspend fun find(prompt: String): String?

    /** 要約を保存する。**生成に成功した空でない要約だけを渡す。** */
    suspend fun save(prompt: String, summary: String)
}
