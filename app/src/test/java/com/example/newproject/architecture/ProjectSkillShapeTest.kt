package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作業手順を置く Skill（`.claude/skills/<名前>/SKILL.md`）が読み込める形で、名指しが実在すること
 * （→ docs/dev/harness/README.md §2）。
 *
 * Claude Code は `name` と `description` で Skill を選ぶので、どちらかが欠けると手順があっても呼ばれない。
 * CLAUDE.md が名指す Skill が無ければ、手順を探した人が無い置き場を探す。
 */
class ProjectSkillShapeTest {

    @Test
    fun `各 Skill は name がフォルダ名と同じで description を持つ`() {
        val skills = skillDirectories()
        assertTrue("Skill が1つも無い: ${skillsRoot()}", skills.isNotEmpty())

        val violations = skills.flatMap { directory ->
            val file = directory.resolve("SKILL.md")
            if (!file.isFile) return@flatMap listOf("${directory.name}: SKILL.md が無い")
            val front = frontMatter(file.readText())
                ?: return@flatMap listOf("${directory.name}: 先頭に --- で囲んだ frontmatter が無い")
            buildList {
                if (front["name"] != directory.name) add("${directory.name}: name が「${front["name"]}」")
                if (front["description"].isNullOrBlank()) add("${directory.name}: description が無い")
            }
        }

        assertEquals("Skill の形が崩れている", emptyList<String>(), violations)
    }

    @Test
    fun `Skill の中の相対リンクは実在する`() {
        val violations = skillDirectories().flatMap { directory ->
            val file = directory.resolve("SKILL.md")
            LINK.findAll(file.readText())
                .map { it.groupValues[1].substringBefore('#') }
                .filter { it.isNotEmpty() && !it.startsWith("http") }
                .filterNot { file.parentFile?.resolve(it)?.exists() == true }
                .map { "${directory.name}: $it" }
                .toList()
        }

        assertEquals("Skill のリンク先が無い", emptyList<String>(), violations)
    }

    @Test
    fun `CLAUDE_md とハーネスの正本が名指す Skill は実在する`() {
        val root = repositoryRoot()
        val existing = skillDirectories().map { it.name }.toSet()
        val referrers = listOf(root.resolve("CLAUDE.md"), root.resolve("docs/dev/harness/README.md"))

        val named = referrers.flatMap { file -> NAMED_SKILL.findAll(file.readText()).map { file.name to it.groupValues[1] } }
        assertTrue("CLAUDE.md が Skill を1つも名指していない", named.any { it.first == "CLAUDE.md" })

        val missing = named.filterNot { it.second in existing }.map { "${it.first}: ${it.second}" }
        assertEquals("名指した Skill が無い（実在: ${existing.sorted()}）", emptyList<String>(), missing)
    }

    private fun frontMatter(text: String): Map<String, String>? {
        val lines = text.lines()
        if (lines.firstOrNull() != "---") return null
        val end = lines.drop(1).indexOf("---").takeIf { it >= 0 } ?: return null
        return lines.subList(1, end + 1)
            .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 } }
            .associate { (key, value) -> key.trim() to value.trim() }
    }

    private fun skillDirectories(): List<File> =
        skillsRoot().listFiles { file -> file.isDirectory }?.sortedBy { it.name }.orEmpty()

    private fun skillsRoot(): File = repositoryRoot().resolve(".claude/skills")

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" })
        return sequenceOf(workingDirectory, workingDirectory.parentFile)
            .filterNotNull()
            .firstOrNull { it.resolve(".claude/skills").isDirectory }
            ?: error("リポジトリルートが見つかりません: $workingDirectory")
    }

    private companion object {
        val LINK = Regex("""\]\(([^)\s]+)\)""")
        val NAMED_SKILL = Regex("""Skill `([a-z0-9-]+)`""")
    }
}
