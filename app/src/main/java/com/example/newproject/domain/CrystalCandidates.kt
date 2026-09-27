package com.example.newproject.domain

import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalLimits
import com.example.newproject.model.CrystalMaterial
import com.example.newproject.model.CrystalMaterialLog
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

// ---------------------------------------------------------------------------
// 結晶の材料の控え・候補の選び方・試す条件（→ docs/dev/features/reflect_crystal.md 判断5）。
//
// **判定・送信・記録は、予算で落とした後の同じ集合（渡す候補）で行う。**
// 控え全体で数えると、候補の外に残った未提示の材料を理由に、昨日と同じ6件を渡しうる。
// ---------------------------------------------------------------------------

/**
 * 要約から、AIへ渡す断片を作る。改行と連続する空白は1つへ均す。
 *
 * [CrystalLimits.FRAGMENT_CHARACTERS] を超えたら**文の区切りまで手前へ寄せる**。
 * 区切りが無ければ切って `…` を付ける（サロゲートペアの途中では切らない）。
 */
internal fun crystalFragmentOf(summary: String): String {
    val flat = summary.replace(WHITESPACE_RUN, " ").trim()
    val limit = CrystalLimits.FRAGMENT_CHARACTERS
    if (flat.length <= limit) return flat
    val window = flat.substring(0, limit)
    val lastEnd = window.indexOfLast { it in SENTENCE_ENDS }
    if (lastEnd >= 0) return window.substring(0, lastEnd + 1)
    var cut = limit - 1
    if (Character.isHighSurrogate(flat[cut - 1])) cut--
    return flat.substring(0, cut).trimEnd() + "…"
}

/**
 * 材料の指紋。**相対パス＋断片**の SHA-256。
 *
 * 長さを前置するのは、パスと断片の境目をずらして別の組と同じ指紋にできないようにするため。
 */
internal fun crystalMaterialFingerprint(material: CrystalMaterial): String {
    val path = material.vaultRelativePath
    val payload = "${path.length}:$path${material.fragment}"
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/**
 * 今のノートの材料を控える。**同じノートは1件にまとめ、最後に見た時刻を進める。**
 *
 * 断片が同じなら指紋も同じなので、開き直しは「まだ渡していない材料」を増やさない。
 * 断片が変われば同じ行を新しい断片で置き換え、それは新しい材料になる。
 */
internal fun recordCrystalMaterial(
    log: CrystalMaterialLog,
    vaultRelativePath: String,
    noteTitle: String,
    fragment: String,
    now: Long
): CrystalMaterialLog {
    val entry = CrystalMaterial(vaultRelativePath, noteTitle, fragment, now)
    val others = log.entries
        .filterNot { it.vaultRelativePath == vaultRelativePath }
        .sortedByDescending { it.lastSeenAt }
    return log.copy(entries = (listOf(entry) + others).take(CrystalLimits.MATERIAL_ENTRIES))
}

/**
 * 候補を選ぶ。**今のノートを先頭に、最近見た他のノートを新しい順に**、合わせて最大
 * [CrystalLimits.CANDIDATES] 件。今のノートが控えに無ければ空。
 */
internal fun selectCrystalCandidates(
    log: CrystalMaterialLog,
    currentPath: String
): List<CrystalMaterial> {
    val current = log.entries.firstOrNull { it.vaultRelativePath == currentPath } ?: return emptyList()
    val others = log.entries
        .filterNot { it.vaultRelativePath == currentPath }
        .sortedByDescending { it.lastSeenAt }
        .take(CrystalLimits.CANDIDATES - 1)
    return listOf(current) + others
}

/** 端末の暦日で、今日すでに試したか。候補を作る前の前段にだけ使う。 */
internal fun crystalAttemptedToday(lastAttemptAt: Long?, now: Long, zone: ZoneId): Boolean =
    lastAttemptAt != null && localDate(lastAttemptAt, zone) == localDate(now, zone)

/**
 * 試すか。**[sent] は予算で落とした後の、実際にAIへ渡す候補**でなければならない。
 *
 * 今日まだ試しておらず、渡す候補の中に、まだ渡していない材料が
 * [CrystalLimits.UNPRESENTED_TO_ATTEMPT] 件以上あるときだけ true。
 */
internal fun shouldAttemptCrystal(
    sent: List<CrystalMaterial>,
    log: CrystalMaterialLog,
    now: Long,
    zone: ZoneId
): Boolean {
    if (crystalAttemptedToday(log.lastAttemptAt, now, zone)) return false
    if (sent.size < CrystalLimits.MIN_SOURCES) return false
    val presented = log.presented.toHashSet()
    return sent.count { crystalMaterialFingerprint(it) !in presented } >= CrystalLimits.UNPRESENTED_TO_ATTEMPT
}

/**
 * 試行を記録する。**[sent] と同じ集合の指紋を記録する** — 選ばれなかった候補も含める
 * （渡した組で結晶ができなかったなら、同じ組をもう一度渡しても同じ結果になる）。
 */
internal fun markCrystalAttempted(
    log: CrystalMaterialLog,
    sent: List<CrystalMaterial>,
    now: Long
): CrystalMaterialLog {
    val fingerprints = sent.map(::crystalMaterialFingerprint)
    val history = (log.presented.filterNot { it in fingerprints } + fingerprints)
        .takeLast(CrystalLimits.PRESENTED_HISTORY)
    return log.copy(lastAttemptAt = now, presented = history)
}

/** 結晶の並び。新しい順。同じ時刻ならファイル名の逆順で決定的に並べる。 */
internal val CRYSTAL_NEWEST_FIRST: Comparator<Crystal> =
    compareByDescending<Crystal> { it.createdAt }.thenByDescending { it.fileName }

/** 今のノートを根拠に含む結晶。新しい順に最大 [CrystalLimits.SHOWN_PER_NOTE] 件。 */
internal fun crystalsForNote(crystals: List<Crystal>, vaultRelativePath: String?): List<Crystal> {
    if (vaultRelativePath.isNullOrBlank()) return emptyList()
    return crystals
        .filter { crystal -> crystal.sources.any { it.vaultRelativePath == vaultRelativePath } }
        .sortedWith(CRYSTAL_NEWEST_FIRST)
        .take(CrystalLimits.SHOWN_PER_NOTE)
}

/** 2つの一覧を重ねる。**同じファイルは1件にする**（読み込み中に自分で足した結晶を落とさないため）。 */
internal fun mergeCrystals(first: List<Crystal>, second: List<Crystal>): List<Crystal> =
    (first + second).distinctBy { it.fileName }.sortedWith(CRYSTAL_NEWEST_FIRST)

private fun localDate(epochMillis: Long, zone: ZoneId) =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

private val WHITESPACE_RUN = Regex("""\s+""")
private const val SENTENCE_ENDS = "。！？!?"
