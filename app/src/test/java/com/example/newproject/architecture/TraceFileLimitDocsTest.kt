package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **痕跡ファイルの上限を書いた表の行が、コードの定数（`ReadingTraceLimits.MAX_FILE_BYTES`）と一致することを固定する。**
 *
 * 上限は「1つの事実」なのに表が2つの文書にある。上げたときに関連文書だけ直して数値の正本の表を取り残すと、
 * 後の実装やテストのデータが旧い値に寄る（→ [lessons L14](../../../../../../../../docs/dev/lessons.md)）。
 * スキーマ版の検査（[SchemaVersionDocsTest]）は版番号しか見ないので、こちらで上限を見る。
 *
 * **見るのは、`MAX_FILE_BYTES` を名指しする表の行の、値の欄の先頭の KB だけ。**
 * 同じ欄の後ろに書いた履歴（「128KB から上げた」）は見ない。
 * 蒸留と結晶にも同名の定数があるので、痕跡の上限を書く文書（[DOCS_STATING_TRACE_LIMIT]）だけを見る。
 */
class TraceFileLimitDocsTest {

    @Test
    fun `痕跡ファイルの上限を書いた表の行はコードの定数と一致する`() {
        val limitKb = traceFileLimitKb()
        val problems = DOCS_STATING_TRACE_LIMIT.flatMap { path ->
            val file = repositoryRoot().resolve("docs/$path")
            check(file.isFile) { "対象文書が見つかりません: $path" }
            val rows = file.readLines().filter { it.trimStart().startsWith("|") && it.contains("`MAX_FILE_BYTES`") }
            if (rows.isEmpty()) {
                listOf("$path: `MAX_FILE_BYTES` の行が無い（表を消したなら、この検査の対象から外すこと）")
            } else {
                rows.mapNotNull { row ->
                    val value = row.split("|").getOrNull(2)?.let { LEADING_KB.find(it)?.groupValues?.get(1)?.toInt() }
                    when (value) {
                        null -> "$path: 値の欄が KB で始まらない: ${row.trim()}"
                        limitKb -> null
                        else -> "$path: ${value}KB がコードの ${limitKb}KB と食い違う: ${row.trim()}"
                    }
                }
            }
        }

        assertTrue("痕跡ファイルの上限の表がコードと食い違っています:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun traceFileLimitKb(): Int {
        val source = repositoryRoot()
            .resolve("app/src/main/java/com/example/newproject/model/ReadingTrace.kt")
            .readText()
        return requireNotNull(LIMIT_CONSTANT.find(source)) {
            "ReadingTrace.kt に `MAX_FILE_BYTES = N * 1024` の宣言が見つかりません。"
        }.groupValues[1].toInt()
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return sequenceOf(workingDirectory, workingDirectory.parentFile)
            .filterNotNull()
            .firstOrNull { it.resolve("docs/dev").isDirectory }
            ?: error("リポジトリルートが見つかりません: $workingDirectory")
    }

    private companion object {
        val LIMIT_CONSTANT = Regex("""MAX_FILE_BYTES\s*=\s*(\d+)\s*\*\s*1024""")
        val LEADING_KB = Regex("""^\s*\**(\d+)KB""")

        /** 痕跡ファイルの上限を表に書く文書。**上限を上げたら、ここへ挙がっているものは全部直す。** */
        val DOCS_STATING_TRACE_LIMIT = listOf(
            "dev/features/reflect_reading_trace.md",
            "dev/features/reflect_margin_memo.md"
        )
    }
}
