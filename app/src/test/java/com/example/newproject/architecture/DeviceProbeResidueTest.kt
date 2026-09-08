package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **実機検証のために書いた使い捨てテストが、作業ツリーに残ったままにならないことを固定する。**
 *
 * ## なぜ要るか
 *
 * 遅延・失敗・走行中の切替は端末設定を手で変えると再現が不安定なので、
 * 本番のControllerと画面を実Composeへ繋いだ**使い捨てテスト**を書いてよいことにしている
 * （→ [docs/review/device_validation/quick_check.md] §一時テスト）。
 *
 * **書いてよい代わりに、消す側に何の担保も無かった。** 2026-09-07、利用枠切れで検証が中断した回に
 * `Book4DeviceProbeTest.kt` が作業ツリーへ残り、**次のセッションが気づく仕組みが1つも無かった。**
 * 残ると test APK へ入り続け、いつか意図せずコミットされる。
 *
 * **文書へ「消すこと」と書くだけにしない。** このリポジトリで規則が守られたのは
 * 検査に載せたときだけ、というのが実績である（→ docs/dev/lessons/L29.md）。
 *
 * ## 落ちる時機が正しいこと
 *
 * 共通手順は、**実機検証の前**（一時テストを作る前）と**後処理の最後**（消した後）に
 * JVMゲートを通す。どちらも緑になる。**赤くなるのは「一時テストを作ったまま終えた」ときだけ**で、
 * それはまさに検出したい状態である。
 *
 * ## 見ていないもの
 *
 * - **中身**。使い捨てとして妥当かは見ない
 * - `*DeviceProbeTest.kt` 以外の名前で書かれた使い捨て。**命名は人が守る**
 *   （守らなければ、そもそもこの検査の対象にならないだけで、恒久テストとして残る）
 */
class DeviceProbeResidueTest {

    @Test
    fun `使い捨ての一時テストは作業ツリーに残っていない`() {
        val residue = androidTestRoot().walkTopDown()
            .filter { it.isFile && it.name.endsWith(PROBE_SUFFIX) }
            .map { it.relativePath() }
            .sorted()
            .toList()

        assertTrue(
            "実機検証の一時テストが残っています。検証が終わったら削除してください" +
                "（続きがあるなら evidence/ へ退避する）:\n${residue.joinToString("\n")}",
            residue.isEmpty()
        )
    }

    private fun androidTestRoot(): File = repositoryRoot().resolve("app/src/androidTest").also {
        assertTrue("androidTest が見つかりません: $it", it.isDirectory)
    }

    private fun File.relativePath(): String =
        invariantSeparatorsPath.substringAfter("/app/src/androidTest/")

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(workingDirectory.resolve(".."), workingDirectory)
        return candidates.firstOrNull { it.resolve("CLAUDE.md").isFile }
            ?: error("リポジトリルートが見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        const val PROBE_SUFFIX = "DeviceProbeTest.kt"
    }
}
