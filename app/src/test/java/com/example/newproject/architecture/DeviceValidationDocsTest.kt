package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codex実機検証の入口と、機能別ケースの最低限の形を固定する。 */
class DeviceValidationDocsTest {

    @Test
    fun `共通手順は権限準備検証後処理記録を持つ`() {
        val text = validationDir().resolve("README.md").readText()
        val required = listOf(
            "## 権限範囲",
            "## 実機検証前",
            "## 実機検証中",
            "## 実機検証後",
            "## 記録",
            "途中で1操作ずつ確認を取り直さず"
        )

        val missing = required.filterNot(text::contains)
        assertTrue("実機検証の共通手順に必須項目がありません: ${missing.joinToString()}", missing.isEmpty())
    }

    @Test
    fun `機能別ケースは正本と前後処理を持つ`() {
        val expected = setOf(
            "reflect_distill.md",
            "background_ai_ux.md",
            "note_image_rendering.md"
        )
        val actual = validationDir().listFiles { file -> file.extension == "md" && file.name != "README.md" }
            .orEmpty()
            .associateBy { it.name }
        val missingFiles = expected - actual.keys
        val requiredHeadings = listOf("## 正本", "## 適用条件", "## 検証前", "## ケース", "## 後処理", "## 記録")
        val malformed = actual.values.mapNotNull { file ->
            val text = file.readText()
            val missing = requiredHeadings.filterNot(text::contains)
            when {
                missing.isNotEmpty() -> "${file.name}: ${missing.joinToString()}"
                "../../dev/" !in text -> "${file.name}: devの正本へのリンクが無い"
                else -> null
            }
        }

        assertTrue("不足している機能別ケース: ${missingFiles.sorted().joinToString()}", missingFiles.isEmpty())
        assertTrue("機能別ケースの形が不完全です:\n${malformed.joinToString("\n")}", malformed.isEmpty())
    }

    @Test
    fun `Codex実機検証の入口が旧運用へ戻っていない`() {
        val root = repositoryRoot()
        val claude = root.resolve("CLAUDE.md").readText()
        val reviewReadme = root.resolve("docs/review/README.md").readText()
        val documentMap = root.resolve("docs/dev/document_map.md").readText()

        assertTrue("CLAUDE.mdから実機手順へ到達できません", "docs/review/device_validation/README.md" in claude)
        assertFalse("旧『ユーザーがAndroid Studioで実施』へ戻っています", "実機確認はユーザーがAndroid Studioで実施する" in claude)
        assertTrue("reviewの入口から実機手順へ到達できません", "device_validation/" in reviewReadme)
        assertTrue("文書地図から実機手順へ到達できません", "review/device_validation/" in documentMap)
    }

    private fun validationDir(): File = repositoryRoot().resolve("docs/review/device_validation").also {
        assertTrue("docs/review/device_validation がありません", it.isDirectory)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(workingDirectory.resolve(".."), workingDirectory)
        return candidates.firstOrNull { it.resolve("CLAUDE.md").isFile }
            ?: error("リポジトリルートが見つかりません（作業ディレクトリ: $workingDirectory）")
    }
}
