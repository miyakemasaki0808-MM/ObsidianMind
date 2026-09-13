package com.example.newproject.testing

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **計測器の閾値を、勘ではなく固定コーパスで決める。**
 *
 * ## なぜ要るか
 *
 * [SUMMARY_SUPPORT_THRESHOLD] を適当に置くと、**測っている気になるだけの計測器**ができる。
 * 値が低すぎれば捏造した文まで「裏付けあり」になり、高すぎれば忠実な要約が
 * 「原文に無い」と言われる。どちらの側に倒れても、要約の善し悪しは言えない。
 *
 * そこで固定コーパス（`app/src/androidTest/assets/ai_corpus`）に、**人が書いた忠実な要約**と
 * **原文に無いことだけを書いた要約**を添えてある。閾値はこの2つを分離できる値であり、
 * **動かせばこのテストが落ちる**。
 *
 * ## 較正は2系統ある
 *
 * 1. **人が書いた参照**（`references/` の `good` と `fabricated`）— 閾値はこちらで決めた
 * 2. **実機の Nano が返した要約**（`references/nano/`）— 文ごとに忠実さのラベルを付けてある
 *
 * **2つ目を足したのは、1つ目で決めた閾値が実機の出力に移らなかったためである。**
 * 人が書くと原文の語を使い回すので裏付けが高く出るが、Nano は言い換えるので低く出る。
 * 実機の出力では**忠実な文と誤りの文を閾値で分離できない** — このテストはその事実を固定する。
 * 分離できないので**閾値は動かさず**、「裏付けが弱い」は合否に使わない
 * （→ docs/dev/system/ai_quality_measurement.md 判断7）。
 */
class SummaryCoverageCalibrationTest {

    @Test
    fun `閾値は忠実な要約と捏造した要約を分離する`() {
        val faithful = mutableListOf<Pair<String, Double>>()
        val fabricated = mutableListOf<Pair<String, Double>>()

        FixedCorpus.noteFiles().forEach { note ->
            val content = note.readText()
            measureSummaryCoverage(content, reference(note, "good")).sentences.forEach {
                faithful += "${note.name}: ${it.sentence}" to it.support
            }
            measureSummaryCoverage(content, reference(note, "fabricated")).sentences.forEach {
                fabricated += "${note.name}: ${it.sentence}" to it.support
            }
        }

        val weakestFaithful = faithful.minByOrNull { it.second }
        val strongestFabricated = fabricated.maxByOrNull { it.second }
        requireNotNull(weakestFaithful) { "忠実な要約を1文も読めていません" }
        requireNotNull(strongestFabricated) { "捏造した要約を1文も読めていません" }

        assertTrue(
            "捏造した文が閾値 $SUMMARY_SUPPORT_THRESHOLD を超えています。" +
                "閾値を上げるか、指標そのものを見直してください:\n" +
                "  最も強く出た捏造文 ${format(strongestFabricated)}\n" +
                "  最も弱い忠実文   ${format(weakestFaithful)}",
            strongestFabricated.second < SUMMARY_SUPPORT_THRESHOLD
        )
        assertTrue(
            "忠実な文が閾値 $SUMMARY_SUPPORT_THRESHOLD に届いていません。" +
                "閾値を下げるか、指標そのものを見直してください:\n" +
                "  最も弱い忠実文   ${format(weakestFaithful)}\n" +
                "  最も強く出た捏造文 ${format(strongestFabricated)}",
            weakestFaithful.second >= SUMMARY_SUPPORT_THRESHOLD
        )
    }

    /**
     * **分離しているだけでは足りない。** 両者が閾値の両側にあっても、その間隔が
     * 小数第2位しか無ければ、コーパスを1本足しただけで裏返る。
     * 間隔を明示的に持たせ、**痩せたら気づく**ようにする。
     */
    @Test
    fun `忠実と捏造のあいだに間隔がある`() {
        val faithful = FixedCorpus.noteFiles().flatMap { note ->
            measureSummaryCoverage(note.readText(), reference(note, "good")).sentences.map { it.support }
        }
        val fabricated = FixedCorpus.noteFiles().flatMap { note ->
            measureSummaryCoverage(note.readText(), reference(note, "fabricated")).sentences.map { it.support }
        }

        val margin = faithful.min() - fabricated.max()
        assertTrue(
            "忠実（最小 ${"%.3f".format(faithful.min())}）と捏造（最大 ${"%.3f".format(fabricated.max())}）の" +
                "間隔が $MINIMUM_MARGIN を下回りました（実測 ${"%.3f".format(margin)}）。" +
                "閾値を動かす前に、指標が効いているかを見てください",
            margin >= MINIMUM_MARGIN
        )
    }

    /**
     * セクション被覆のほうも効いていることを確かめる。**要約から1文落とせば、
     * その文だけが触れていたセクションが「落とした」側へ回る**はずである。
     *
     * 短いノート（測る対象のセクションが1つしか無い）はこの性質を持たないので対象外。
     */
    @Test
    fun `要約から1文落とすと被覆するセクションが減る`() {
        val failures = FixedCorpus.noteFiles().mapNotNull { note ->
            val content = note.readText()
            val full = measureSummaryCoverage(content, reference(note, "good"))
            val covered = full.sections.count { it.touched }
            if (covered < 2) return@mapNotNull null

            val sentences = full.sentences.map { it.sentence }
            val droppedCoverage = sentences.indices.map { index ->
                val remaining = sentences.filterIndexed { i, _ -> i != index }.joinToString("")
                measureSummaryCoverage(content, remaining).sections.count { it.touched }
            }
            if (droppedCoverage.any { it < covered }) null
            else "${note.name}: $covered セクションを被覆しているのに、" +
                "どの1文を落としても被覆が減りませんでした（$droppedCoverage）"
        }

        assertTrue(
            "セクション被覆が要約の欠落に反応していません:\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }

    /**
     * **ラベルが採点器の文と1対1で対応していることを先に固定する。**
     *
     * ラベルは `index` で採点器の `sentences` と照合する（採点器は記法を落とすので文字列では合わない）。
     * 採点器の文の割り方が変われば、ラベルは黙って別の文を指すようになる。
     * **ずれたラベルで較正した結論は、何も言っていない。**
     */
    @Test
    fun `実機出力のラベルは採点器の文と1対1で対応する`() {
        val problems = mutableListOf<String>()
        val labels = nanoLabels()
        val outputs = nanoOutputs()

        labels.filter { it.label !in LABELS }.forEach {
            problems += "${it.key}#${it.index}: 未知のラベル `${it.label}`"
        }
        (outputs.keys - labels.map { it.key }.toSet()).forEach { problems += "$it: ラベルが1件も無い" }
        (labels.map { it.key }.toSet() - outputs.keys).forEach { problems += "$it: 出力ファイルが無い" }

        labels.groupBy { it.key }.forEach { (key, rows) ->
            val output = outputs[key] ?: return@forEach
            val sentences = measureSummaryCoverage(noteContent(key), output.readText()).sentences
            val indices = rows.map { it.index }.sorted()
            if (indices != (1..sentences.size).toList()) {
                problems += "$key: ラベルの index $indices が採点器の文数 ${sentences.size} と対応しない"
            }
        }

        assertTrue("実機出力のラベルが採点器とずれています:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    /**
     * **実機の出力では、忠実な文と誤りの文を閾値で分離できない。** その事実を固定する。
     *
     * 否定の結果をわざわざ検査にするのは、正本と [SUMMARY_SUPPORT_THRESHOLD] の説明が
     * 「分離できないので合否に使わない」を前提に書かれているからである。**採点器を直して分離できるように
     * なったら、このテストが落ちて、その前提を見直す番だと知らせる。** 黙って古い説明が残るより良い。
     *
     * 一部誤り（`partial`）と主張でない文（`not_a_claim`）は数えない — 1文に正誤が混ざるものを
     * 片側に寄せると、分離の判定そのものが恣意的になる。
     */
    @Test
    fun `実機出力では忠実な文と誤りの文を閾値で分離できない`() {
        val supports = nanoSupportsByLabel()
        val faithful = supports.getValue(FAITHFUL)
        val unfaithful = supports.getValue(UNFAITHFUL)

        val weakestFaithful = faithful.minBy { it.second }
        val strongestUnfaithful = unfaithful.maxBy { it.second }
        assertTrue(
            "実機出力で忠実な文と誤りの文が分離できるようになりました。" +
                "正本の判断7と SUMMARY_SUPPORT_THRESHOLD の説明（合否に使わない）を見直してください:\n" +
                "  最も弱い忠実文   ${format(weakestFaithful)}\n" +
                "  最も強い誤りの文 ${format(strongestUnfaithful)}",
            weakestFaithful.second < strongestUnfaithful.second
        )
    }

    private data class NanoLabel(val note: String, val variant: String, val index: Int, val label: String) {
        val key: String get() = "$note.$variant"
    }

    private fun nanoLabels(): List<NanoLabel> {
        val file = FixedCorpus.root().resolve("references/nano/labels.tsv")
        check(file.isFile) { "実機出力のラベルがありません: ${file.path}" }
        val lines = file.readLines().filter { it.isNotBlank() }
        check(lines.first().startsWith("note\tvariant\tindex\tlabel\t")) { "labels.tsv の見出し行が想定と違います" }
        return lines.drop(1).map { line ->
            val cells = line.split('\t')
            check(cells.size == LABEL_COLUMNS) { "labels.tsv の列数が ${cells.size} です: $line" }
            NanoLabel(cells[0], cells[1], cells[2].toInt(), cells[3])
        }
    }

    /** `<note>.<variant>` → 出力ファイル。 */
    private fun nanoOutputs(): Map<String, File> =
        FixedCorpus.root().resolve("references/nano").listFiles()
            ?.filter { it.isFile && it.extension == "txt" }
            ?.associateBy { it.nameWithoutExtension }
            .orEmpty()

    private fun noteContent(key: String): String =
        FixedCorpus.root().resolve("${key.substringBefore('.')}.md").readText()

    private fun nanoSupportsByLabel(): Map<String, List<Pair<String, Double>>> {
        val outputs = nanoOutputs()
        return nanoLabels().groupBy { it.key }.flatMap { (key, rows) ->
            val sentences = measureSummaryCoverage(noteContent(key), outputs.getValue(key).readText()).sentences
            rows.map { row -> row.label to ("$key#${row.index}" to sentences[row.index - 1].support) }
        }.groupBy({ it.first }, { it.second })
    }

    private fun format(entry: Pair<String, Double>): String =
        "${"%.3f".format(entry.second)}  ${entry.first}"

    private fun reference(note: File, kind: String): String {
        val file = File(note.parentFile, "references/${note.nameWithoutExtension}.$kind.txt")
        check(file.isFile) { "参照要約がありません: ${file.path}" }
        return file.readText()
    }

    private companion object {
        /** 忠実な文の最小と捏造文の最大のあいだに、最低限あってほしい間隔。 */
        const val MINIMUM_MARGIN = 0.10

        const val FAITHFUL = "faithful"
        const val UNFAITHFUL = "unfaithful"
        val LABELS = setOf(FAITHFUL, "partial", UNFAITHFUL, "not_a_claim")
        const val LABEL_COLUMNS = 7
    }
}
