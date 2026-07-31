package com.example.newproject

import com.example.newproject.data.AnnotationDocumentGateway
import com.example.newproject.data.AnnotationFileWriter
import com.example.newproject.data.AnnotationWriteResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 補記1件の書き出しが、どの段階で失敗しても後始末を試み、
 * **消せなかった場合はそれを利用者へ伝える**ことを固定する。
 *
 * 以前は `createDocument()` の直後に書き込むだけで後始末が無く、画面に生成失敗と
 * 出しても `_AI補記` に空・部分ファイルが残り得た。削除はSAFプロバイダ側の都合で
 * 失敗し得るので「必ず消える」とは言えない。**言えるのは「消すか、残ったと伝えるか」**。
 */
class AnnotationFileWriterTest {

    @Test
    fun `書き込めたら参照と保存後の実名を返す`() {
        val gateway = FakeGateway()
        val writer = AnnotationFileWriter(gateway)

        val result = writer.create("ノート__補記_20260731_1200.md", "本文")

        val success = result as AnnotationWriteResult.Success
        assertEquals("ノート__補記_20260731_1200.md", success.displayName)
        assertEquals("本文", gateway.contentOf(success.reference))
    }

    @Test
    fun `プロバイダが改名したら予測名ではなく実名を返す`() {
        // 同じノートを同じ分に再生成すると、SAF側が別名を割り当てることがある。
        val gateway = FakeGateway(renameTo = "ノート__補記_20260731_1200 (1).md")
        val writer = AnnotationFileWriter(gateway)

        val result = writer.create("ノート__補記_20260731_1200.md", "本文")

        assertEquals(
            "ノート__補記_20260731_1200 (1).md",
            (result as AnnotationWriteResult.Success).displayName
        )
    }

    @Test
    fun `ファイルを作れなければ失敗を返す`() {
        val gateway = FakeGateway(failCreate = true)

        val result = AnnotationFileWriter(gateway).create("メモ.md", "本文")

        assertTrue(result is AnnotationWriteResult.Failure)
        assertTrue(gateway.files.isEmpty())
    }

    @Test
    fun `書き込みに失敗したら作成済みファイルを消す`() {
        val gateway = FakeGateway(failWrite = true)

        val result = AnnotationFileWriter(gateway).create("メモ.md", "本文")

        assertTrue(result is AnnotationWriteResult.Failure)
        assertTrue("空ファイルが残っています", gateway.files.isEmpty())
        assertEquals(1, gateway.deleteCalls)
    }

    @Test
    fun `読み直せなければ作成済みファイルを消す`() {
        val gateway = FakeGateway(failReadBack = true)

        val result = AnnotationFileWriter(gateway).create("メモ.md", "本文")

        assertTrue(result is AnnotationWriteResult.Failure)
        assertTrue(gateway.files.isEmpty())
    }

    @Test
    fun `部分的にしか書けていなければ失敗として扱い消す`() {
        // ストリームは開けたが途中で切れた状態。例外にならないので検証でしか気づけない。
        val gateway = FakeGateway(truncateWriteTo = 3)

        val result = AnnotationFileWriter(gateway).create("メモ.md", "十分な長さの本文")

        assertTrue(result is AnnotationWriteResult.Failure)
        assertTrue("部分ファイルが残っています", gateway.files.isEmpty())
    }

    @Test
    fun `後始末の削除に失敗したら残骸が残ったことを伝える`() {
        val gateway = FakeGateway(failWrite = true, failDelete = true)

        val result = AnnotationFileWriter(gateway).create("メモ.md", "本文") as AnnotationWriteResult.Failure

        // 削除できないこと自体は例外にしない（「書けなかった」が「消せなかった」に
        // すり替わる）。ただし黙ってもいけない — 残骸が残ったことは元の理由に足して伝える。
        assertTrue("元の失敗理由が消えています", result.message.contains("書き込めませんでした"))
        assertTrue("残骸の通知がありません", result.message.contains("残った可能性"))
        assertTrue(result.residueLeft)
        assertEquals(1, gateway.files.size)
    }

    @Test
    fun `後始末に成功したら残骸の通知は付けない`() {
        val gateway = FakeGateway(failWrite = true)

        val result = AnnotationFileWriter(gateway).create("メモ.md", "本文") as AnnotationWriteResult.Failure

        assertFalse("消せているのに残骸を通知しています", result.message.contains("残った可能性"))
        assertFalse(result.residueLeft)
    }

    @Test
    fun `検証に失敗して削除もできなければ残骸を伝える`() {
        val gateway = FakeGateway(truncateWriteTo = 3, failDelete = true)

        val result = AnnotationFileWriter(gateway).create("メモ.md", "十分な長さの本文") as AnnotationWriteResult.Failure

        assertTrue(result.residueLeft)
        assertTrue(result.message.contains("残った可能性"))
    }

    @Test
    fun `作成そのものに失敗したときは残骸を通知しない`() {
        // まだ何も作れていないので、後始末も残骸も無い。
        val result = AnnotationFileWriter(FakeGateway(failCreate = true))
            .create("メモ.md", "本文") as AnnotationWriteResult.Failure

        assertFalse(result.residueLeft)
        assertFalse(result.message.contains("残った可能性"))
    }

    @Test
    fun `中断されても作りかけのファイルは残さない`() {
        val gateway = FakeGateway(cancelOnWrite = true)

        val thrown = runCatching { AnnotationFileWriter(gateway).create("メモ.md", "本文") }

        assertTrue(thrown.exceptionOrNull() is CancellationException)
        assertTrue(gateway.files.isEmpty())
    }

    @Test
    fun `表示名を取れなければ要求した名前へ落とす`() {
        val gateway = FakeGateway(renameTo = null, hideDisplayName = true)

        val result = AnnotationFileWriter(gateway).create("メモ.md", "本文")

        assertEquals("メモ.md", (result as AnnotationWriteResult.Success).displayName)
    }

    private class FakeGateway(
        private val failCreate: Boolean = false,
        private val failWrite: Boolean = false,
        private val failReadBack: Boolean = false,
        private val failDelete: Boolean = false,
        private val cancelOnWrite: Boolean = false,
        private val truncateWriteTo: Int? = null,
        private val renameTo: String? = null,
        private val hideDisplayName: Boolean = false
    ) : AnnotationDocumentGateway {
        val files = mutableMapOf<String, ByteArray>()
        private val names = mutableMapOf<String, String>()
        var deleteCalls = 0
            private set

        fun contentOf(reference: String): String? = files[reference]?.toString(Charsets.UTF_8)

        override fun createFile(fileName: String): String? {
            if (failCreate) return null
            val reference = "fake://${files.size}"
            files[reference] = ByteArray(0)
            names[reference] = renameTo ?: fileName
            return reference
        }

        override fun write(reference: String, bytes: ByteArray) {
            if (cancelOnWrite) throw CancellationException("中断")
            if (failWrite) error("書き込めませんでした")
            files[reference] = truncateWriteTo?.let { bytes.copyOf(it) } ?: bytes
        }

        override fun readBack(reference: String): ByteArray? =
            if (failReadBack) null else files[reference]

        override fun delete(reference: String): Boolean {
            deleteCalls++
            if (failDelete) return false
            files.remove(reference)
            names.remove(reference)
            return true
        }

        override fun displayName(reference: String): String? =
            if (hideDisplayName) null else names[reference]
    }
}
