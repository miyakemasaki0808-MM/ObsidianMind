package com.example.newproject.architecture

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * **端末に残す置き場を足したら、自動バックアップから除外されていることを数える。**
 *
 * ## なぜ要るか
 *
 * `allowBackup="true"` のまま除外規則で守る形なので、**除外を1つ書き忘れた瞬間に漏れる**。
 * しかも**忘れても何も起きない** — ビルドは通り、テストは緑で、実機でも動く。
 * 気づけるのは端末を移行したときだけで、そのときには既にVaultのSAF URIと
 * 当日の閲覧履歴がクラウドへ出ている。**このリポジトリで最も守られにくい型の規則**なので、
 * 人の注意ではなく走査で数える（→ docs/dev/lessons.md L29）。
 *
 * 今は `random_note_prefs` 1本に寄せてあり、蒸留の復旧レコードは `noBackupFilesDir`、
 * stagingは `cacheDir` にあるので守れている。**危ないのは次に置き場を足すときである。**
 *
 * ## この検査が見ていないもの
 *
 * 覆えるのは [BACKED_UP_ENTRY_POINTS] に挙げたAPIを使ったときだけで、
 * **それ以外の形で永続化を足せば素通りする**。走査は届く範囲しか守らない。
 * 列挙にないAPIを使い始めたら、ここへ足すこと。
 *
 * 除外の中身が正しいか（そのprefsに何が入るか）も見ていない。見るのは
 * **「置き場と除外が対応しているか」だけ**である。
 */
class BackupExclusionTest {

    /**
     * **両方のXMLを見る。** API 30以下は `backup_rules.xml`、31以上は
     * `data_extraction_rules.xml` が使われるので、片方だけ書いても端末によって漏れる。
     * さらに後者は `cloud-backup`（Drive経由）と `device-transfer`（新端末への直接移行）に
     * 分かれており、**3箇所すべてに除外が要る**。
     */
    @Test
    fun `prefs はバックアップの3経路すべてから除外されている`() {
        val prefsNames = declaredPrefsNames()
        require(prefsNames.isNotEmpty()) { "prefs の宣言を1つも見つけられませんでした" }

        val targets = mapOf(
            "backup_rules.xml" to excludedPrefsFiles("backup_rules.xml", null),
            "data_extraction_rules.xml の <cloud-backup>" to
                excludedPrefsFiles("data_extraction_rules.xml", "cloud-backup"),
            "data_extraction_rules.xml の <device-transfer>" to
                excludedPrefsFiles("data_extraction_rules.xml", "device-transfer")
        )

        val violations = prefsNames.flatMap { name ->
            // **保存されるファイル名そのもので照合する。** prefs 名へ寄せて比べると、
            // `path="random_note_prefs"`（拡張子なし＝実在しないファイル）を
            // 正しい除外として受け取ってしまう（2026-09-13 のレビューで再現）。
            targets.filterValues { "$name.xml" !in it }.keys.map { "$name: $it に除外が無い" }
        }.sorted()

        assertTrue(
            "端末に残す prefs を足したら、バックアップの3経路すべてから除外してください " +
                "（忘れても何も起きないので、端末移行まで漏れに気づけません）:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * **バックアップ対象のディレクトリを新しく使い始めていないこと。**
     *
     * `filesDir` などはバックアップの対象に入るので、使うなら除外規則が要る。
     * 現在は1件も使っておらず、蒸留は `noBackupFilesDir`（性質として除外）と
     * `cacheDir`（同）に寄せてある。**その状態を固定する。**
     *
     * 使う必要が出たら、除外を書いたうえで [BACKED_UP_ENTRY_POINTS] から外すのではなく、
     * **除外済みとして許可リストへ登録する**（外すと次の追加をまた見逃す）。
     */
    @Test
    fun `バックアップ対象の置き場を新しく使っていない`() {
        val violations = mainSources().flatMap { file ->
            val text = file.readText()
            BACKED_UP_ENTRY_POINTS.filter { it.pattern.containsMatchIn(text) }
                .map { "${file.name}: ${it.label}" }
        }.sorted()

        assertTrue(
            "バックアップ対象の置き場を使っています。除外規則を書き、この検査の許可リストへ" +
                "登録してください（`noBackupFilesDir` / `cacheDir` なら性質として除外されます）:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * `getSharedPreferences(` の第1引数から prefs 名を解く。
     *
     * **解けなかったら失敗させる。** 名前を変数で組み立てられると走査が届かなくなるが、
     * **届かなくなったことは緑のまま起きる**ので、静かに諦めない。
     */
    private fun declaredPrefsNames(): Set<String> {
        val sources = mainSources().associateWith { it.readText() }
        val unresolved = mutableListOf<String>()
        val names = sources.flatMap { (file, text) ->
            PREFS_CALL.findAll(text).map { match ->
                val argument = match.groupValues[1].trim()
                val literal = when {
                    argument.startsWith("\"") && argument.endsWith("\"") -> argument.trim('"')
                    else -> constantValue(argument, sources)
                }
                if (literal == null) unresolved += "${file.name}: $argument"
                literal
            }.filterNotNull().toList()
        }.toSet()

        assertTrue(
            "prefs 名を解決できませんでした。文字列リテラルで書くか、`const val` の名前を" +
                "一意にしてください（同名の定数が複数あると、どちらを指しているか決まりません）。" +
                "**解決できないまま通すと、この検査はその置き場を見ないまま緑になります**:\n" +
                unresolved.joinToString("\n"),
            unresolved.isEmpty()
        )
        return names
    }

    /**
     * `NewStore.PREFS_NAME` のような参照を実際の文字列へ解く。
     *
     * **最初に見つかった同名の定数を採るのは誤り**だった。修飾部分を捨てていたため、
     * 別の保存先が同じ `PREFS_NAME` という名前を使うと**既存の除外済み prefs と誤認**し、
     * 除外を書かなくても緑になる（2026-09-12 のレビュー）。しかも**どちらが当たるかは
     * ファイル走査の順で変わる**ので、再現したりしなかったりする。
     *
     * **ファイル単位の絞り込みでは足りなかった。** 各ファイルの**先頭の1件**しか候補にせず、
     * 所有者も「そのファイルに型名があるか」でしか見ていなかったため、
     * **同じファイルに `Existing.PREFS_NAME` と `NewStore.PREFS_NAME` を置くと両方が
     * 先頭の値へ解決され**、未除外の保存先を見逃した（2026-09-13 のレビューで再現）。
     *
     * いまは**全宣言を候補に数え、所有する型まで見て**解く。修飾名で1つに決まらなければ
     * **黙って片方を選ばず失敗させる**（選ばれなかった保存先が検査の外に出るため）。
     */
    private fun constantValue(argument: String, sources: Map<File, String>): String? {
        val simpleName = argument.substringAfterLast('.')
        val qualifier = argument.substringBeforeLast('.', "").substringAfterLast('.')
        val declarations = sources.values.flatMap { text -> declarationsOf(simpleName, text) }
        val candidates = when {
            qualifier.isEmpty() -> declarations
            else -> declarations.filter { it.owner == qualifier }
        }
        return candidates.map { it.value }.distinct().singleOrNull()
    }

    /**
     * ファイル内の `const val <simpleName> = "..."` を**全部**拾い、所有者を添える。
     *
     * 所有者は**直前に現れた名前つきの `object` / `class`** とする。`companion object` は
     * 名前を持たないので飛ばされ、それを囲む `class` が所有者になる（`SharedAppPreferences` が実例）。
     */
    private fun declarationsOf(simpleName: String, text: String): List<Declaration> {
        val owners = OWNER.findAll(text).toList()
        val declaration = Regex("const val\\s+" + Regex.escape(simpleName) + "\\s*=\\s*\"([^\"]+)\"")
        return declaration.findAll(text).map { match ->
            val owner = owners.lastOrNull { it.range.last < match.range.first }?.groupValues?.get(1)
            Declaration(owner, match.groupValues[1])
        }.toList()
    }

    /**
     * 除外されている prefs の**ファイル名**を、**XMLとして解析して**集める。
     *
     * **文字列検索では足りなかった。** `<exclude .../>` をコメントで囲んでも
     * `contains` は一致するので、**3経路すべてをコメントアウトしても緑になる**
     * （2026-09-12 のレビューで再現）。コメントは要素にならないので、解析すれば消える。
     *
     * [sectionTag] が null なら文書要素の直下（`backup_rules.xml`）、
     * 指定があればその節の中（`cloud-backup` / `device-transfer`）だけを見る。
     */
    private fun excludedPrefsFiles(fileName: String, sectionTag: String?): Set<String> {
        val file = repositoryRoot().resolve("app/src/main/res/xml/$fileName")
        assertTrue("バックアップ規則が見つかりません: $file", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val scope: Element = when (sectionTag) {
            null -> document.documentElement
            else -> document.documentElement.childElements()
                .firstOrNull { it.tagName == sectionTag }
                ?: return emptySet()
        }
        return scope.getElementsByTagName("exclude").let { nodes ->
            (0 until nodes.length).mapNotNull { index ->
                val element = nodes.item(index) as? Element ?: return@mapNotNull null
                // **`path` は加工せずそのまま返す。** `removeSuffix(".xml")` で prefs 名へ
                // 寄せると、拡張子の有無を区別できなくなる。
                element.takeIf { it.getAttribute("domain") == "sharedpref" }
                    ?.getAttribute("path")
                    ?.takeIf { it.isNotEmpty() }
            }.toSet()
        }
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun mainSources(): List<File> =
        repositoryRoot().resolve("app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return sequenceOf(workingDirectory, workingDirectory.parentFile)
            .filterNotNull()
            .firstOrNull { it.resolve("docs/dev").isDirectory }
            ?: error("リポジトリルートが見つかりません: $workingDirectory")
    }

    private data class EntryPoint(val label: String, val pattern: Regex)

    /** `const val` 1件と、それを囲む名前つきの型。 */
    private data class Declaration(val owner: String?, val value: String)

    private companion object {
        /** 名前つきの型の宣言。`companion object` は名前が無いので当たらない。 */
        val OWNER = Regex("""\b(?:object|class)\s+(\w+)""")

        val PREFS_CALL = Regex("""getSharedPreferences\(\s*([^,)]+)""")

        /**
         * **自動バックアップの対象に入る置き場の入口。**
         * `noBackupFilesDir` と `cacheDir` は性質として除外されるので挙げない。
         * `filesDir` の `\b` は、`noBackupFilesDir` の末尾に当たらないために要る。
         */
        val BACKED_UP_ENTRY_POINTS = listOf(
            EntryPoint("filesDir", Regex("""\bfilesDir\b""")),
            EntryPoint("getFilesDir()", Regex("""\bgetFilesDir\(""")),
            EntryPoint("getDir()", Regex("""\bgetDir\(""")),
            EntryPoint("openFileOutput()", Regex("""\bopenFileOutput\(""")),
            EntryPoint("getDatabasePath()", Regex("""\bgetDatabasePath\(""")),
            EntryPoint("openOrCreateDatabase()", Regex("""\bopenOrCreateDatabase\(""")),
            EntryPoint("getExternalFilesDir()", Regex("""\bgetExternalFilesDir\(""")),
            EntryPoint("Room.databaseBuilder()", Regex("""\bdatabaseBuilder\("""))
        )
    }
}
