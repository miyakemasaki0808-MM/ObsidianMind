package com.example.newproject.data

import com.example.newproject.domain.SummaryCache
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 要約を1件1ファイルで端末に残す（→ `docs/dev/features/note_summary.md` 判断7）。
 *
 * **[directory] は `noBackupFilesDir` の下に置く。** 中身は本文から作った文章なので、
 * 除外規則を書き忘れても端末の外へ出ない置き場を選んでいる。
 *
 * ファイル名は完成したプロンプトのSHA-256、中身は要約（UTF-8）、**更新時刻が最後に使った時刻**。
 * 当たったら更新時刻だけを書き換え、上限を超えたら最後に使った時刻が古いものから消す。
 */
internal class FileSummaryCache(
    private val directory: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SummaryCache {

    private val writeLock = Mutex()

    override suspend fun find(prompt: String): String? = withContext(ioDispatcher) {
        val file = entryFile(prompt)
        try {
            // 保存側と同じ上限で弾く。大きすぎるファイルは要約ではない（壊れているか、別物が置かれた）
            if (!file.isFile || file.length() > MAX_ENTRY_BYTES) return@withContext null
            val summary = file.readText(Charsets.UTF_8).takeIf { it.isNotEmpty() }
                ?: return@withContext null
            // 書き換えに失敗しても当たりは当たり。古い順が「書いた順」へ劣化するだけで壊れはしない
            file.setLastModified(clock())
            summary
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun save(prompt: String, summary: String) {
        val bytes = summary.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) return
        withContext(ioDispatcher) {
            writeLock.withLock { write(entryFile(prompt), bytes) }
        }
    }

    private fun write(target: File, bytes: ByteArray) {
        val temp = File(directory, target.name + TEMP_SUFFIX)
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            temp.writeBytes(bytes)
            // 途中で落ちても、読み手が書きかけのファイルを要約として読まないようにする
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            target.setLastModified(clock())
            evictOldest()
        } catch (_: IOException) {
            // 保存できなくても要約は出ている。次に開いたとき生成し直すだけ
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun evictOldest() {
        val files = directory.listFiles() ?: return
        // 書き込みは錠で直列なので、ここに残っている一時ファイルは落ちた書き込みの残骸である
        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }
        val entries = files.filter { ENTRY_NAME.matches(it.name) }
        val excess = entries.size - maxEntries
        if (excess <= 0) return
        entries.map { it to it.lastModified() }
            .sortedWith(compareBy<Pair<File, Long>> { it.second }.thenBy { it.first.name })
            .take(excess)
            .forEach { (file, _) -> file.delete() }
    }

    private fun entryFile(prompt: String): File =
        File(directory, sha256Hex(prompt.toByteArray(Charsets.UTF_8)))

    companion object {
        /** `noBackupFilesDir` の下に切る置き場の名前。 */
        const val DIRECTORY_NAME = "summary_cache"

        /** 保存する要約の件数上限（数の根拠は判断7）。上限いっぱいでも数MBに収まる。 */
        const val MAX_ENTRIES = 1000

        /** 1件の上限。要約は256トークン以下なので、これを超えるファイルは要約ではない。 */
        const val MAX_ENTRY_BYTES = 16 * 1024

        private const val TEMP_SUFFIX = ".tmp"
        private val ENTRY_NAME = Regex("[0-9a-f]{64}")
    }
}
