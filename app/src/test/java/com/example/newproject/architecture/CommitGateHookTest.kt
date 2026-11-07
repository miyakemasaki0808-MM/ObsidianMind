package com.example.newproject.architecture

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * コミット前のゲートを、Git の pre-commit がコミットされる内容で照らすこと（→ docs/dev/harness/README.md §1）。
 *
 * 一時のリポジトリへ本物の `.claude/gate/` を写して追跡させ、偽の `gradlew` を置いて、本物の `git commit` を流す。
 * 偽の `gradlew` は無視される `build/fail` があると失敗し、`build/touch` があると実行中にコードを書き換える。
 * 照らすのはコマンドの書き方ではないので、別名・関数・`-C`・行継続などの書き方は実際の Bash で流して確かめる。
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
        copyGateScripts()
        git("add", "-A")
        git("commit", "-q", "-m", "init")
        git("config", "core.hooksPath", HOOKS_PATH)
    }

    // --- Git の pre-commit -----------------------------------------------------------

    @Test
    fun `ゲートを通していないコードはコミットできず、理由が返る`() {
        write("app/Main.kt", "fun main() { println() }\n")
        git("add", "-A")

        val result = assertRefused { commit() }
        assertTrue(result.err, "ゲートの通過記録が無い" in result.err)
        assertTrue(result.err, ".claude/gate/gate.sh" in result.err)
    }

    @Test
    fun `ゲートを通したコードはコミットでき、無視されるファイルは数えない`() {
        write("app/Main.kt", "fun main() { println() }\n")
        write("build/out.txt", "x")
        assertGatePasses()
        git("add", "-A")

        assertCommitted { commit() }
    }

    @Test
    fun `ゲートの後にコードを変えてステージすると止まり、ステージしなければ通る`() {
        write("app/Main.kt", "fun main() { println() }\n")
        assertGatePasses()
        git("add", "-A")
        // ステージした内容はゲートが見たとおりなので、作業ファイルだけの変更はコミットに入らない。
        write("app/Main.kt", "fun main() { println(1) }\n")
        assertCommitted { commit() }

        git("add", "-A")
        val result = assertRefused { commit() }
        assertTrue(result.err, "ゲートを通したときの作業ツリーと違う" in result.err)
    }

    @Test
    fun `ゲートが失敗すると前の記録も消えて止まる`() {
        write("app/Main.kt", "fun main() { println() }\n")
        assertGatePasses()
        write("build/fail", "")

        assertTrue(gate().exit != 0)
        assertFalse("失敗したのに通過の記録が残っている", recordFile().exists())
        git("add", "-A")
        assertRefused { commit() }
    }

    @Test
    fun `ゲートの実行中にコードが変わると記録しない`() {
        write("app/Main.kt", "fun main() { println() }\n")
        write("build/touch", "")

        assertTrue(gate().exit != 0)
        assertFalse("実行中に変わったコードを通過として記録している", recordFile().exists())
        git("add", "-A")
        assertRefused { commit() }
    }

    @Test
    fun `ゲートとコミットをつないでも、ゲートの結果どおりに通るか止まる`() {
        write("app/Main.kt", "fun main() { println() }\n")
        write("build/fail", "")
        assertRefused { bash("$GATE_PATH; git add -A; git commit -qm x") }

        repo.resolve("build/fail").delete()
        assertCommitted { bash("$GATE_PATH && git add -A && git commit -qm x") }
    }

    @Test
    fun `文書だけのコミットは、コードの書きかけが残っていてもゲートなしで通る`() {
        write("docs/a.md", "# a2\n")
        write("docs/new.md", "# new\n")
        write("README.md", "# r2\n")
        write(".claude/skills/x/SKILL.md", "---\nname: x\n---\n")
        write("app/Main.kt", "fun main() { println() }\n")
        git("add", "docs", "README.md", ".claude/skills")

        assertCommitted { commit() }
    }

    @Test
    fun `最上位以外の md はコードとして数える`() {
        write("app/docs/nested.md", "# 最上位の docs ではない\n")
        git("add", "-A")

        assertRefused { commit() }
    }

    @Test
    fun `ステージした内容がゲートの見た作業ツリーと違えば、記録が無くても有っても止まる`() {
        // 壊れた内容をステージし、作業ファイルだけ HEAD へ戻す。
        write("app/Main.kt", "THIS DOES NOT COMPILE\n")
        git("add", "app/Main.kt")
        write("app/Main.kt", "fun main() {}\n")
        assertRefused { commit() }

        // 正常な作業ファイルでゲートを通しても、別の内容の索引は通さない。
        write("app/Main.kt", "fun main() { println() }\n")
        assertGatePasses()
        write("app/Main.kt", "THIS DOES NOT COMPILE\n")
        git("add", "app/Main.kt")
        write("app/Main.kt", "fun main() { println() }\n")
        assertRefused { commit() }

        git("add", "app/Main.kt")
        assertCommitted { commit() }
    }

    @Test
    fun `ゲートが見た変更の一部だけをコミットすると止まる`() {
        write("app/Main.kt", "fun main() { println() }\n")
        write("app/New.kt", "class New\n")
        assertGatePasses()

        git("add", "app/Main.kt")
        assertRefused { commit() }
        assertRefused { commit("app/Main.kt") }

        git("add", "-A")
        assertCommitted { commit() }
    }

    @Test
    fun `commit -a の一時の索引も照らす`() {
        write("app/Main.kt", "fun main() { println() }\n")
        assertGatePasses()

        assertCommitted { commit("-a") }

        write("app/Main.kt", "fun main() { println(2) }\n")
        assertRefused { commit("-a") }
    }

    @Test
    fun `Claude Code の外のコミットは止めない`() {
        write("app/Main.kt", "fun main() { println() }\n")
        git("add", "-A")

        assertCommitted { commit(claude = false) }
    }

    @Test
    fun `別の worktree へのコミットは、書き方にかかわらずその worktree の記録と照らす`() {
        val other = temporary.root.resolve("other")
        git("worktree", "add", "-q", "-b", "other", other.path)
        val forms = listOf(
            "git -C ../other commit -qm x",
            "git -C ../other \\\n  commit -qm x",
            "git \\\n  -C ../other commit -qm x",
            "g() { command git -C ../other \"\$@\"; }; g commit -qm x",
            "shopt -s expand_aliases\nalias g='git -C ../other'\ng commit -qm x",
            "git -C ../other -c alias.ci=commit ci -qm x",
            "'git' -C ../other commit -qm x",
            "g\\it -C ../other commit -qm x",
            "\"\$(command -v git)\" -C ../other commit -qm x",
            "git -C \"../oth\"er commit -qm x",
        )

        forms.forEachIndexed { i, command ->
            other.resolve("app/Main.kt").writeText("INVALID KOTLIN $i\n")
            git("-C", other.path, "add", "-A")
            // 呼び出し元でゲートを通しても、相手の記録にはならない。
            write("app/Main.kt", "fun main() { println($i) }\n")
            assertGatePasses()
            assertRefused(other) { bash(command) }

            // 同じ書き方が、相手でゲートを通せば実際にコミットになる（書き方の誤りで落ちたのではない）。
            assertEquals(0, run(listOf(other.resolve(GATE_PATH).path), directory = other).exit)
            assertCommitted(other) { bash(command) }
        }
    }

    @Test
    fun `単一引用符の中のバックスラッシュと改行は、そのままのパスの worktree で照らす`() {
        // 変更の無い other を身代わりに置き、実際の対象は「oth＼改行er」という名前の worktree にする。
        git("worktree", "add", "-q", "-b", "decoy", temporary.root.resolve("other").path)
        val target = temporary.root.resolve("oth\\\ner")
        git("worktree", "add", "-q", "-b", "target", target.path)
        target.resolve("app/Main.kt").writeText("INVALID KOTLIN\n")
        git("-C", target.path, "add", "-A")
        val command = "git -C '../oth\\\ner' commit -qm literal"

        assertRefused(target) { bash(command) }

        assertEquals(0, run(listOf(target.resolve(GATE_PATH).path), directory = target).exit)
        assertCommitted(target) { bash(command) }
    }

    // --- Claude Code の PreToolUse（フックを飛ばす指定と、フックが入っているか） -------------

    @Test
    fun `Git のフックが入っていなければ、コミットのコマンドを止める`() {
        git("config", "--unset", "core.hooksPath")

        val result = guard("git commit -m x")

        assertEquals(2, result.exit)
        assertTrue(result.err, GATE_PATH in result.err)
        assertEquals(0, guard("git status").exit)
    }

    @Test
    fun `フックを飛ばす指定を含むコミットのコマンドを止める`() {
        listOf(
            "git commit --no-verify -m x",
            "git commit --no-veri -m x",
            "git commit -n -m x",
            "git commit -qnm x",
            "git -c core.hooksPath=/dev/null commit -m x",
            "git -c core.HooksPath= commit -m x",
            "git -c alias.ci='commit -n' ci -m x",
        ).forEach { command ->
            val result = guard(command)
            assertEquals(command, 2, result.exit)
            assertTrue("$command: ${result.err}", "フックを飛ばす" in result.err)
        }
        listOf(
            "git commit -m x",
            "git commit -qm x",
            "git commit --amend --no-edit",
            "git commit -F /tmp/message.txt",
            "git log --grep commit",
            "git status",
        ).forEach { command -> assertEquals(command, 0, guard(command).exit) }
    }

    // --- 入れ方と登録 -----------------------------------------------------------------

    @Test
    fun `ゲートは Git のフックを入れ、別の値なら上書きせずに失敗する`() {
        git("config", "--unset", "core.hooksPath")
        assertGatePasses()
        assertEquals(HOOKS_PATH, git("config", "--get", "core.hooksPath").trim())

        git("config", "core.hooksPath", "elsewhere/hooks")
        val result = gate()
        assertTrue(result.exit != 0)
        assertTrue(result.err, "elsewhere/hooks" in result.err)
        assertEquals("elsewhere/hooks", git("config", "--get", "core.hooksPath").trim())
    }

    @Test
    fun `既定の場所に有効なフックがあれば、入れずに失敗して元のフックを残す`() {
        git("config", "--unset", "core.hooksPath")
        val hooks = File(git("rev-parse", "--path-format=absolute", "--git-path", "hooks").trim())
        val owner = hooks.resolve("pre-commit").apply {
            writeText("#!/bin/sh\necho OWNER_CHECK >&2\nexit 1\n")
            setExecutable(true)
        }

        val result = gate()

        assertTrue(result.exit != 0)
        assertTrue(result.err, "pre-commit" in result.err)
        assertEquals("設定を変えた", "", run(listOf("git", "config", "--get", "core.hooksPath")).out.trim())
        write("app/Main.kt", "fun main() { println() }\n")
        git("add", "-A")
        val before = head(repo)
        val manual = commit(claude = false)
        assertTrue("元のフックが走らない: ${manual.err}", manual.exit != 0 && "OWNER_CHECK" in manual.err)
        assertEquals(before, head(repo))

        // pre-commit が無く、ほかの名前のフックだけでも切り替えない。
        owner.delete()
        hooks.resolve("pre-push").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        assertTrue(gate().exit != 0)
        assertEquals("", run(listOf("git", "config", "--get", "core.hooksPath")).out.trim())

        // linked worktree から流しても、共有の既定の場所を見る。
        val other = temporary.root.resolve("other")
        git("worktree", "add", "-q", "-b", "other", other.path)
        val fromOther = run(listOf(other.resolve(GATE_PATH).path), directory = other)
        assertTrue(fromOther.exit != 0)
        assertTrue(fromOther.err, "pre-push" in fromOther.err)
        assertEquals("", run(listOf("git", "config", "--get", "core.hooksPath")).out.trim())
    }

    @Test
    fun `既定の場所がサンプルと実行できないファイルだけなら入れる`() {
        git("config", "--unset", "core.hooksPath")
        val hooks = File(git("rev-parse", "--path-format=absolute", "--git-path", "hooks").trim())
        hooks.mkdirs()
        hooks.resolve("pre-commit.sample").apply {
            writeText("#!/bin/sh\nexit 1\n")
            setExecutable(true)
        }
        hooks.resolve("pre-push").writeText("#!/bin/sh\nexit 1\n")

        assertGatePasses()
        assertEquals(HOOKS_PATH, git("config", "--get", "core.hooksPath").trim())
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

        assertTrue("PreToolUse(Bash) にゲートのフックが無い: $commands", commands.any { GUARD_PATH in it })
        listOf(GUARD_PATH, GATE_PATH, "$HOOKS_PATH/pre-commit").forEach {
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

    // --- 道具 -------------------------------------------------------------------------

    private fun gate(): Result = run(listOf(repo.resolve(GATE_PATH).path))

    private fun assertGatePasses() {
        val result = gate()
        assertEquals("ゲートが通らない: ${result.err}", 0, result.exit)
    }

    /** Claude Code の Bash と同じく `CLAUDECODE=1` で `git commit` を流す。 */
    private fun commit(vararg extra: String, claude: Boolean = true): Result =
        run(listOf("git", "commit", "-q", "-m", "x") + extra, claude = claude)

    private fun bash(command: String): Result = run(listOf("/bin/bash", "-c", command))

    /** [action] がコミットを作り、[worktree] の HEAD が動いたこと。 */
    private fun assertCommitted(worktree: File = repo, action: () -> Result): Result {
        val before = head(worktree)
        val result = action()
        assertEquals("コミットできない: ${result.err}", 0, result.exit)
        assertNotEquals("HEAD が動いていない", before, head(worktree))
        return result
    }

    /** [action] が理由つきで止まり、[worktree] の HEAD が動いていないこと。 */
    private fun assertRefused(worktree: File = repo, action: () -> Result): Result {
        val before = head(worktree)
        val result = action()
        assertNotEquals("未検証のコミットが通った", 0, result.exit)
        assertTrue("止めた理由が返っていない: ${result.err}", "コミット前のゲート" in result.err)
        assertEquals("止めたのに HEAD が動いた", before, head(worktree))
        return result
    }

    private fun head(worktree: File): String =
        run(listOf("git", "-C", worktree.path, "rev-parse", "HEAD")).out.trim()

    private fun guard(command: String): Result {
        val input = JSONObject()
            .put("tool_name", "Bash")
            .put("tool_input", JSONObject().put("command", command))
            .put("cwd", repo.path)
            .toString()
        return run(listOf(repo.resolve(GUARD_PATH).path), stdin = input)
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

    /** 本物の `.claude/gate/` を、実行の可否ごと一時のリポジトリへ写す。 */
    private fun copyGateScripts() {
        val source = repositoryRoot().resolve(".claude/gate")
        source.walkTopDown().filter { it.isFile && it.name != ".DS_Store" }.forEach { file ->
            val target = repo.resolve(".claude/gate").resolve(file.relativeTo(source).path)
            target.parentFile?.mkdirs()
            file.copyTo(target)
            target.setExecutable(file.canExecute())
        }
    }

    private fun run(
        command: List<String>,
        stdin: String? = null,
        directory: File = repo,
        claude: Boolean = true,
    ): Result {
        val err = temporary.newFile()
        val builder = ProcessBuilder(command).directory(directory).redirectError(err)
        builder.environment().apply {
            keys.filter { it.startsWith("GIT_") || it == "CLAUDE_PROJECT_DIR" || it == "CLAUDECODE" }.forEach { remove(it) }
            if (claude) put("CLAUDECODE", "1")
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
        const val GUARD_PATH = ".claude/gate/pre_commit_hook.sh"
        const val GATE_PATH = ".claude/gate/gate.sh"
        const val HOOKS_PATH = ".claude/gate/githooks"

        const val FAKE_GRADLEW =
            "#!/bin/sh\n[ -f build/fail ] && exit 1\n[ -f build/touch ] && echo '// touched' >> app/Main.kt\nexit 0\n"
    }
}
