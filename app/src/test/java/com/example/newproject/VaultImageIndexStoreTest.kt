package com.example.newproject

import com.example.newproject.data.DocumentVersionLookup
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

    private fun scan(
        vararg paths: Pair<String, String>,
        isComplete: Boolean = true,
        lastModified: Long? = null
    ) =
        VaultImageScan(
            entries = paths.map { (path, id) -> NoteImageEntry(path, DocumentRef(id), lastModified) },
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


    // --- ヒットの鮮度確認 -----------------------------------------------------

    @Test
    fun `索引が古ければヒットでも世代を引き直す`() = runTest {
        // Obsidian側で同じ名前のまま画像を差し替えた状況。参照は変わらないので
        // 索引の値のままだと復号キャッシュの鍵が動かず、古いBitmapが固定される。
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-1", lastModified = 1L))
        handle.documentVersions = { DocumentVersionLookup.Found(2L) }
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("a.png"))
        clock += ttl

        assertEquals(
            ImageResolution.Resolved(DocumentRef("doc-1"), 2L),
            store.resolve(lookup("a.png"))
        )
        // **引き直しで全走査を増やさない。** ここが増えるなら索引ごと作り直している。
        assertEquals(1, store.scanCount)
    }

    @Test
    fun `索引が新しいうちは世代を引き直さない`() = runTest {
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-1", lastModified = 1L))
        handle.documentVersions = { DocumentVersionLookup.Found(2L) }
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("a.png"))
        store.resolve(lookup("a.png"))

        assertEquals(
            ImageResolution.Resolved(DocumentRef("doc-1"), 1L),
            store.resolve(lookup("a.png"))
        )
        assertEquals(0, handle.documentVersionCount)
    }

    @Test
    fun `世代を返さないプロバイダでは索引の値を使う`() = runTest {
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-1", lastModified = 1L))
        handle.documentVersions = { DocumentVersionLookup.Found(null) }
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("a.png"))
        clock += ttl

        assertEquals(
            ImageResolution.Resolved(DocumentRef("doc-1"), 1L),
            store.resolve(lookup("a.png"))
        )
    }

    @Test
    fun `参照先が消えていたら索引を作り直して引き当て直す`() = runTest {
        // 削除して同じ名前で作り直した状況。ヒットのままだと壊れた参照へ固定される。
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-old"))
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("a.png"))
        clock += ttl
        handle.documentVersions = { ref ->
            if (ref == DocumentRef("doc-old")) DocumentVersionLookup.Unconfirmed
            else DocumentVersionLookup.Found(9L)
        }
        handle.imageScan = scan("a.png" to "doc-new", lastModified = 9L)

        assertEquals(
            ImageResolution.Resolved(DocumentRef("doc-new"), 9L),
            store.resolve(lookup("a.png"))
        )
        assertEquals(2, store.scanCount)
    }

    @Test
    fun `照会が例外を投げても存在を確かめられなかった扱いにする`() = runTest {
        // 実プロバイダは消えたドキュメントの照会に例外で答える。ここで
        // 索引を信じ続けると、削除して作り直した画像が古い参照へ固定される。
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-old"))
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("a.png"))
        clock += ttl
        handle.documentVersions = { throw IllegalStateException("document is gone") }
        handle.imageScan = scan("a.png" to "doc-new", lastModified = 9L)

        assertEquals(
            ImageResolution.Resolved(DocumentRef("doc-new"), 9L),
            store.resolve(lookup("a.png"))
        )
    }

    @Test
    fun `確かめられなくても作り直しはTTLごとに1回に収まる`() = runTest {
        // 照会が常に失敗するプロバイダでも、合流先の歯止めが効いている限り
        // 全走査は TTL ごとに1回で済む。
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-1"))
        handle.documentVersions = { DocumentVersionLookup.Unconfirmed }
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("a.png"))
        clock += ttl
        store.resolve(lookup("a.png"))
        store.resolve(lookup("a.png"))
        store.resolve(lookup("a.png"))

        assertEquals(2, store.scanCount)
    }

    @Test
    fun `壊れたリンクは世代の照会も誘発しない`() = runTest {
        val handle = FakeVaultHandle(imageScan = scan("a.png" to "doc-1"))
        val store = store(FakeVaultBrowser(handle))

        store.resolve(lookup("missing.png"))
        clock += ttl
        store.resolve(lookup("missing.png"))

        // 外した要求には引き直す相手がいない。**照会は当たったときだけ。**
        assertEquals(0, handle.documentVersionCount)
    }

    @Test
    fun `外部URLと空は索引を作らずに返す`() = runTest {
        val store = store(FakeVaultBrowser(FakeVaultHandle(imageScan = scan())))

        assertEquals(
            ImageResolution.Failed(NoteImageFailure.External("https://example.com/a.png")),
            store.resolve(ImageRequest.External("https://example.com/a.png"))
        )
        assertEquals(ImageResolution.Failed(NoteImageFailure.Empty), store.resolve(ImageRequest.Empty))
        // 索引を要らない要求で走査を起こさない。外部画像しか無いノートを開いただけで
        // Vault全走査が走ると、初回表示がそのぶん遅れる。
        assertEquals(0, store.scanCount)
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
