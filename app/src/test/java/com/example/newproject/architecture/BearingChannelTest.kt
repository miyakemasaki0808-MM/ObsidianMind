package com.example.newproject.architecture

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import com.example.newproject.ui.theme.BrowsingSheetShape
import com.example.newproject.ui.theme.ReadingSurfaceShape
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 佇まいのチャネル割り当てのうち、**形の役割**を固定する（→ docs/dev/system/bearing_channels.md）。
 *
 * ## なぜ検査が要るか
 *
 * 冊子と本文が見分けられなかった根本は、**両方が同じ角丸の値を直に書いていた**ことにある。
 * 値を変えるだけだと、次に誰かが「揃えたほうが綺麗」と言った瞬間に戻る。
 * **区別を役割の違いとして持てば、どちらの面がどちらを引いているかを固定できる。**
 *
 * ## 何を見て、何を見ないか
 *
 * **走査は「境界を通っているか」までに限る**（→ docs/dev/lessons/L55.md）。
 * ソースに名前があることは、その面がそう描かれたことの証拠にならない。
 * **見え方そのものは実機検証のケース表が持つ**（`androidTest` へ置いても変異確認ができない
 * → docs/dev/lessons/L53.md）。
 *
 * そのうえで、**値として観測できる部分はJVMへ引き出す** — 2つの役割が本当に別物であることは
 * ここで直接確かめられるので、走査だけに頼らない。
 */
class BearingChannelTest {

    private val bookletScreen = source("main", "ui/screen/BookletScreen.kt")
    private val noteComponents = source("main", "ui/component/NoteComponents.kt")

    /** 紙そのものを描く関数の本体。**縁の代入と混ざらないよう、ここへ絞る。** */
    private val sheetBody = bookletScreen.bodyOf("private fun BookletSheet(")

    /** 縁を描く関数の本体。 */
    private val edgeBody = bookletScreen.bodyOf("private fun BoxScope.StackEdge(")

    /** 本文パネルを描く関数の本体。 */
    private val notePanelBody = noteComponents.bodyOf("internal fun NoteContentPanel(")

    /**
     * **これが本体の受け入れ条件。** 走査ではなく値で見る。
     *
     * 「2つの役割を用意した」だけでは足りず、**中身が違うこと**まで見ないと、
     * 両方を同じ値にする変更が素通りする。
     */
    @Test
    fun `眺める面と読む面は違う形を持つ`() {
        assertNotEquals(
            "「眺める面」と「読む面」が同じ形になっています。形は面の役割を表す唯一のチャネルなので、" +
                "同値にすると区別が消えます（→ docs/dev/system/bearing_channels.md 判断1）。",
            ReadingSurfaceShape,
            BrowsingSheetShape
        )
    }

    /**
     * **眺める面はほぼ直角。** 断ち切った紙とUIカードを形だけで分けるための要で、
     * 丸めるほど読む面へ寄る。
     *
     * **0dp にはしない** — 完全な直角だと画面の隅と見分けが付かず、面が置かれていること自体が消える。
     */
    @Test
    fun `眺める面の角は読む面より小さく、しかし0ではない`() {
        val browsing = cornerPx(BrowsingSheetShape as RoundedCornerShape)
        val reading = cornerPx(ReadingSurfaceShape as RoundedCornerShape)

        assertTrue(
            "眺める面（$browsing）が読む面（$reading）より丸くなっています。" +
                "断ち切った紙のほうが鋭いことが区別の中身です。",
            browsing < reading
        )
        assertTrue(
            "眺める面が完全な直角です。画面の隅と見分けが付かず、面が置かれていること自体が消えます。",
            browsing > 0f
        )
    }

    /**
     * 冊子の紙が「眺める面」の役割を通ること。**通らなければ、そもそも区別が始まらない。**
     *
     * **`shape` への代入で見る。** 名前の存在だけを見た版は、代入を直書きへ戻しても
     * import 行で緑のまま通った。
     */
    @Test
    fun `冊子の紙は眺める面の役割を引く`() {
        assertTrue(
            "冊子の紙が `shape = BrowsingSheetShape` を代入していません。" +
                "紙の形を直に書くと、区別が値の一致で消えます。",
            sheetBody.contains("shape = BrowsingSheetShape")
        )
    }

    /** 本文パネルが「読む面」の役割を通ること。**同じく代入で見る。** */
    @Test
    fun `本文パネルは読む面の役割を引く`() {
        assertTrue(
            "本文パネルが `shape = ReadingSurfaceShape` を代入していません。",
            notePanelBody.contains("shape = ReadingSurfaceShape")
        )
    }

    /**
     * **2つの面が役割を取り違えていないこと。**
     *
     * 上の2本は「参照している」しか見ないので、**両方が両方を参照していても通る**。
     * 取り違えと重複参照はここで落とす。
     */
    @Test
    fun `2つの面は互いの役割を引かない`() {
        assertTrue(
            "BookletScreen が読む面の役割（ReadingSurfaceShape）を参照しています。" +
                "冊子は眺める面です。",
            !bookletScreen.contains("ReadingSurfaceShape")
        )
        assertTrue(
            "NoteComponents が眺める面の役割（BrowsingSheetShape）を参照しています。" +
                "本文パネルは読む面です。",
            !noteComponents.contains("BrowsingSheetShape")
        )
    }

    /**
     * **束の縁は明暗で分岐しない。**
     *
     * `panelRow` は明暗どちらでも `panel` より暗い唯一の既存の面トークンで、
     * だから `if (dark) ... else ...` を書かずに「沈む」を表せる。
     * **分岐が生えたら、それは明暗で値をコピーし始めた合図**である
     * （→ docs/dev/system/ui_design_principles.md §2）。
     */
    @Test
    fun `束の縁は既存の面トークンを1つだけ引く`() {
        assertTrue(
            "束の縁が `color = PanelRow` を代入していません。新しい色トークンを作ると、" +
                "コントラスト検査に面が増えます（→ bearing_channels 判断1）。",
            edgeBody.contains("color = PanelRow")
        )
        assertTrue(
            "束の縁に明暗の分岐が入っています。役割トークン1つで表せるはずです。",
            !bookletScreen.contains("DarkAppColors") && !bookletScreen.contains("LightAppColors")
        )
        // **縁も紙と同じ形を引く。** 同じ束の紙なので、角だけ丸いと背後だけ別素材に見える。
        assertTrue(
            "束の縁が `shape = BrowsingSheetShape` を代入していません。",
            edgeBody.contains("shape = BrowsingSheetShape")
        )
    }

    /**
     * **縁が実際に描かれる経路にあること。**
     *
     * 関数が定義してあることは、呼ばれていることの証拠にならない。
     * 呼び出しだけを外す変異は、**関数と import を残したままビルドも検査も通った**
     * （外部レビュー `P2-2`）。定義（`StackEdge(offset: Dp)`）と呼び出し（`StackEdge(offset = …)`）は
     * 引数の書き方で見分ける。
     */
    @Test
    fun `束の縁は紙から呼ばれている`() {
        assertTrue(
            "BookletSheet から StackEdge が呼ばれていません。" +
                "関数が残っていても、呼ばれなければ縁は描かれません。",
            sheetBody.contains("StackEdge(offset = ")
        )
    }

    /**
     * **縁の有無はページ位置から決めない。**
     *
     * 束の紙かどうか（種別）で決める。最後の1枚で縁が消えると、
     * それは**残数を形で数え始めた**ことになり、判断9 が避けた 1.4.1 の側へ入る。
     * 外部レビュー `P2-1` は位置で決める形を提案したが、**判断9 と衝突するため採らない。**
     */
    @Test
    fun `縁の有無はページ位置ではなく種別で決まる`() {
        assertTrue(
            "束の紙が `isBundleSheet = true` を定数で渡していません。" +
                "ページ位置から導出すると、残数を形で数えることになります。",
            bookletScreen.contains("BookletSheet(isBundleSheet = true)")
        )
        assertTrue(
            "束の紙でないページが `isBundleSheet = false` を渡していません。",
            bookletScreen.contains("BookletSheet(isBundleSheet = false)")
        )
    }

    /** 密度1で解決して比べる。**値そのものではなく順序を見る**（→ docs/dev/lessons/L44.md）。 */
    private fun cornerPx(shape: RoundedCornerShape): Float =
        shape.topStart.toPx(Size(100f, 100f), Density(1f))

    /**
     * **コメントと import を落としてから走査する。**
     *
     * 落とさない版は、変異確認で2度素通りした。
     * 1度目はコメント — 冊子の `shape` を読む面の役割へすり替えても、
     * KDocに名前が残っていたので緑のままだった。
     * 2度目は import — **代入を直書きへ戻しても import 行だけで名前は残る**。
     * しかも Kotlin も Android Lint も未使用 import を報告しないので、
     * **その変異はビルドを通したうえで検査も通る**（外部レビュー `P2-2` が指摘）。
     *
     * **走査テストは文字列しか見ないので、名前の在処が実装の証拠に化ける。**
     * だから受理条件は「名前があること」ではなく**代入と呼び出しの形**で書く。
     */
    /**
     * 関数1つ分の本体を切り出す。**同じ役割を複数の面が引くので、ファイル単位では足りない。**
     *
     * 紙の `shape` を直書きへ戻す変異は、**縁が同じ役割を引いているせいで
     * ファイル全体の走査では捕まらなかった**（レビュー `P2-2` を直した後も残っていた穴）。
     * どの面の代入かまで見ないと、受理条件が「誰かが引いている」に緩む。
     *
     * 次の `@Composable` までを本体とみなす。**厳密な構文解析はしない** —
     * 走査の役割は境界の固定までで、それ以上を背負わせない（→ docs/dev/lessons/L55.md）。
     */
    private fun String.bodyOf(signature: String): String {
        val start = indexOf(signature)
        require(start >= 0) { "走査対象の関数が見つかりません: $signature" }
        // **行頭の `@Composable` で切る。** 素の "@Composable" だと、
        // `content: @Composable () -> Unit` という引数自身に当たって本体が空になる。
        val next = indexOf("\n@Composable", start + signature.length)
        return if (next < 0) substring(start) else substring(start, next)
    }

    private fun source(sourceSet: String, path: String): String {
        val file = File("src/$sourceSet/java/com/example/newproject/$path")
        require(file.exists()) { "走査対象が見つかりません: ${file.path}" }
        return file.readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("""//[^\n]*"""), " ")
            .lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .joinToString("\n")
    }
}
