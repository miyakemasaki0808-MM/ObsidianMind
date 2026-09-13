package com.example.newproject.testing

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.blocksToMarkdown
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.domain.splitIntoSentences
import com.example.newproject.domain.stripFrontmatter
import com.example.newproject.domain.stripMarkdownMarkers
import com.example.newproject.domain.withoutFencedCode

// 要約が原文のどこを落としたかを機械的に測る計測器（→ docs/dev/system/ai_quality_measurement.md）。
//
// **AIは使わない。** 照合そのものを生成にやらせると、検査が被検査と同じ弱点を持つ。
// 語の重なり（内容語の包含率）だけで測り、判断はしない。
//
// **`debug` ソースセットに置く。** 本番経路から呼ばないものを `main` に置くと、
// リリースへ載るだけでなく、入力サイズに比例するMarkdown解析を本番から呼べる場所が1つ増え、
// 「最大1MBの解析をMainから呼ばない」を守る走査（`NoteSectionThreadingTest` など）の面を広げる。

/** 要約1文が、原文にどれだけ支えられ、どの節の話として数えられたか。 */
data class SummarySentenceSupport(
    val sentence: String,
    /** その文の内容語のうち、**原文全体**に在るものの割合（0.0〜1.0）。 */
    val support: Double,
    /** [support] が閾値に届いたか。届かなければ「裏付けが弱い」。 */
    val supported: Boolean,
    /**
     * 被覆に数えた節の見出し。**裏付けと割り当ては別の判定である** —
     * 裏付けを得ていても、一致した先が計測対象外の節や見出し前の本文なら null になる。
     */
    val assignedSection: String?
)

/**
 * 原文セクション1つ分の被覆。**同名の見出しがありうるので、識別は位置で行う**
 * （このリストの順序が原文の出現順）。見出し前の本文はここに含めない（被覆は見出し単位）。
 */
data class SectionCoverage(
    val title: String,
    val level: Int,
    /** 見出しの直下に本文を持ち、材料が [MIN_SECTION_SIGNATURE] 以上あって、測る対象になったか。 */
    val measured: Boolean,
    /** いずれかの要約文がこの節へ割り当てられたか。 */
    val touched: Boolean
)

data class SummaryCoverageReport(
    val sections: List<SectionCoverage>,
    val sentences: List<SummarySentenceSupport>
) {
    /** 測る対象でありながら、どの要約文からも割り当てられなかった見出し。 */
    val untouchedSections: List<String>
        get() = sections.filter { it.measured && !it.touched }.map { it.title }

    /** 原文に裏付けを見つけられなかった要約文。**割り当ての有無とは無関係に決まる。** */
    val unsupportedSentences: List<String>
        get() = sentences.filter { !it.supported }.map { it.sentence }
}

/**
 * 要約と原文を突き合わせ、**未反映のセクション**と**裏付けの弱い要約文**を出す。
 *
 * ## どう測っているか
 *
 * 素の文字bigramでは測れなかった。**助詞と語尾のbigramが値を作ってしまい**、
 * 原文に無いことを書いた文が0.400、原文に忠実な文が0.211という逆転が実測で出た
 * （2026-09-13、固定コーパス）。そこで2つ変えてある。
 *
 * 1. **内容語だけを見る。** ひらがなを除いた連続（漢字・カタカナ・英数）を語とみなし、
 *    2文字以上の語はさらに文字bigramへ割る。`写真展` と `写真` が繋がるようにするためで、
 *    語をそのまま照合すると表記の差で落ちる
 * 2. **裏付けは原文全体に対して測る。** 要約の1文は複数セクションをまたいで書かれるので、
 *    単一セクションとの一致で測ると、**正しく統合した文ほど低く出る**。
 *    材料は**前処理した原文全体**から作る — 見出し配下だけから作ると、先頭の見出しより前に
 *    書かれた本文が裏付けから消える（2026-09-13 の机上レビューで再現された）
 *
 * ## 被覆の数え方
 *
 * 裏付けを得た文を、原文の**区間**（見出し前の本文・各見出しの直下の本文）のうち
 * 最も一致する1つへ割り当てる。割り当て先が**計測対象の節**のときだけ、その節を触れたと数える。
 *
 * - **一致が0の区間へは割り当てない。** 「最大の区間を必ず選ぶ」形だと、どこにも根拠が無い文が
 *   先頭の節を触れたことにし、本当に落ちた節を未反映一覧から消す
 * - **計測対象外の区間に一致した文は、どの節も触れない。** 短い節を写しただけの文が、
 *   無関係な長い節を被覆済みにしない
 * - **区間は見出しから次の見出し（深さを問わない）までのブロックで切る。** 親の節から子の節の
 *   語集合を差し引く形だと、親と子で共有する語が親から消え、本文を持つ親が計測対象から外れる
 *
 * ## 測っていないもの
 *
 * - **コードフェンスの中身**。前処理で落としている（→ [withoutFencedCode]）。
 *   手順がコードにしか書かれていないノートでは、落としても検出できない
 * - **見出しを持たない材料**。見出しが1つも無いノートは全体を1セクションとして扱うので、
 *   「どこを落としたか」は出ない。見出し前の本文も被覆の対象にしない（裏付けにだけ使う）
 * - **意味**。語が重なっていれば支えられていると見なす。言い換えた正しい要約は低く出て、
 *   原文の語を並べただけの要約は高く出る。**実機の Nano の出力では、忠実な文と誤りの文を
 *   閾値で分離できなかった**（→ `SummaryCoverageCalibrationTest`）
 * - **複数セクションを1文へ統合した要約の被覆**。割り当ては最良の1つだけなので、
 *   3つのセクションをまとめた1文は1つぶんとしか数えない（残り2つは「落とした」側に出る）
 * - **裏付けを得られなかった文の被覆**。言い換えで閾値を割った忠実な文は、どの節にも数えられない
 * - **英語の要約の文分割**。[splitIntoSentences] の終止符に `.` は入っていないので、
 *   英語の要約は行単位で1文として扱われる（本アプリは単一言語前提）
 * - **内容語を持たない文**。ひらがなだけの文は材料が無いので、裏付け0として出る
 */
fun measureSummaryCoverage(
    content: String,
    summary: String,
    threshold: Double = SUMMARY_SUPPORT_THRESHOLD
): SummaryCoverageReport {
    val outline = noteOutline(content)
    val noteSignature = plainSignature(content)

    // **割り当ては1回で決めて持ち回る。** 位置を後から引き直す形にすると、
    // 同点の解き方が2箇所に分かれ、割り当てた先と数えた先が食い違いうる。
    val assessed = summarySentences(summary).map { sentence ->
        val terms = contentSignature(sentence)
        val support = containment(terms, noteSignature)
        val supported = support >= threshold
        val region = if (!supported) null else bestRegion(terms, outline.regions)
        val section = region?.sectionIndex?.let(outline.sections::get)?.takeIf { it.measured }
        SummarySentenceSupport(sentence, support, supported, section?.title) to section?.index
    }

    // 見出し名ではなく位置で数える。同名の見出しが2つあるとき、名前で数えると
    // 片方を触っただけで両方が触れられたことになる。
    val touchedIndices = assessed.mapNotNull { (_, index) -> index }.toSet()

    return SummaryCoverageReport(
        sections = outline.sections.map { section ->
            SectionCoverage(
                title = section.title,
                level = section.level,
                measured = section.measured,
                touched = section.index in touchedIndices
            )
        },
        sentences = assessed.map { (support, _) -> support }
    )
}

/**
 * 最も一致する区間。**一致が0なら null**（根拠の無い割り当てをしない）。
 * 同点は先に現れた区間が勝つ。
 */
private fun bestRegion(terms: Set<String>, regions: List<Region>): Region? {
    val best = regions.maxByOrNull { containment(terms, it.signature) } ?: return null
    return best.takeIf { containment(terms, it.signature) > 0.0 }
}

private class NoteOutline(val sections: List<Section>, val regions: List<Region>)

/** 見出し1つ。[index] は [NoteOutline.sections] の中の位置。 */
private class Section(val index: Int, val title: String, val level: Int, val measured: Boolean)

/**
 * 割り当ての候補になる本文の区間。[sectionIndex] が null なら見出し前の本文で、
 * どの節の被覆にも数えない。
 */
private class Region(val sectionIndex: Int?, val signature: Set<String>)

/**
 * 原文を区間に切る。**区間はブロックの位置で切り、語集合の差では切らない。**
 *
 * 見出しの区間は「その見出し 〜 次の見出し（深さを問わない）の直前」なので、
 * 親の区間には親の直下の本文だけが入る。親と子に同じ語があっても親から消えない。
 */
private fun noteOutline(content: String): NoteOutline {
    val blocks = parseMarkdownBlocks(content)
    val headingPositions = blocks.indices.filter { blocks[it] is MarkdownBlock.Heading }
    if (headingPositions.isEmpty()) {
        val signature = plainSignature(content)
        return NoteOutline(
            sections = listOf(Section(0, WHOLE_NOTE_TITLE, 0, signature.size >= MIN_SECTION_SIGNATURE)),
            regions = listOf(Region(0, signature))
        )
    }

    val regions = mutableListOf<Region>()
    val preamble = blocks.subList(0, headingPositions.first())
    if (preamble.isNotEmpty()) regions += Region(null, plainSignature(blocksToMarkdown(preamble)))

    val sections = headingPositions.mapIndexed { index, position ->
        val end = headingPositions.getOrNull(index + 1) ?: blocks.size
        val heading = blocks[position] as MarkdownBlock.Heading
        val body = blocks.subList(position + 1, end)
        val signature = plainSignature(blocksToMarkdown(blocks.subList(position, end)))
        regions += Region(index, signature)
        Section(
            index = index,
            title = heading.text.trim(),
            level = heading.level,
            // **本文を持たない見出しは測らない。** 見出しの語だけで下限を超える長い見出しでも、
            // 直下に本文が無ければ「落とした」とは言えない。
            measured = plainSignature(blocksToMarkdown(body)).isNotEmpty() &&
                signature.size >= MIN_SECTION_SIGNATURE
        )
    }
    return NoteOutline(sections, regions)
}

private fun summarySentences(summary: String): List<String> =
    plainLines(summary)
        .flatMap { line -> splitIntoSentences(line).asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

private fun plainSignature(markdown: String): Set<String> =
    contentSignature(plainLines(markdown).joinToString(" "))

/** フェンス・frontmatter・記法を落として、比較できる素の行にする。 */
private fun plainLines(markdown: String): Sequence<String> =
    withoutFencedCode(stripFrontmatter(markdown))
        .lineSequence()
        .map { it.stripMarkdownMarkers() }
        .filter { it.isNotBlank() }

/**
 * 照合に使う材料。**ひらがなを含まない連続**を語とみなし、2文字以上はさらに文字bigramへ割る。
 *
 * ひらがなを外すのは、助詞と語尾が最も頻出する文字だからである
 * （素のbigramでは、この2つだけで捏造文が0.4に達した）。
 * bigramへ割るのは、`写真展` と `写真` のように**語の切れ目が一致しない**ことが普通にあるためで、
 * 語のまま照合すると表記の差だけで落ちる。1文字の語はそのまま材料にする。
 */
private fun contentSignature(text: String): Set<String> {
    val signature = LinkedHashSet<String>()
    var run = StringBuilder()
    fun flush() {
        val term = run.toString()
        run = StringBuilder()
        when {
            term.isEmpty() -> Unit
            term.length == 1 -> signature += term
            else -> for (index in 0 until term.lastIndex) signature += term.substring(index, index + 2)
        }
    }
    text.lowercase().forEach { char ->
        if (char.isContentChar()) run.append(char) else flush()
    }
    flush()
    return signature
}

/** ひらがな以外の文字と数字。長音符（`ー`）は Lm なのでカタカナ語に繋がったまま残る。 */
private fun Char.isContentChar(): Boolean = isLetterOrDigit() && code !in HIRAGANA_RANGE

/** a のうち b に含まれる割合。**b の大きさでは割らない**（→ [measureSummaryCoverage]）。 */
private fun containment(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty()) return 0.0
    return a.count { it in b }.toDouble() / a.size
}

private val HIRAGANA_RANGE = 0x3041..0x309F

/** 見出しが1つも無いノートの、唯一のセクション名。 */
const val WHOLE_NOTE_TITLE = "（見出しなし）"

/**
 * 要約文が原文に支えられていると見なす包含率の下限。
 *
 * **人が書いた参照要約で決めた値である。** 固定コーパス（9ノート）に人が書いた忠実な要約36文と
 * 原文に無いことだけを書いた18文を添え、実測した分布の中間を採った。動かせば
 * `SummaryCoverageCalibrationTest` が落ちる。
 *
 * **実機の Nano の出力には移らない。** Nano は言い換えるので、忠実な文でもこの値を割る
 * （数値は `SummaryCoverageCalibrationTest` と正本が持つ）。したがって
 * **「裏付けが弱い」は探索の手がかりであって、合否には使わない。**
 */
const val SUMMARY_SUPPORT_THRESHOLD = 0.65

/**
 * 材料がこれ未満のセクションは測らない。**見出しだけの節（親見出し）を
 * 「落とした」と数えないための下限**で、見出し1行は内容語がせいぜい2〜3語にしかならない。
 */
private const val MIN_SECTION_SIGNATURE = 6
