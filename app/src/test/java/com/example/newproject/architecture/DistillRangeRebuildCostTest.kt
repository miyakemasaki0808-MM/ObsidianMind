package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **候補状態の作り直しに、入力サイズへ比例する処理を入れない。**
 *
 * 段の導出（`presetRangesFor`）は `model.sentences` を舐めるので本文の長さに比例する。
 * 段をタップするだけなら1回きりだが、**自由範囲のドラッグは指の動きに合わせて候補状態を
 * 作り直す**ので、作り直しの中に置くと大きなノートでフレームごとに候補数×文数が走る。
 *
 * 時間では測れない — 小さな fixture では一瞬で終わり、閾値に掛からないまま
 * 比例したままになる（→ `DistillProtectedScanTest` と同じ型の検査）。
 * **セッションを組むときに1度だけ引く**ことを呼び出し回数で固定する。
 */
class DistillRangeRebuildCostTest {

    @Test
    fun `段の導出はセッションを組むときの1回だけ`() {
        val calls = sourceFile().readText().lines().withIndex()
            .filter { (_, line) -> CALL.containsMatchIn(line) }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }

        assertEquals(
            "段の導出は候補状態の作り直しから呼ばないでください。" +
                "ドラッグ中はフレームごとに走ります:\n" + calls.joinToString("\n"),
            1,
            calls.size
        )
    }

    private companion object {
        val CALL = Regex("""presetRangesFor\(""")
    }

    private fun sourceFile(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return listOf(workingDirectory, workingDirectory.parentFile)
            .map { it.resolve("app/src/main/java/com/example/newproject/controller/DistillController.kt") }
            .first { it.isFile }
    }
}
