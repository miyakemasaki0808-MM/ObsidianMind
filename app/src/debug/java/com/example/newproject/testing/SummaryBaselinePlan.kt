package com.example.newproject.testing

// 実機で要約の基準線を採るときの「何を生成し、何を再利用するか」の計画
// （→ docs/dev/system/ai_quality_measurement.md 判断8）。
//
// **端末を使わずに決まることは、ここで決めてJVMで検査する。** 実機で気づくと
// セッションが1回無駄になる（2026-09-13、27回のうち12回が同じプロンプトの再生成だった）。

/** 実機で採る1組。[key] は `<variant>/<note>`。 */
class SummaryBaselineItem(
    val variant: SummaryExcerptVariant,
    /** 固定コーパスのファイル名から `.md` を除いたもの。 */
    val note: String,
    val prompt: String
) {
    val key: String get() = "${variant.key}/$note"
}

/**
 * 採る組の並びと、同じプロンプトの組をまとめた結果。
 *
 * **生成は決定的である**（SDK の既定値が `temperature=0`・`seed=0`、実機でも同じプロンプトは
 * 同じ要約を返した）。したがって同じプロンプトを2回投げても情報は増えず、AICore の枠だけを使う。
 * **同じプロンプトの組は、この計画の中で先に現れた組の応答を使い回す。**
 * 使い回しは計画に書き出すので、表を読む人から隠れない。
 */
class SummaryBaselinePlan(val items: List<SummaryBaselineItem>) {

    /** 実際に生成する組。同じプロンプトのうち最初に現れたもの。 */
    val generations: List<SummaryBaselineItem>
        get() = items.distinctBy { it.prompt }

    /** その組の応答をどの組から採るか。自分で生成するなら自分自身。 */
    fun sourceOf(item: SummaryBaselineItem): SummaryBaselineItem =
        items.first { it.prompt == item.prompt }

    /**
     * **全変種でプロンプトが同じノート。** 変種どうしの比較には使えない（入力が同じなので出力も同じ）。
     * 計画に含まれる変種が1つだけなら、どのノートもここに入らない。
     */
    val identicalInputNotes: List<String>
        get() = notesWhere { prompts -> prompts.size > 1 && prompts.distinct().size == 1 }

    /** **変種によってプロンプトが変わるノート。** 比べるならこれだけを、ノートごとに対応させて比べる。 */
    val comparableNotes: List<String>
        get() = notesWhere { prompts -> prompts.distinct().size > 1 }

    private fun notesWhere(predicate: (List<String>) -> Boolean): List<String> =
        items.groupBy { it.note }
            .filterValues { group -> predicate(group.map { it.prompt }) }
            .keys.toList()

    /**
     * 実機の引数で絞り込む。**null か空なら絞らない。** 使い回しは絞った後の計画の中で決め直す
     * （本番を含まない実行では、旧方式の短いノートも自分で生成する）。
     *
     * **知らない名前は黙って捨てず、失敗させる。** 綴りを間違えた実行が
     * 「0件を採って成功」に見えると、途中から再開したつもりで欠落が残る。
     */
    fun select(variantKeys: String?, notes: String?, keys: String? = null): SummaryBaselinePlan {
        val wantedVariants = parseList(variantKeys)
        val wantedNotes = parseList(notes)
        val wantedKeys = parseList(keys)
        // **組の指定と、変種・ノートの指定は混ぜない。** 両方あると交わりか和かで読み違える。
        require(wantedKeys == null || (wantedVariants == null && wantedNotes == null)) {
            "keys と variants / notes は同時に指定できません"
        }
        wantedVariants?.let { requireKnown("変種", it, items.map { item -> item.variant.key }) }
        wantedNotes?.let { requireKnown("ノート", it, items.map { item -> item.note }) }
        wantedKeys?.let { requireKnown("組", it, items.map { item -> item.key }) }

        val selected = items.filter { item ->
            (wantedKeys == null || item.key in wantedKeys) &&
                (wantedVariants == null || item.variant.key in wantedVariants) &&
                (wantedNotes == null || item.note in wantedNotes)
        }
        require(selected.isNotEmpty()) { "絞り込みの結果が0組です（variants=$variantKeys, notes=$notes, keys=$keys）" }
        return SummaryBaselinePlan(selected)
    }

    /**
     * [failedKey] の組で止まったとき、**続きから採るべき組**。失敗した組そのものと、その後の組。
     *
     * 失敗した組を外すと、再開しても**その組だけが永久に欠ける**
     * （2026-09-13 の机上レビューで、後続だけを再開すると26/27組になることが再現された）。
     * 変種とノートの指定は直積なので任意の残りを表せない — 組をそのまま `-e keys` へ渡す。
     */
    fun resumeKeysAfter(failedKey: String): List<String> {
        val position = items.indexOfFirst { it.key == failedKey }
        require(position >= 0) { "計画に無い組です: $failedKey" }
        return items.drop(position).map { it.key }
    }

    private fun parseList(raw: String?): Set<String>? =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }

    private fun requireKnown(label: String, wanted: Set<String>, known: List<String>) {
        val unknown = wanted - known.toSet()
        require(unknown.isEmpty()) {
            "知らない$label: ${unknown.sorted().joinToString()}（使えるのは ${known.distinct().joinToString()}）"
        }
    }
}

/**
 * 固定コーパスの全ノート × 全変種の計画を作る。並びは**変種の順 → ノートの名前順**で、
 * 本番が先に来るので、同じプロンプトの組は本番の応答を使い回す。
 *
 * @param notes `ファイル名` → 本文。ファイル名は `.md` 付きで渡す。
 */
fun planSummaryBaseline(
    notes: Map<String, String>,
    variants: List<SummaryExcerptVariant> = SUMMARY_EXCERPT_VARIANTS
): SummaryBaselinePlan {
    val sorted = notes.toSortedMap()
    return SummaryBaselinePlan(
        variants.flatMap { variant ->
            sorted.map { (fileName, content) ->
                SummaryBaselineItem(
                    variant = variant,
                    note = fileName.removeSuffix(MARKDOWN_SUFFIX),
                    prompt = variant.buildPrompt(baselineNoteTitle(fileName, content), content)
                )
            }
        }
    )
}

/**
 * プロンプトへ渡すノートのタイトル。**本文の先頭見出しを使う。**
 *
 * 本番は Vault のファイル名から採るが、**固定コーパスのファイル名はASCIIにしてある**
 * （assets の非ASCII名が `AssetManager` を通って返るかを端末で確かめずに済ませるため）。
 * ノートらしい日本語のタイトルは本文の `#` が持っているので、そちらから採る。
 *
 * `0700_no_heading.md` だけは見出しが無いのでファイル名になる。
 * **タイトルがノートらしくない唯一の1本**で、基準線を読むときはそれを承知で読む。
 */
fun baselineNoteTitle(fileName: String, content: String): String =
    content.lineSequence()
        .firstOrNull { it.startsWith("# ") }
        ?.removePrefix("# ")
        ?.trim()
        ?: fileName.removeSuffix(MARKDOWN_SUFFIX)

private const val MARKDOWN_SUFFIX = ".md"
