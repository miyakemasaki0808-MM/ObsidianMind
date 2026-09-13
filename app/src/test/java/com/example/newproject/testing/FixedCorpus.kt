package com.example.newproject.testing

import java.io.File

/**
 * 固定コーパス（`app/src/androidTest/assets/ai_corpus`）をJVMテストから読む口。
 *
 * **実機テストと同じ1つを、JVMからはファイルとして読む。** 置き場を変えるときはここだけを直す
 * （3本のテストがそれぞれ場所を解決していると、1本だけ古い場所を読み続けても気づけない）。
 */
object FixedCorpus {

    fun root(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return sequenceOf(workingDirectory, workingDirectory.parentFile)
            .filterNotNull()
            .map { it.resolve("app/src/androidTest/assets/ai_corpus") }
            .firstOrNull { it.isDirectory }
            ?: workingDirectory.resolve("src/androidTest/assets/ai_corpus")
    }

    /** ノートのファイル（`README.md` を除く `.md`）。名前順。 */
    fun noteFiles(): List<File> {
        val notes = root().listFiles()
            ?.filter { it.isFile && it.extension == "md" && it.name != "README.md" }
            ?.sortedBy { it.name }
            .orEmpty()
        check(notes.isNotEmpty()) { "固定コーパスを読めていません: ${root().path}" }
        return notes
    }

    /** `ファイル名（.md 付き）` → 本文。 */
    fun notes(): Map<String, String> = noteFiles().associate { it.name to it.readText() }
}
