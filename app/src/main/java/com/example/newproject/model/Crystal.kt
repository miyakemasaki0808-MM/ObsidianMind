package com.example.newproject.model

/**
 * 結晶の根拠1件（→ `docs/dev/features/reflect_crystal.md` §6）。
 *
 * [fragment] は **AIへ渡した断片そのもの**で、後から検算するために残す。
 * [noteTitle] は結晶を作った時点のノート名で、改名・移動には追従しない。
 */
data class CrystalSource(
    val vaultRelativePath: String,
    val noteTitle: String,
    val fragment: String
)

/**
 * 結晶1件。Vault 内に1件1ファイルで置き、**書き換えない**。
 *
 * [fileName] は並べ替えには使わない（端末間で名前が衝突すると SAF が別名にするため）。
 * 同じ結晶を一覧へ2度足さないための鍵にだけ使う。
 */
data class Crystal(
    val createdAt: Long,
    val sentence: String,
    val sources: List<CrystalSource>,
    val fileName: String
)

/**
 * 材料の控え1件。**同一性は「相対パス＋断片」で決まる**（→ 判断5）。
 *
 * [lastSeenAt] は候補の並びにだけ使う。開き直すと進むが、材料の数には効かない。
 */
data class CrystalMaterial(
    val vaultRelativePath: String,
    val noteTitle: String,
    val fragment: String,
    val lastSeenAt: Long
)

/**
 * 端末内に Vault ごとに1つ持つ控え。
 *
 * [presented] はこれまでの試行でAIへ渡した材料の指紋（古い順）。
 * [lastAttemptAt] は暦日の条件にだけ使う。
 */
data class CrystalMaterialLog(
    val lastAttemptAt: Long? = null,
    val entries: List<CrystalMaterial> = emptyList(),
    val presented: List<String> = emptyList()
) {
    companion object {
        val EMPTY = CrystalMaterialLog()
    }
}

/** 今のノートの候補ID。**選択に必ず含まれていなければならない**（→ reflect_crystal.md 判断3）。 */
const val CRYSTAL_CURRENT_ID = "N1"

/** 共通する筋が無いときの応答。 */
const val CRYSTAL_NONE_TOKEN = "NONE"

/** 応答の2行のラベル。プロンプトが書かせ、検証が読む。 */
const val CRYSTAL_SELECTION_LABEL = "選択"
const val CRYSTAL_SENTENCE_LABEL = "結晶"

/** 結晶の上限（→ `docs/dev/features/reflect_crystal.md` §5 の上限表。値の正本はそちら）。 */
object CrystalLimits {
    /** 候補は今のノートを含めて最大6件。 */
    const val CANDIDATES = 6

    /** 候補1件の断片（要約）の上限。UTF-16 文字数。 */
    const val FRAGMENT_CHARACTERS = 240

    /** 根拠は今のノート＋他の1〜3件。 */
    const val MIN_SOURCES = 2
    const val MAX_SOURCES = 4

    /** 結晶の文の長さ（コードポイント数）。 */
    const val SENTENCE_MIN_CHARACTERS = 10
    const val SENTENCE_MAX_CHARACTERS = 120

    /** 控えの件数。使うのは直近5件なので、重複を除いても足りる厚み。 */
    const val MATERIAL_ENTRIES = 12

    /** 渡した材料の指紋の記録。1回最大6件 × 10回分。 */
    const val PRESENTED_HISTORY = 60

    /** 渡す候補の中に、まだ渡していない材料がこの件数以上あるときだけ試す。 */
    const val UNPRESENTED_TO_ATTEMPT = 3

    /** ✨タブに出す、このノートを根拠に含む結晶の件数。 */
    const val SHOWN_PER_NOTE = 3

    /** 結晶1件のファイルの上限。超えるものは書かず、読み込みでも飛ばす。 */
    const val MAX_FILE_BYTES = 16 * 1024
}
