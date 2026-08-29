package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保護範囲を**カーソル越しにしか読まない**ことをソース走査で固定する。
 *
 * **時間で測れない経路があるから置いている。** 候補ごとに一覧の先頭へ戻る書き方は、
 * 最大入力でも百ミリ秒台にしかならない形があり（句分割の再併合は 23ms → 118ms）、
 * 上限テストの閾値では捕まらない。**それでも入力サイズの二乗であることは変わらない**ので、
 * 「先頭から舐める書き方を新しく足さない」を構造で守る。
 *
 * カーソルを通せば、問う位置が前へ戻らない限り全体が入力サイズに比例する。
 */
class DistillProtectedScanTest {

    @Test
    fun `保護範囲はカーソル越しにしか読まない`() {
        val source = sourceFile().readText()

        // **禁じるのはレシーバ付きの読み取り**（`syntax.protectedSpans` の形）だけ。
        // 併合済みの一覧を自分で持って自分のカーソルで舐めるローカル変数は、
        // 先頭へ戻らないので対象外にする。
        val violations = source.lines().withIndex()
            .filter { (_, line) -> QUALIFIED_READ.containsMatchIn(line) }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }

        assertTrue(
            "保護範囲を直接読んでいます。ProtectedCursor 越しに問い、" +
                "候補ごとに一覧の先頭へ戻らないでください:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private companion object {
        val QUALIFIED_READ = Regex("""[A-Za-z_]\w*\.protectedSpans""")
    }

    private fun sourceFile(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return listOf(workingDirectory, workingDirectory.parentFile)
            .map { it.resolve("app/src/main/java/com/example/newproject/domain/DistillSourceModel.kt") }
            .first { it.isFile }
    }
}
