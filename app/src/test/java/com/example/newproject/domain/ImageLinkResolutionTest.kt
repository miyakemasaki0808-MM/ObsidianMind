package com.example.newproject.domain

import com.example.newproject.domain.image.ImageRequest
import com.example.newproject.domain.image.ImageResolution
import com.example.newproject.domain.image.NoteImageEntry
import com.example.newproject.domain.image.NoteImageIndex
import com.example.newproject.domain.image.imageRequestOf
import com.example.newproject.domain.image.normalizeVaultImagePath
import com.example.newproject.domain.image.percentDecode
import com.example.newproject.domain.image.resolveImage
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteImageFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLinkResolutionTest {

    private fun link(target: String) = MarkdownBlock.Image("alt", target, isEmbed = false)
    private fun embed(target: String) = MarkdownBlock.Image("", target, isEmbed = true)
    private fun ref(value: String) = DocumentRef(value)

    private fun index(vararg paths: Pair<String, String>, isComplete: Boolean = true) =
        NoteImageIndex.of(
            paths.map { (path, id) -> NoteImageEntry(path, ref(id)) },
            isComplete = isComplete
        )

    // --- パーセントデコード -------------------------------------------------

    @Test
    fun `空白のパーセントエンコードを解く`() {
        assertEquals("my image.png", percentDecode("my%20image.png"))
    }

    @Test
    fun `日本語のパーセントエンコードを解く`() {
        assertEquals("図.png", percentDecode("%E5%9B%B3.png"))
    }

    @Test
    fun `プラス記号は空白へ変換しない`() {
        // URLDecoder はクエリ文字列の規則で `+` を空白にする。パスに当てると壊れる。
        assertEquals("a+b.png", percentDecode("a+b.png"))
    }

    @Test
    fun `不正なエスケープはそのままの文字として残す`() {
        assertEquals("%ZZ.png", percentDecode("%ZZ.png"))
        assertEquals("末尾%A", percentDecode("末尾%A"))
    }

    @Test
    fun `サロゲートペアを含むファイル名が壊れない`() {
        // 1文字ずつUTF-8へ変換すると単独サロゲートに割れて置換文字になる。
        assertEquals("😀 図.png", percentDecode("😀%20図.png"))
    }

    // --- 正規化 -------------------------------------------------------------

    @Test
    fun `バックスラッシュ区切りをスラッシュへ寄せる`() {
        assertEquals("a/b.png", normalizeVaultImagePath("a\\b.png"))
    }

    @Test
    fun `先頭スラッシュと空要素を捨てる`() {
        assertEquals("a/b.png", normalizeVaultImagePath("/a//b.png"))
    }

    @Test
    fun `カレントディレクトリ指定を捨てる`() {
        assertEquals("a/b.png", normalizeVaultImagePath("./a/./b.png"))
    }

    @Test
    fun `親ディレクトリ指定は1段上へ畳む`() {
        assertEquals("b.png", normalizeVaultImagePath("a/../b.png"))
    }

    @Test
    fun `ルートを超える親ディレクトリ指定は捨てる`() {
        assertEquals("b.png", normalizeVaultImagePath("../../b.png"))
    }

    // --- 解決要求 -----------------------------------------------------------

    @Test
    fun `埋め込みのサイズヒントは対象から外す`() {
        assertEquals(
            ImageRequest.Lookup("zu.png", "zu.png"),
            imageRequestOf(embed("zu.png|400"))
        )
    }

    @Test
    fun `リンク記法では縦棒を対象から外さない`() {
        // `![alt|400](path)` の `|` は alt 側に付くので、target の `|` は対象の一部。
        val request = imageRequestOf(link("a|b.png")) as ImageRequest.Lookup
        assertEquals("a|b.png", request.vaultPath)
    }

    @Test
    fun `httpとhttpsは外部URLとして扱う`() {
        assertEquals(
            ImageRequest.External("https://example.com/a.png"),
            imageRequestOf(link("https://example.com/a.png"))
        )
        assertTrue(imageRequestOf(link("http://example.com/a.png")) is ImageRequest.External)
    }

    @Test
    fun `dataURIは外部として扱う`() {
        assertTrue(imageRequestOf(link("data:image/png;base64,AAAA")) is ImageRequest.External)
    }

    @Test
    fun `ドライブレターに見えるパスを外部URLと誤検出しない`() {
        // `://` を要求しているので `C:/...` はVault内のパスとして扱う。
        assertTrue(imageRequestOf(link("C:/photo.png")) is ImageRequest.Lookup)
    }

    @Test
    fun `対象が空なら空として扱う`() {
        assertEquals(ImageRequest.Empty, imageRequestOf(link("")))
        assertEquals(ImageRequest.Empty, imageRequestOf(link("   ")))
        assertEquals(ImageRequest.Empty, imageRequestOf(embed("|400")))
    }

    @Test
    fun `パス付きの対象はファイル名を最終要素にする`() {
        assertEquals(
            ImageRequest.Lookup("attachments/zu.png", "zu.png"),
            imageRequestOf(link("attachments/%E3%81%82/../zu.png"))
        )
    }

    // --- 照合 ---------------------------------------------------------------

    @Test
    fun `完全パス一致で解決する`() {
        val index = index("attachments/zu.png" to "doc-1", "other/zu2.png" to "doc-2")
        val result = resolveImage(imageRequestOf(link("attachments/zu.png")), index)
        assertEquals(ImageResolution.Resolved(ref("doc-1")), result)
    }

    @Test
    fun `完全パスが外れてもファイル名で解決する`() {
        val index = index("attachments/zu.png" to "doc-1")
        val result = resolveImage(imageRequestOf(embed("zu.png")), index)
        assertEquals(ImageResolution.Resolved(ref("doc-1")), result)
    }

    @Test
    fun `索引側のファイル名が大文字でも一致する`() {
        val index = index("attachments/Zu.PNG" to "doc-1")
        val result = resolveImage(imageRequestOf(embed("zu.png")), index)
        assertEquals(ImageResolution.Resolved(ref("doc-1")), result)
    }

    @Test
    fun `要求側のファイル名が大文字でも一致する`() {
        // 索引側だけ小文字へ畳んでも、照合側で畳まなければ当たらない。
        // 索引側の大小文字テストとは別に、**要求側が大文字の場合**を分けて置く。
        val index = index("attachments/zu.png" to "doc-1")
        val result = resolveImage(imageRequestOf(embed("ZU.PNG")), index)
        assertEquals(ImageResolution.Resolved(ref("doc-1")), result)
    }

    @Test
    fun `索引側のパスも正規化される`() {
        // 同名を2つ置くのは、**ファイル名フォールバックに救われないようにする**ため。
        // 1件だけだと索引側の正規化を外してもファイル名一致で解決してしまい、
        // 完全パスのキーが揃っているかを検証できない。
        val index = index("./attachments//zu.png" to "doc-1", "other/zu.png" to "doc-2")
        val result = resolveImage(imageRequestOf(link("attachments/zu.png")), index)
        assertEquals(ImageResolution.Resolved(ref("doc-1")), result)
    }

    @Test
    fun `同名が複数あるときは解決せず候補数を返す`() {
        val index = index("a/zu.png" to "doc-1", "b/zu.png" to "doc-2")
        val result = resolveImage(imageRequestOf(embed("zu.png")), index)
        assertEquals(ImageResolution.Failed(NoteImageFailure.Ambiguous(2)), result)
    }

    @Test
    fun `完全パスが当たれば同名が複数あっても確定させる`() {
        val index = index("a/zu.png" to "doc-1", "b/zu.png" to "doc-2")
        val result = resolveImage(imageRequestOf(link("b/zu.png")), index)
        assertEquals(ImageResolution.Resolved(ref("doc-2")), result)
    }

    @Test
    fun `完全な索引で見つからなければ不在と断定する`() {
        val index = index("a/zu.png" to "doc-1", isComplete = true)
        val result = resolveImage(imageRequestOf(embed("nai.png")), index)
        assertEquals(ImageResolution.Failed(NoteImageFailure.NotFound), result)
    }

    @Test
    fun `不完全な索引では不在と断定しない`() {
        // 読めなかったフォルダがある状態で「Vaultにありません」と言い切ってはいけない。
        val index = index("a/zu.png" to "doc-1", isComplete = false)
        val result = resolveImage(imageRequestOf(embed("nai.png")), index)
        assertEquals(ImageResolution.Failed(NoteImageFailure.Unverifiable), result)
    }

    @Test
    fun `不完全な索引でも見つかれば解決する`() {
        val index = index("a/zu.png" to "doc-1", isComplete = false)
        val result = resolveImage(imageRequestOf(embed("zu.png")), index)
        assertEquals(ImageResolution.Resolved(ref("doc-1")), result)
    }

    @Test
    fun `外部URLと空はそのまま結果へ通る`() {
        val index = index("a/zu.png" to "doc-1")
        assertEquals(
            ImageResolution.Failed(NoteImageFailure.External("https://example.com/a.png")),
            resolveImage(imageRequestOf(link("https://example.com/a.png")), index)
        )
        assertEquals(ImageResolution.Failed(NoteImageFailure.Empty), resolveImage(imageRequestOf(link("")), index))
    }
}
