package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退避ファイルの組み立てと解析が、Main上の呼び出し元へ再び直書きされないことを固定する。
 *
 * **「純粋」は「軽い」を意味しない。** 退避ファイルは最大8MB・5,000件で、JSONの組み立て・
 * 整形文字列化・各痕跡のdecode・重複の畳み込みはいずれも入力サイズに比例する。
 * Controller の `scope` は本番では `viewModelScope`（Main）なので、ここを外すと
 * 上限近傍で進捗表示も中止ボタンも止まる（→ `NoteExcerptThreadingTest` と同じ型）。
 *
 * 2026-08-26 のコードレビュー（P2-2）はこれを指摘した。既存の走査が2本あったのに、
 * **退避だけがその外にあった**のが根なので、同じ形の走査をここへ足す。
 */
class ReadingTraceBackupThreadingTest {

    @Test
    fun `退避ファイルのencodeとdecodeはcpuDispatcherの中でだけ呼ぶ`() {
        val source = controllerSource()

        val calls = JSON_CALL.findAll(source).count()
        assertTrue("退避ファイルの組み立て／解析が1つも見つかりません", calls > 0)
        assertEquals(
            "ReadingTraceBackupJson の呼び出しが cpuDispatcher の外へ出ています",
            calls,
            OFF_MAIN_JSON_CALL.findAll(source).count()
        )
    }

    /** 重複の畳み込みも件数に比例するので、同じ扱いにする。 */
    @Test
    fun `退避ファイル内の重複集約もcpuDispatcherの中で行う`() {
        val source = controllerSource()

        assertEquals(
            "重複の畳み込みが cpuDispatcher の外へ出ています",
            DEDUP_CALL.findAll(source).count(),
            OFF_MAIN_DEDUP.findAll(source).count()
        )
    }

    @Test
    fun `本番既定のcpuDispatcherはDefaultである`() {
        assertEquals(
            "本番既定が Dispatchers.Default ではありません",
            1,
            DEFAULT_DISPATCHER.findAll(controllerSource()).count()
        )
    }

    /**
     * **退避形式を触れる場所を1つに保つ。**
     *
     * どこか別の層から直接呼べると、そこがMain上の新しい抜け道になる。
     * 呼んでよいのは実装本体と、上の検査が見ているController だけ。
     */
    @Test
    fun `退避形式を呼ぶ本番コードはControllerだけである`() {
        val callers = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { JSON_CALL.containsMatchIn(it.readText()) }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            "退避形式を直接呼ぶ本番コードが増えています",
            sortedSetOf("ReadingTraceBackupController.kt"),
            callers
        )
    }

    private fun controllerSource(): String =
        mainSourceRoot().resolve("controller/ReadingTraceBackupController.kt").readText()

    private fun mainSourceRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        val candidates = listOf(
            workingDirectory.resolve("src/main/java/com/example/newproject"),
            workingDirectory.resolve("app/src/main/java/com/example/newproject")
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("main source root が見つかりません: $workingDirectory")
    }

    private companion object {
        val JSON_CALL = Regex("""\bReadingTraceBackupJson\.(?:encode|decode)\(""")
        val OFF_MAIN_JSON_CALL =
            Regex("""withContext\(cpuDispatcher\)\s*\{\s*ReadingTraceBackupJson\.(?:encode|decode)\(""")
        val DEDUP_CALL = Regex("""\breduce\(::mergeReadingTraces\)""")
        val OFF_MAIN_DEDUP = Regex(
            """withContext\(cpuDispatcher\)\s*\{[\s\S]{0,400}?reduce\(::mergeReadingTraces\)"""
        )
        val DEFAULT_DISPATCHER =
            Regex("""cpuDispatcher:\s*CoroutineDispatcher\s*=\s*Dispatchers\.Default""")
    }
}
