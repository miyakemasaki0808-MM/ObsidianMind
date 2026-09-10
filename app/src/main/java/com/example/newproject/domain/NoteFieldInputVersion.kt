package com.example.newproject.domain

import com.example.newproject.model.NOTE_FIELD_VOCABULARY_VERSION
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.NoteField
import com.example.newproject.model.promptId
import java.security.MessageDigest

/**
 * 分野判定の**入力版（指紋）**（→ `docs/dev/features/note_field_color.md` 判断9）。
 *
 * ## 何を含めるか
 *
 * **AIへ実際に渡したものすべて。** 本文ハッシュだけでは足りない —
 * 判断6 でヒントをプロンプトへ添え、判断7 でVault別の語彙を使うので、
 * **本文が同じでも入力は違い得る**。同じ本文を別フォルダに複製すれば別のヒントが付く。
 *
 * | 含めるもの | 落とすと起きること |
 * |---|---|
 * | 本文（の抜粋そのもの） | 本文を書き換えても古い結果が出続ける |
 * | 抜粋の予算・省略の有無 | 予算を変えても再判定されない |
 * | ヒント | 移動して別のヒントが付いても再判定されない |
 * | 語彙の版 | **リストを更新しても古い結果が出続ける**（最も気づきにくい） |
 * | プロンプト版 | プロンプトを直しても古い結果が出続ける |
 *
 * **Vaultの名前空間は含めない。** 分野IDはVaultローカルだが、
 * **名前空間は索引Bの置き場が持つ**（→ 判断9）。ここへ混ぜると、
 * 同じ本文・同じ設定でもVaultが違うだけで再判定になり、キャッシュの意味が薄れる。
 *
 * **機能種別も含めない。** この関数が分野判定専用で、他の機能と同じ表を共有しないためである。
 * 表を共有し始めたら、そのとき種別を足す。
 *
 * ## なぜ抜粋そのものを混ぜるのか
 *
 * 本文全体のハッシュではなく**プロンプトへ載る抜粋**を混ぜる。
 * 抜粋は見出し骨格＋冒頭＋末尾なので、**本文の真ん中だけを直したときは抜粋が変わらない** —
 * そのとき再判定しないのは正しい（AIへ渡るものが変わっていない）。
 * 本文ハッシュにすると、**AIの入力が同じなのに再判定する**ことになる。
 */
fun noteFieldInputVersion(excerpt: NoteExcerpt, hint: NoteField?): String {
    val material = buildString {
        append("p").append(NOTE_FIELD_PROMPT_VERSION)
        append(":v").append(NOTE_FIELD_VOCABULARY_VERSION)
        append(":b").append(NoteExcerptLimits.FIELD)
        append(":a").append(if (excerpt.isAbridged) 1 else 0)
        append(":h").append(hint?.promptId ?: "-")
        append(":x").append(excerpt.text)
    }
    return sha256Hex(material.toByteArray(Charsets.UTF_8))
}

/**
 * プロンプト版。**`buildNoteFieldPrompt` の文面を変えたら上げる。**
 *
 * 上げ忘れると、プロンプトを直しても**古い結果が出続ける**。
 * 語彙の版（`NOTE_FIELD_VOCABULARY_VERSION`）とは別に持つのは、
 * **どちらか一方だけが動くことが実際にある**ため（文面の推敲と、候補の増減）。
 */
const val NOTE_FIELD_PROMPT_VERSION = 1

/**
 * 永続の索引Aの鍵。**相対パスのハッシュ**（→ 判断10）。
 *
 * 参照（`DocumentRef`）ではなくパスを使うのは、**参照が端末やセッションをまたいで
 * 安定するとは限らない**ため。`docs/dev/features/reflect_reading_trace.md` の痕跡サイドカーが
 * 同じ形で、**改名・移動で見失うのも同じ**である。
 * ただし痕跡と違い、こちらは**作り直せる導出値**なので、見失っても次に開けば戻る。
 */
fun noteFieldPathKey(vaultRelativePath: String): String =
    sha256Hex(vaultRelativePath.toByteArray(Charsets.UTF_8))

/**
 * `data` の同名関数と重複しているが、**`domain` は `data` を import できない**
 * （→ `docs/dev/system/architecture.md` 判断5）。`MessageDigest` は素のJVMなので、
 * 層の規約には触れない。**共有したくなったら `model` へ降ろす**のが筋である。
 */
private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
