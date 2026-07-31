package com.example.newproject

import com.example.newproject.data.AnnotationDocumentGateway
import com.example.newproject.data.AnnotationFileWriter
import com.example.newproject.data.AnnotationWriteResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 補記1件の書き出しが、どの段階で失敗しても残骸を残さないことを固定する。
 *
 * 以前は `createDocument()` の直後に書き込むだけで後始末が無く、画面に生成失敗と
 * 出しても `_AI補記` に空・部分ファイルが残り得た。
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
    fun `後始末の削除に失敗しても元の失敗理由を返す`() {
        val gateway = FakeGateway(failWrite = true, failDelete = true)

        val result = AnnotationFileWriter(gateway).create("メモ.md", "本文")

        // 削除できないこと自体は握りつぶす。ここを例外にすると
        // 「書けなかった」が「消せなかった」にすり替わる。
        assertTrue(result is AnnotationWriteResult.Failure)
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

        override fun deleteQuietly(reference: String) {
            deleteCalls++
            if (failDelete) return
            files.remove(reference)
            names.remove(reference)
        }

        override fun displayName(reference: String): String? =
            if (hideDisplayName) null else names[reference]
    }
}
