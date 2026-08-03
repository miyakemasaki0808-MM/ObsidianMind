package com.example.newproject

import com.example.newproject.data.VaultImageIndexStore
import com.example.newproject.data.VaultImageScan
import com.example.newproject.domain.image.ImageRequest
import com.example.newproject.domain.image.ImageResolution
import com.example.newproject.domain.image.NoteImageEntry
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteImageFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultImageIndexStoreTest {

    private val ttl = 60_000L
    private var clock = 1_000L
    private var generation = 0L

    private fun scan(vararg paths: Pair<String, String>, isComplete: Boolean = true) =
        VaultImageScan(
            entries = paths.map { (path, id) -> NoteImageEntry(path, DocumentRef(id)) },
            isComplete = isComplete
        )

    private fun store(browser: FakeVaultBrowser) = VaultImageIndexStore(
        vault = browser,
        vaultGeneration = { generation },
        now = { clock },
        ttlMillis = ttl
    )

    private fun lookup(path: String) = ImageRequest.Lookup(path, path.substringAfterLast('/'))

    @Test
    fun `索引を作って解決する`() = runTest {
        val handle = FakeVaultHandle(imageScan = scan("attachments/zu.png" to "doc-1"))
        val store = store(FakeVaultBrowser(handle))

        assertEquals(
            ImageResolution.Resolved(DocumentRef("doc-1")),
            store.resolve(lookup("attachments/zu.png"))
        )
        assertEquals(1, store.scanCount)
    }

    @Test
    fun `2枚目以降は索引を作り直さない`() = runTest {
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-1", "b.png" to "doc-2"))
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("a.png"))
        store.resolve(lookup("b.png"))

        assertEquals(1, store.scanCount)
    }

    @Test
    fun `Vault世代が進むと索引を作り直す`() = runTest {
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-1"))
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("a.png"))
        generation++
        handle.imageScan = scan("a.png" to "doc-new")

        assertEquals(
            ImageResolution.Resolved(DocumentRef("doc-new")),
            store.resolve(lookup("a.png"))
        )
        assertEquals(2, store.scanCount)
    }

    // --- miss時の再走査と歯止め ---------------------------------------------

    @Test
    fun `索引が古い状態で外したら作り直して再試行する`() = runTest {
        // Obsidian側で画像を足した状況。切替を待たずに出るようにする。
        val handle = FakeVaultHandle(imageScan = scan())
        val store = store(FakeVaultBrowser(handle))

        assertEquals(ImageResolution.Failed(NoteImageFailure.NotFound), store.resolve(lookup("new.png")))
        clock += ttl
        handle.imageScan = scan("new.png" to "doc-new")

        assertEquals(
            ImageResolution.Resolved(DocumentRef("doc-new")),
            store.resolve(lookup("new.png"))
        )
        assertEquals(2, store.scanCount)
    }

    @Test
    fun `索引が新しいうちは外しても作り直さない`() = runTest {
        val handle = FakeVaultHandle(imageScan = scan())
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("nai.png"))
        clock += ttl / 2
        store.resolve(lookup("nai.png"))

        assertEquals(1, store.scanCount)
    }

    @Test
    fun `壊れたリンクは再走査を誘発し続けない`() = runTest {
        // 作り直しても見つからない場合、読込時刻を更新するので次のTTLまでは走査しない。
        val handle = FakeVaultHandle(imageScan = scan())
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("nai.png"))
        clock += ttl
        store.resolve(lookup("nai.png"))
        val afterRebuild = store.scanCount
        store.resolve(lookup("nai.png"))
        store.resolve(lookup("nai.png"))

        assertEquals(2, afterRebuild)
        assertEquals(2, store.scanCount)
    }

    @Test
    fun `見つかったときは索引が古くても作り直さない`() = runTest {
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-1"))
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("a.png"))
        clock += ttl * 10
        store.resolve(lookup("a.png"))

        assertEquals(1, store.scanCount)
    }

    @Test
    fun `外部URLと空は索引を作らずに返す`() = runTest {
        val store = store(FakeVaultBrowser(FakeVaultHandle(imageScan = scan())))

        assertEquals(
            ImageResolution.Failed(NoteImageFailure.External("https://example.com/a.png")),
            store.resolve(ImageRequest.External("https://example.com/a.png"))
        )
        assertEquals(ImageResolution.Failed(NoteImageFailure.Empty), store.resolve(ImageRequest.Empty))
    }

    // --- 完全性 -------------------------------------------------------------

    @Test
    fun `不完全な索引では不在と断定しない`() = runTest {
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-1", isComplete = false))
        val store = store(FakeVaultBrowser(handle))

        assertEquals(ImageResolution.Failed(NoteImageFailure.Unverifiable), store.resolve(lookup("nai.png")))
    }

    @Test
    fun `不完全な索引もキャッシュする`() = runTest {
        // 禁じると、読めないフォルダが1つあるだけで画像1枚ごとに全走査が走る。
        // 「無い」と断定しない保証は Unverifiable が型として持っている。
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-1", isComplete = false))
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("a.png"))
        store.resolve(lookup("a.png"))

        assertEquals(1, store.scanCount)
    }

    @Test
    fun `走査が例外で落ちても不在と断定しない`() = runTest {
        val handle = FakeVaultHandle(imageScan = scan(), failure = IllegalStateException("SAF失敗"))
        val store = store(FakeVaultBrowser(handle))

        assertEquals(ImageResolution.Failed(NoteImageFailure.Unverifiable), store.resolve(lookup("a.png")))
    }

    @Test
    fun `Vault未選択なら不在と断定しない`() = runTest {
        val store = store(FakeVaultBrowser(handle = null))

        assertEquals(ImageResolution.Failed(NoteImageFailure.Unverifiable), store.resolve(lookup("a.png")))
        assertEquals(0, store.scanCount)
    }
}
