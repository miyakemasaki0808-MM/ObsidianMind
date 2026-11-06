package com.example.newproject.architecture

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Codex にレビューを頼むスクリプトが、往復の上限と停止の条件を機械的に守ること（→ docs/dev/harness/README.md §3）。
 *
 * 一時のリポジトリで本物のスクリプトを走らせ、`codex` は偽物に差し替える。偽物は受け取ったプロンプトを控え、
 * 置かれた本文を返す。`fail`・`touch`・`leave` の印があると、失敗・追跡ファイルの変更・未追跡の残し物を作る。
 */
class CodexReviewScriptTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var repo: File
    private lateinit var fake: File

    @Before
    fun setUp() {
        repo = temporary.newFolder("repo")
        fake = temporary.newFolder("fake")
        fake.resolve("codex").apply {
            writeText(FAKE_CODEX)
            setExecutable(true)
        }
        git("init", "-q")
        write("app/Main.kt", "fun main() {}\n")
        write("docs/review/README.md", "# review\n")
        git("add", "-A")
        git("commit", "-q", "-m", "first")
        write("app/Main.kt", "fun main() { println() }\n")
        git("commit", "-q", "-am", "second")
    }

    @Test
    fun `本文を docs_review へ置き、重さを数えて回数を進める`() {
        body("### P2-1. 何か\n### P3-1. 小さい\n")

        val result = review()

        assertEquals(result.err, 0, result.exit)
        val bodies = reviewBodies()
        assertEquals(listOf("s-r1.md"), bodies.map { it.name.substringAfter("-").substringAfter("-").substringAfter("-") })
        assertTrue("### P2-1." in bodies.single().readText())
        assertTrue(result.out, "P1 0件・P2 1件・P3 1件" in result.out)
        assertTrue(result.out, "残り 2 回" in result.out)
        val prompt = prompt()
        assertTrue(prompt, "`HEAD~1..HEAD`" in prompt)
        assertTrue(prompt, "追跡しているファイル（製品コード・テスト・文書）を変更しない" in prompt)
    }

    @Test
    fun `観点のファイルをプロンプトに入れる`() {
        body("### P3-1. 小さい\n")
        val focus = temporary.newFile("focus.md").apply { writeText("- 観点その1: 取り消しの後着\n") }

        assertEquals(0, review(focus.path).exit)
        assertTrue(prompt(), "観点その1: 取り消しの後着" in prompt())
    }

    @Test
    fun `同じシリーズは3回で必ず断る`() {
        body("### P2-1. 残る\n")
        repeat(3) {
            assertEquals(0, review().exit)
            intake()
        }
        fake.resolve("prompt.md").delete()

        val fourth = review()

        assertEquals(3, fourth.exit)
        assertTrue(fourth.err, "上限の 3 回に達した" in fourth.err)
        assertFalse("上限の後に Codex を呼んでいる", fake.resolve("prompt.md").exists())
    }

    @Test
    fun `3回目で P1_P2 が残ったら直さずに渡すと返す`() {
        body("### P1-1. 残る\n")
        repeat(2) {
            assertEquals(0, review().exit)
            intake()
        }

        val third = review()

        assertEquals(0, third.exit)
        assertTrue(third.out, "受け付けたら直さずにオーナーへ渡す" in third.out)
    }

    @Test
    fun `P1_P2 が0件ならシリーズを閉じて次を断る`() {
        body("### P3-1. 小さい\n")
        val first = review()
        assertTrue(first.out, "P1・P2 は0件" in first.out)
        intake()

        val second = review()

        assertEquals(3, second.exit)
        assertTrue(second.err, "閉じている" in second.err)
    }

    @Test
    fun `前の本文が残っていれば断る`() {
        body("### P2-1. 残る\n")
        assertEquals(0, review().exit)

        val second = review()

        assertEquals(3, second.exit)
        assertTrue(second.err, "前の本文が残っている" in second.err)
    }

    @Test
    fun `未コミットの変更があれば断る`() {
        body("### P2-1. 残る\n")
        write("app/Main.kt", "fun main() { println(1) }\n")

        val result = review()

        assertEquals(3, result.exit)
        assertTrue(result.err, "未コミットの変更" in result.err)
    }

    @Test
    fun `Codex が追跡しているファイルを変えたら失敗で止まる`() {
        body("### P2-1. 残る\n")
        fake.resolve("touch").createNewFile()

        val result = review()

        assertEquals(5, result.exit)
        assertTrue(result.err, "app/Main.kt" in result.err)
    }

    @Test
    fun `Codex が失敗したら回数に数えず本文を残さない`() {
        body("### P2-1. 残る\n")
        fake.resolve("fail").createNewFile()

        val failed = review()

        assertEquals(2, failed.exit)
        assertTrue(reviewBodies().isEmpty())
        fake.resolve("fail").delete()
        assertEquals(0, review().exit)
        assertTrue(reviewBodies().single().name.endsWith("-s-r1.md"))
    }

    @Test
    fun `2回目は前回の HEAD からの修正と前回の受付を判定させる`() {
        body("### P2-1. 残る\n")
        assertEquals(0, review().exit)
        val firstHead = git("rev-parse", "--short", "HEAD").trim()
        intake()
        write("app/Main.kt", "fun main() { println(2) }\n")
        git("commit", "-q", "-am", "fix")

        assertEquals(0, review().exit)

        val head = git("rev-parse", "--short", "HEAD").trim()
        assertTrue(prompt(), "`$firstHead..$head`" in prompt())
        assertTrue(prompt(), "`s-r1/`" in prompt())
    }

    @Test
    fun `Codex が残した未追跡のファイルを知らせる`() {
        body("### P3-1. 小さい\n")
        fake.resolve("leave").createNewFile()

        val result = review()

        assertEquals(0, result.exit)
        assertTrue(result.err, "app/Tmp.kt" in result.err)
    }

    private fun review(vararg extra: String): Result =
        run(listOf(repositoryRoot().resolve(SCRIPT_PATH).path, "s", "HEAD~1..HEAD") + extra)

    /** 受付を済ませて本文を消したことにする。 */
    private fun intake() = reviewBodies().forEach { it.delete() }

    private fun body(text: String) = fake.resolve("body.md").writeText("# 総評\n\n$text")

    private fun prompt(): String = fake.resolve("prompt.md").readText()

    private fun reviewBodies(): List<File> =
        repo.resolve("docs/review").listFiles { file -> file.name.first().isDigit() }?.toList().orEmpty()

    private fun git(vararg args: String): String {
        val result = run(listOf("git") + args)
        assertEquals("git ${args.joinToString(" ")}: ${result.err}", 0, result.exit)
        return result.out
    }

    private fun write(path: String, text: String) {
        repo.resolve(path).apply {
            parentFile?.mkdirs()
            writeText(text)
        }
    }

    private fun run(command: List<String>): Result {
        val err = temporary.newFile()
        val builder = ProcessBuilder(command).directory(repo).redirectError(err)
        builder.environment().apply {
            keys.filter { it.startsWith("GIT_") }.forEach { remove(it) }
            put("PATH", fake.path + File.pathSeparator + getValue("PATH"))
            put("FAKE_DIR", fake.path)
            put("TMPDIR", temporary.root.path)
            // 端末と同じ UTF-8 で流す。macOS の bash 3.2 は UTF-8 のとき全角文字に隣り合う変数名を読み違える。
            put("LC_ALL", "C.UTF-8")
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_CONFIG_GLOBAL", "/dev/null")
            put("GIT_AUTHOR_NAME", "test")
            put("GIT_AUTHOR_EMAIL", "test@example.com")
            put("GIT_COMMITTER_NAME", "test")
            put("GIT_COMMITTER_EMAIL", "test@example.com")
        }
        val process = builder.start()
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "${command.first()} が終わらない" }
        return Result(process.exitValue(), out, err.readText())
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" })
        return sequenceOf(workingDirectory, workingDirectory.parentFile)
            .filterNotNull()
            .firstOrNull { it.resolve(".claude/review").isDirectory }
            ?: error("リポジトリルートが見つかりません: $workingDirectory")
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private companion object {
        const val SCRIPT_PATH = ".claude/review/codex_review.sh"

        val FAKE_CODEX = """
            #!/bin/bash
            out=""; prev=""
            for a in "${'$'}@"; do [ "${'$'}prev" = "-o" ] && out="${'$'}a"; prev="${'$'}a"; done
            cat > "${'$'}FAKE_DIR/prompt.md"
            [ -f "${'$'}FAKE_DIR/fail" ] && exit 1
            [ -f "${'$'}FAKE_DIR/touch" ] && echo '// x' >> app/Main.kt
            [ -f "${'$'}FAKE_DIR/leave" ] && echo 'class Tmp' > app/Tmp.kt
            cp "${'$'}FAKE_DIR/body.md" "${'$'}out"
            echo "tokens used"
            echo "1,234"
        """.trimIndent() + "\n"
    }
}
