package com.example.newproject.domain

import com.example.newproject.domain.markdown.CodeTokenKind
import com.example.newproject.domain.markdown.CodeTokenKind.Comment
import com.example.newproject.domain.markdown.CodeTokenKind.Keyword
import com.example.newproject.domain.markdown.CodeTokenKind.Number
import com.example.newproject.domain.markdown.CodeTokenKind.StringLiteral
import com.example.newproject.domain.markdown.codeTokensOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** コードの色分けの切れ目（→ `docs/dev/features/note_typesetting.md` §5.4・判断15）。 */
class CodeTokensTest {

    /** 切れ目を「文字列→種類」の並びにする。位置の数え違いは、取り出した文字列のずれとして出る。 */
    private fun cut(language: String?, code: String): List<Pair<String, CodeTokenKind>> =
        codeTokensOf(language, code).map { code.substring(it.start, it.end) to it.kind }

    @Test
    fun `Python はキーワード・文字列・コメント・数字を切り分ける`() {
        assertEquals(
            listOf("for" to Keyword, "in" to Keyword, "1" to Number, "f\"{i}\"".drop(1) to StringLiteral, "# 番号" to Comment),
            cut("python", "for i in enumerate(items, start=1): print(f\"{i}\")  # 番号")
        )
    }

    @Test
    fun `Kotlin は行と囲みのコメントと文字列を切り分ける`() {
        assertEquals(
            listOf("val" to Keyword, "\"a\\\"b\"" to StringLiteral, "// 行" to Comment, "/* 囲み */" to Comment, "true" to Keyword),
            cut("kotlin", "val s = \"a\\\"b\" // 行\n/* 囲み */ true")
        )
    }

    @Test
    fun `シェルの # は語の頭でだけコメントで、一重引用符の中はエスケープしない`() {
        assertEquals(
            listOf("alias" to Keyword, "'git status'" to StringLiteral, "# 短く" to Comment),
            cut("zsh", "alias gs='git status' # 短く")
        )
        assertEquals(emptyList<Pair<String, CodeTokenKind>>(), cut("sh", "echo ${'$'}{#arr} a#b"))
        assertEquals(listOf("'a\\'" to StringLiteral), cut("bash", "x='a\\'"))
    }

    @Test
    fun `語の途中の数字とキーワードの一部は切らない`() {
        assertEquals(emptyList<Pair<String, CodeTokenKind>>(), cut("python", "item2 = format_in"))
        assertEquals(listOf("0x1F" to Number, "3.14" to Number), cut("python", "a = 0x1F + 3.14"))
    }

    @Test
    fun `三重の引用符は複数行にまたがり、閉じていない文字列とコメントでも落ちない`() {
        val doc = "s = \"\"\"1行目\n2行目\"\"\"\nx = 1"
        assertEquals(listOf("\"\"\"1行目\n2行目\"\"\"" to StringLiteral, "1" to Number), cut("python", doc))
        // 一重の文字列は行の末尾で打ち切り、次の行は普通に切る。
        assertEquals(listOf("\"開いたまま" to StringLiteral, "return" to Keyword), cut("python", "\"開いたまま\nreturn"))
        assertEquals(listOf("/* 閉じない" to Comment), cut("kotlin", "/* 閉じない"))
        assertEquals(listOf("\"\"\"閉じない\nまま" to StringLiteral), cut("kotlin", "\"\"\"閉じない\nまま"))
    }

    @Test
    fun `言語の札は大小を見ず、知らない言語と札の無い枠は色分けしない`() {
        assertTrue(cut("Python", "def f(): pass").isNotEmpty())
        assertEquals(emptyList<Pair<String, CodeTokenKind>>(), cut("rust", "fn main() {}"))
        assertEquals(emptyList<Pair<String, CodeTokenKind>>(), cut(null, "def f(): pass"))
    }
}
