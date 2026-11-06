package com.example.newproject.architecture

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * コミット前のゲートを、Claude Code のフックが `git commit` の直前に照らすこと（→ docs/dev/harness/README.md §1）。
 *
 * 一時のリポジトリに偽の `gradlew` を置き、本物の `.claude/gate/` のスクリプトをそこで走らせる。
 * 偽の `gradlew` は無視される `build/fail` があると失敗し、`build/touch` があると実行中にコードを書き換える —
 * コードのツリーを変えずに、失敗と実行中の変更を作るため。
 */
class CommitGateHookTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var repo: File

    @Before
    fun setUp() {
        repo = temporary.newFolder("repo")
        git("init", "-q")
        write(".gitignore", "build/\n")
        write("gradlew", FAKE_GRADLEW).setExecutable(true)
        write("app/Main.kt", "fun main() {}\n")
        write("docs/a.md", "# a\n")
        write("README.md", "# r\n")
        git("add", "-A")
        git("commit", "-q", "-m", "init")
    }

    @Test
    fun `ゲートを通していないコードの変更はコミットが止まり理由が返る`() {
        write("app/Main.kt", "fun main() { println() }\n")

        val result = hook("git commit -m x")

        assertEquals(2, result.exit)
        assertTrue(result.err, "ゲートの通過記録が無い" in result.err)
        assertTrue(result.err, ".claude/gate/gate.sh" in result.err)
    }

    @Test
    fun `ゲートを通したコードは、索引へ揃えればコミットできる`() {
        write("app/Main.kt", "fun main() { println() }\n")
        assertGatePasses()

        val unstaged = hook("git commit -m x")
        assertEquals("ステージしていないコードがあるのに通した", 2, unstaged.exit)
        assertTrue(unstaged.err, "索引" in unstaged.err)

        git("add", "-A")
        assertEquals(0, hook("git commit -m x").exit)
    }

    @Test
    fun `作業ファイルと違う内容をステージしていると、記録が無くても有っても止まる`() {
        // 壊れた内容をステージし、作業ファイルだけ HEAD へ戻す。作業ツリーだけを見ると変更が無い。
        write("app/Main.kt", "THIS DOES NOT COMPILE\n")
        git("add", "app/Main.kt")
        write("app/Main.kt", "fun main() {}\n")

        val noRecord = hook("git commit -m x")
        assertEquals(2, noRecord.exit)
        assertTrue(noRecord.err, "索引" in noRecord.err)

        // 正常な作業ファイルでゲートを通しても、別の内容の索引は通さない。
        write("app/Main.kt", "fun main() { println() }\n")
        assertGatePasses()
        write("app/Main.kt", "THIS DOES NOT COMPILE\n")
        git("add", "app/Main.kt")
        write("app/Main.kt", "fun main() { println() }\n")
        assertEquals(2, hook("git commit -m x").exit)

        git("add", "app/Main.kt")
        assertEquals("ゲートを通した内容へ揃えたのに止めた", 0, hook("git commit -m x").exit)
    }

    @Test
    fun `ゲートの後にコードを変えると止まる`() {
        write("app/Main.kt", "fun main() { println() }\n")
        assertGatePasses()
        write("app/Main.kt", "fun main() { println(1) }\n")

        val result = hook("git commit -m x")

        assertEquals(2, result.exit)
        assertTrue(result.err, "ゲートを通した後にコードが変わっている" in result.err)
    }

    @Test
    fun `ゲートが失敗すると前の記録も消えて止まる`() {
        write("app/Main.kt", "fun main() { println() }\n")
        assertGatePasses()
        write("build/fail", "")

        assertTrue(gate().exit != 0)
        assertFalse("失敗したのに通過の記録が残っている", recordFile().exists())
        assertEquals(2, hook("git commit -m x").exit)
    }

    @Test
    fun `ゲートの実行中にコードが変わると記録しない`() {
        write("app/Main.kt", "fun main() { println() }\n")
        write("build/touch", "")

        assertTrue(gate().exit != 0)
        assertFalse("実行中に変わったコードを通過として記録している", recordFile().exists())
        assertEquals(2, hook("git commit -m x").exit)
    }

    @Test
    fun `ゲートとコミットを1つのコマンドにつないでも止まる`() {
        write("app/Main.kt", "fun main() { println() }\n")

        assertEquals(2, hook("./gradlew testDebugUnitTest lintDebug; git commit -m x").exit)
        assertEquals(2, hook(".claude/gate/gate.sh && git add -A && git commit -m x").exit)
        assertEquals(2, hook("cd app && git -C .. commit --amend --no-edit").exit)
        assertEquals(2, hook("git add -A\ngit commit -q -F - <<'EOF'\nx\nEOF").exit)
    }

    @Test
    fun `文書だけの変更はゲートなしで止まらない`() {
        write("docs/a.md", "# a2\n")
        write("docs/new.md", "# new\n")
        write("README.md", "# r2\n")
        write(".claude/skills/x/SKILL.md", "---\nname: x\n---\n")

        assertEquals(0, hook("git commit -m x").exit)
    }

    @Test
    fun `ゲートの後に文書を足しても記録は無効にならない`() {
        write("app/Main.kt", "fun main() { println() }\n")
        assertGatePasses()
        git("add", "-A")
        write("docs/a.md", "# a2\n")
        write("CLAUDE.md", "# c\n")

        assertEquals("ステージしていない文書で止めた", 0, hook("git commit -m x").exit)
    }

    @Test
    fun `未追跡のコードのファイルも数え、無視されるファイルは数えない`() {
        write("build/out.txt", "x")
        assertEquals("無視されるファイルだけなら止まらない", 0, hook("git commit -m x").exit)

        write("app/New.kt", "class New\n")
        assertEquals(2, hook("git commit -m x").exit)

        write("app/docs/nested.md", "# 最上位の docs ではない\n")
        assertGatePasses()
        write("app/docs/nested.md", "# 変えた\n")
        assertEquals("最上位以外の .md はコードとして数える", 2, hook("git commit -m x").exit)
    }

    @Test
    fun `コミット以外のコマンドは止めない`() {
        write("app/Main.kt", "fun main() { println() }\n")

        assertEquals(0, hook("git status").exit)
        assertEquals(0, hook("git log --grep commit").exit)
        assertEquals(0, hook("git commit-tree HEAD^{tree}").exit)
        assertEquals(0, hook("./gradlew testDebugUnitTest").exit)
    }

    @Test
    fun `フックは Bash の PreToolUse に登録され、スクリプトは実行できる`() {
        val root = repositoryRoot()
        val settings = JSONObject(root.resolve(".claude/settings.json").readText())
        val entries = settings.getJSONObject("hooks").getJSONArray("PreToolUse")
        val commands = (0 until entries.length())
            .map { entries.getJSONObject(it) }
            .filter { it.optString("matcher") == "Bash" }
            .flatMap { entry ->
                val hooks = entry.getJSONArray("hooks")
                (0 until hooks.length()).map { hooks.getJSONObject(it).getString("command") }
            }

        assertTrue("PreToolUse(Bash) にゲートのフックが無い: $commands", commands.any { HOOK_PATH in it })
        listOf(HOOK_PATH, GATE_PATH).forEach {
            assertTrue("$it を実行できない", root.resolve(it).canExecute())
        }
    }

    @Test
    fun `CLAUDE_md のゲートの手順はゲートのスクリプトを流す`() {
        val constitution = repositoryRoot().resolve("CLAUDE.md").readText()

        assertTrue(
            "CLAUDE.md のゲートの手順が $GATE_PATH を流していない",
            "```bash\n$GATE_PATH\n```" in constitution
        )
    }

    private fun gate(): Result = run(listOf(repositoryRoot().resolve(GATE_PATH).path))

    private fun assertGatePasses() {
        val result = gate()
        assertEquals("ゲートが通らない: ${result.err}", 0, result.exit)
    }

    private fun hook(command: String): Result {
        val input = JSONObject()
            .put("tool_name", "Bash")
            .put("tool_input", JSONObject().put("command", command))
            .put("cwd", repo.path)
            .toString()
        return run(listOf(repositoryRoot().resolve(HOOK_PATH).path), stdin = input)
    }

    private fun recordFile(): File = File(git("rev-parse", "--path-format=absolute", "--git-path", "vigilith-gate-pass").trim())

    private fun git(vararg args: String): String {
        val result = run(listOf("git") + args)
        assertEquals("git ${args.joinToString(" ")}: ${result.err}", 0, result.exit)
        return result.out
    }

    private fun write(path: String, text: String): File =
        repo.resolve(path).also {
            it.parentFile?.mkdirs()
            it.writeText(text)
        }

    private fun run(command: List<String>, stdin: String? = null): Result {
        val err = temporary.newFile()
        val builder = ProcessBuilder(command).directory(repo).redirectError(err)
        builder.environment().apply {
            keys.filter { it.startsWith("GIT_") || it == "CLAUDE_PROJECT_DIR" }.forEach { remove(it) }
            // 端末と同じ UTF-8 で流す。macOS の bash 3.2 は UTF-8 のとき `$after）` の全角文字を変数名の続きと読む。
            put("LC_ALL", "C.UTF-8")
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_CONFIG_GLOBAL", "/dev/null")
            put("GIT_AUTHOR_NAME", "test")
            put("GIT_AUTHOR_EMAIL", "test@example.com")
            put("GIT_COMMITTER_NAME", "test")
            put("GIT_COMMITTER_EMAIL", "test@example.com")
        }
        val process = builder.start()
        process.outputStream.use { stream -> stdin?.let { stream.write(it.toByteArray()) } }
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "${command.first()} が終わらない" }
        return Result(process.exitValue(), out, err.readText())
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" })
        return sequenceOf(workingDirectory, workingDirectory.parentFile)
            .filterNotNull()
            .firstOrNull { it.resolve(".claude/gate").isDirectory }
            ?: error("リポジトリルートが見つかりません: $workingDirectory")
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private companion object {
        const val HOOK_PATH = ".claude/gate/pre_commit_hook.sh"
        const val GATE_PATH = ".claude/gate/gate.sh"

        const val FAKE_GRADLEW =
            "#!/bin/sh\n[ -f build/fail ] && exit 1\n[ -f build/touch ] && echo '// touched' >> app/Main.kt\nexit 0\n"
    }
}
