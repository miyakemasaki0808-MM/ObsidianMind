package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * パッケージ依存が許可した向きだけを向いていることをCIで固定する。
 *
 * 「整理した」と「境界が効いている」は別で、レイヤー別にフォルダを分けても
 * 依存の**向き**は何も制約されない（実際、整理後も循環が4組残っていた）。
 * ここが唯一の歯止めなので、**すり抜ける経路を作らない**ことが要点になる。
 */
class PackageDependencyTest {

    /**
     * 値は「その行のパッケージからimportしてよいプロジェクト内パッケージ」。
     * 同一パッケージ内のimportは常に許可し、ここには書かない。
     *
     * [ROOT] はルートパッケージ（`MainActivity` / `NoteViewModel` /
     * `NoteViewModelDependencies`）。**組み立ての場所なので全方向へ依存してよい**が、
     * 逆に**どの層からも参照されてはいけない**（参照を許すと、そこを経由して
     * 任意の循環が作れてしまう）。層として明示しておかないと、ルートへファイルを
     * 移すだけでこのテストを回避できる抜け道になる。
     */
    private val allowedDependencies = mapOf(
        "model" to emptySet(),
        "ai" to setOf("model"),
        "domain" to setOf("model", "ai"),
        "data" to setOf("model", "domain"),
        "controller" to setOf("model", "data", "domain", "ai"),
        "ui" to setOf("model", "domain"),
        ROOT to setOf("model", "data", "domain", "ai", "controller", "ui")
    )

    @Test
    fun `パッケージ依存は許可した方向だけを向く`() {
        val sourceRoot = mainSourceRoot()
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap(::dependencyViolations)
            .sorted()
            .toList()

        assertTrue(
            "許可されていないパッケージ依存があります:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    private fun dependencyViolations(file: File): Sequence<String> {
        val source = file.readText()
        val sourceLayer = PACKAGE_PATTERN.find(source)?.let { layerOf(it.groupValues[1]) }
            ?: return sequenceOf("${file.relativePath()}: package 宣言を読めません")
        val allowed = allowedDependencies[sourceLayer]
            ?: return sequenceOf("${file.relativePath()}: 未定義のパッケージ $sourceLayer")

        return IMPORT_PATTERN.findAll(source)
            .map { it.groupValues[1] }
            // R はビルドが生成するリソースクラス。ルートパッケージに置かれるが
            // 自分たちの層構造の一部ではないので、依存の向きの対象にしない。
            .filterNot { it == GENERATED_RESOURCE_CLASS }
            .map(::layerOf)
            .filter { target -> target != sourceLayer && target !in allowed }
            .map { target -> "${file.relativePath()}: $sourceLayer -> $target" }
            .distinct()
    }

    /**
     * `com.example.newproject.` に続く識別子から層名を決める。
     *
     * 小文字始まりはサブパッケージ（`model.state` なら `model`）、大文字始まりは
     * ルートパッケージ直下の型（`NoteViewModel` など）を指すので [ROOT] へ寄せる。
     * 大文字側を無視すると `model` が `NoteViewModel` を import しても検出できず、
     * `model -> ルート -> controller -> model` の循環が素通りする。
     * 空文字はルート直下の `package` 宣言（`.<pkg>` を持たない）。
     */
    private fun layerOf(identifier: String): String =
        if (identifier.isNotEmpty() && identifier.first().isLowerCase()) identifier else ROOT

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

    private fun File.relativePath(): String =
        invariantSeparatorsPath.substringAfter("/src/main/java/")

    companion object {
        /** ルートパッケージ（`com.example.newproject` 直下）を表す層名。 */
        private const val ROOT = "(root)"
        private const val GENERATED_RESOURCE_CLASS = "R"

        // ルート直下のファイルは `.<pkg>` を持たないので、その場合はグループ1が空になる。
        private val PACKAGE_PATTERN =
            Regex("""(?m)^package\s+com\.example\.newproject(?:\.([a-z][A-Za-z0-9_]*))?\b""")
        private val IMPORT_PATTERN =
            Regex("""(?m)^import\s+com\.example\.newproject\.([A-Za-z][A-Za-z0-9_]*)\b""")
    }
}
