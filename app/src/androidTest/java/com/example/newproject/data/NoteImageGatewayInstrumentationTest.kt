package com.example.newproject.data

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.newproject.domain.image.ImageRequest
import com.example.newproject.domain.image.ImageResolution
import com.example.newproject.domain.image.NoteImageLimits
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteImageFailure
import com.example.newproject.testing.FakeVaultDocumentsProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 画像の復号を**実物のSAF・実物の `BitmapFactory`** で通す（→ instrumentation_testing 段階3）。
 *
 * ## なぜ要るか
 *
 * 上限ちょうどの入力を切断扱いにしていた不具合の修正では、
 * `TooLarge` と `Broken` の切り分けを純関数 `imageDecodeFailureFor` へ切り出して
 * JVMテストで固定した。しかし**その判定を Gateway が正しい材料で・正しい位置から
 * 呼んでいるか**は覆えていなかった（`truncated` を読むタイミングと `openStream` の
 * 組み合わせ）。**レビューが要求した受理条件のうち、唯一その場で満たせなかった分**にあたる。
 *
 * ## JVMでは書けない理由
 *
 * `BitmapFactory`・`ContentResolver`・`DocumentsContract` に直結しており、
 * Robolectric もモックライブラリも入っていない。
 *
 * ## 上限の境界をここで通す意味
 *
 * 呼び出し側のメタデータ判定は `size > MAX_INPUT_BYTES` **だけ**を拒否するので、
 * **上限ちょうどは許可**である。`BoundedInputStream` が上限到達だけで打ち切りを
 * 立てると、正常な16MiBちょうどの画像が `TooLarge` へ化ける。
 * JVMでは境界そのものを固定したが、**実際に `BitmapFactory` へ食わせたときに
 * 同じ結論になるか**はここでしか分からない。
 */
@RunWith(AndroidJUnit4::class)
class NoteImageGatewayInstrumentationTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 索引の鮮度を試す用の時計。既定では動かさないので TTL には触れない。 */
    private var clock = 1_000L

    private fun browser() = SafVaultBrowser(
        contentResolver = targetContext.contentResolver,
        repository = NoteRepository(),
        vaultUri = { FakeVaultDocumentsProvider.treeUri }
    )

    private fun gateway(): NoteImageGateway =
        NoteImageGateway(
            contentResolver = targetContext.contentResolver,
            indexStore = VaultImageIndexStore(
                vault = browser(),
                vaultGeneration = { 1L },
                now = { clock }
            ),
            loadScope = scope
        )

    @Before
    fun setUp() {
        FakeVaultDocumentsProvider.cacheRootHolder = targetContext.cacheDir
        FakeVaultDocumentsProvider.reset()
        clock = 1_000L
    }

    @Test
    fun Vault内の画像を復号して寸法どおりに返す() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putBinaryFile("assets/photo.png", pngBytes(120, 80))

        val result = gateway().load(imageBlock("assets/photo.png"), targetWidthPx = 120)

        val bitmap = (result as NoteImageResult.Loaded).bitmap
        assertEquals(120, bitmap.width)
        assertEquals(80, bitmap.height)
    }

    @Test
    fun 寸法だけを読む経路も実物のSAFで通る() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putBinaryFile("assets/photo.png", pngBytes(200, 100))

        val result = gateway().measure(imageBlock("assets/photo.png"))

        assertEquals(NoteImageMeasureResult.Measured(200, 100), result)
    }

    /**
     * **上限に収まる大きな画像は、大きさを理由に落とさない。**
     *
     * サイズを申告しないプロバイダを使い、メタデータ判定を素通りさせて
     * `BoundedInputStream` の側だけで判断させる。上限直下まで実際に読ませたうえで
     * 復号が成功することを見る — **境界が1バイト内側へずれていれば `TooLarge` になる。**
     *
     * 非圧縮のBMPを使うのは、**復号器に最後まで読ませるため**。
     * PNGは中身がゼロ列だとヘッダで諦めるので、上限に到達せず境界を通らない。
     */
    @Test
    fun 上限に収まる大きな画像は大きすぎる扱いにならない() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putBinaryFile(
            vaultRelativePath = "assets/large.bmp",
            bytes = bmpBytes(width = 2400, height = 2300),
            reportSize = false
        )

        val result = gateway().load(imageBlock("assets/large.bmp"), targetWidthPx = 100)

        assertTrue(
            "上限内なのに落ちている: ${(result as? NoteImageResult.Failed)?.reason}",
            result is NoteImageResult.Loaded
        )
    }

    /**
     * 上限を超えた入力は、**サイズを申告しないプロバイダでも**「大きすぎる」。
     *
     * ここが `Broken` に化けたら、`truncated` を読む位置か順序が壊れている
     * （純関数 `imageDecodeFailureFor` は正しくても、Gateway の配線で崩れ得る）。
     * これがレビューで唯一満たせなかった受理条件にあたる。
     */
    @Test
    fun 上限を超えた入力はサイズ未申告でも大きすぎるとして扱う() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putBinaryFile(
            vaultRelativePath = "assets/over.bmp",
            bytes = bmpBytes(width = 2400, height = 2400),
            reportSize = false
        )

        val result = gateway().load(imageBlock("assets/over.bmp"), targetWidthPx = 100)

        assertEquals(
            NoteImageFailure.TooLarge,
            (result as NoteImageResult.Failed).reason
        )
    }

    /** サイズを申告するなら、読む前に弾ける。 */
    @Test
    fun 申告サイズが上限を超えていれば読む前に弾く() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putBinaryFile(
            vaultRelativePath = "assets/huge.png",
            bytes = ByteArray(NoteImageLimits.MAX_INPUT_BYTES.toInt() + 1)
        )

        val result = gateway().load(imageBlock("assets/huge.png"), targetWidthPx = 100)

        assertEquals(NoteImageFailure.TooLarge, (result as NoteImageResult.Failed).reason)
    }

    /**
     * 壊れた画像は「壊れている」。
     *
     * ここが `TooLarge` に化けると、**ユーザーは縮小すれば直ると誤解する。**
     */
    @Test
    fun 壊れた画像は大きすぎるではなく壊れているとして扱う() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putBinaryFile("assets/broken.png", "これは画像ではない".toByteArray())

        val result = gateway().load(imageBlock("assets/broken.png"), targetWidthPx = 100)

        assertEquals(NoteImageFailure.Broken, (result as NoteImageResult.Failed).reason)
    }

    /** Vaultに無い参照は「見つからない」。走査は成功しているので断定してよい。 */
    @Test
    fun Vaultに無い画像は見つからないとして扱う() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putFile("note.md")

        val result = gateway().load(imageBlock("assets/missing.png"), targetWidthPx = 100)

        assertTrue(result is NoteImageResult.Failed)
        assertTrue(
            "見つからない以外の理由になっている: ${(result as NoteImageResult.Failed).reason}",
            result.reason == NoteImageFailure.NotFound
        )
    }


    /**
     * **上書きした画像が古いBitmapへ固定されない。**
     *
     * 復号キャッシュの鍵は更新日時を含むが、索引が当たり続ける限りその値は
     * 初回走査時のまま固定される。JVM側は索引が返す世代までしか見られないので、
     * **鍵が実際に変わって復号し直されるか**はここでしか確かめられない。
     */
    @Test
    fun 外部から上書きした画像はTTL後に復号し直す() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putBinaryFile(
            vaultRelativePath = "assets/zu.png",
            bytes = pngBytes(120, 80),
            lastModified = 1_000L
        )
        val gateway = gateway()
        val before = gateway.load(imageBlock("assets/zu.png"), targetWidthPx = 120)
        assertEquals(120, (before as NoteImageResult.Loaded).bitmap.width)

        // Obsidian側で同じ名前のまま差し替えた状況。参照は変わらない。
        FakeVaultDocumentsProvider.putBinaryFile(
            vaultRelativePath = "assets/zu.png",
            bytes = pngBytes(60, 40),
            lastModified = 2_000L
        )
        clock += VaultImageIndexStore.INDEX_TTL_MS

        val after = gateway.load(imageBlock("assets/zu.png"), targetWidthPx = 120)

        assertEquals(
            "古いBitmapが返っている（鍵の世代が更新されていない）",
            60,
            (after as NoteImageResult.Loaded).bitmap.width
        )
    }

    /** 存在するドキュメントは、実物のSAFで更新日時を引ける。 */
    @Test
    fun 更新日時の照会は存在するドキュメントで値を返す() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putBinaryFile(
            vaultRelativePath = "assets/zu.png",
            bytes = pngBytes(10, 10),
            lastModified = 4_242L
        )
        val handle = checkNotNull(browser().current())
        val ref = indexRefOf("assets/zu.png")

        assertEquals(DocumentVersionLookup.Found(4_242L), handle.documentVersion(ref))
    }

    /**
     * **消えたドキュメントの照会は「確かめられない」になる。**
     *
     * SAF は空のカーソルではなく例外で答えるので、「行が返らない＝消えている」を
     * 分けても実際には来ない。索引を作り直す側へ倒す判断（→ [DocumentVersionLookup]）は
     * この振る舞いに乗っているので、**実物のプロバイダで固定しておく。**
     */
    @Test
    fun 消えたドキュメントの照会は確かめられない扱いになる() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putBinaryFile(
            vaultRelativePath = "assets/zu.png",
            bytes = pngBytes(10, 10)
        )
        val handle = checkNotNull(browser().current())
        val ref = indexRefOf("assets/zu.png")
        DocumentsContract.deleteDocument(targetContext.contentResolver, ref.toUri())

        assertEquals(DocumentVersionLookup.Unconfirmed, handle.documentVersion(ref))
    }

    // --- 補助 -----------------------------------------------------------------

    /** 索引を1回作って、そのパスの参照を取り出す。 */
    private suspend fun indexRefOf(path: String): DocumentRef {
        val store = VaultImageIndexStore(vault = browser(), vaultGeneration = { 1L }, now = { clock })
        val resolution = store.resolve(ImageRequest.Lookup(path, path.substringAfterLast('/')))
        return (resolution as ImageResolution.Resolved).ref
    }


    private fun imageBlock(path: String) =
        MarkdownBlock.Image(alt = "", target = path, isEmbed = false)

    /** 実物の `BitmapFactory` が読める PNG を作る。 */
    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 120, 200))
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    /**
     * 非圧縮の24bit BMP を組み立てる。
     *
     * **上限の境界を通すには「復号器が最後まで読む」必要がある。**
     * PNGは圧縮されるので、狙ったバイト数のファイルを作れず、中身がゼロ列なら
     * ヘッダで諦めて上限に到達しない。BMPは寸法からファイル長が決まり、
     * 画素データを最後まで読ませられるので、境界の検証に向く。
     *
     * ファイル長 = 54 + ((width * 3 + 3) / 4 * 4) * height。
     */
    private fun bmpBytes(width: Int, height: Int): ByteArray {
        val rowSize = (width * 3 + 3) / 4 * 4
        val pixelBytes = rowSize * height
        val fileSize = HEADER_BYTES + pixelBytes
        val bytes = ByteArray(fileSize)

        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        writeIntLe(bytes, 2, fileSize)
        writeIntLe(bytes, 10, HEADER_BYTES)
        writeIntLe(bytes, 14, 40)
        writeIntLe(bytes, 18, width)
        writeIntLe(bytes, 22, height)
        writeShortLe(bytes, 26, 1)
        writeShortLe(bytes, 28, 24)
        writeIntLe(bytes, 34, pixelBytes)
        writeIntLe(bytes, 38, 2835)
        writeIntLe(bytes, 42, 2835)
        // 画素は 0 のまま（黒）。中身は問わず、長さと読み切れることだけが要る。
        return bytes
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = (value shr 8 and 0xFF).toByte()
        target[offset + 2] = (value shr 16 and 0xFF).toByte()
        target[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private companion object {
        const val HEADER_BYTES = 54
    }
}
