package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

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

        val fullBackup = readRes("backup_rules.xml")
        val extraction = readRes("data_extraction_rules.xml")
        val targets = mapOf(
            "backup_rules.xml" to fullBackup,
            "data_extraction_rules.xml の <cloud-backup>" to section(extraction, "cloud-backup"),
            "data_extraction_rules.xml の <device-transfer>" to section(extraction, "device-transfer")
        )

        val violations = prefsNames.flatMap { name ->
            val exclude = """<exclude domain="sharedpref" path="$name.xml" />"""
            targets.filterValues { !it.contains(exclude) }.keys.map { "$name: $it に除外が無い" }
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
                    else -> constantValue(argument.substringAfterLast('.'), sources.values)
                }
                if (literal == null) unresolved += "${file.name}: $argument"
                literal
            }.filterNotNull().toList()
        }.toSet()

        assertTrue(
            "prefs 名を解決できませんでした。文字列リテラルか `const val` で書いてください " +
                "（解決できないと、この検査はその置き場を見ないまま緑になります）:\n" +
                unresolved.joinToString("\n"),
            unresolved.isEmpty()
        )
        return names
    }

    private fun constantValue(simpleName: String, sources: Collection<String>): String? {
        val declaration = Regex("const val\\s+" + Regex.escape(simpleName) + "\\s*=\\s*\"([^\"]+)\"")
        return sources.firstNotNullOfOrNull { text ->
            declaration.find(text)?.groupValues?.get(1)
        }
    }

    private fun section(xml: String, tag: String): String =
        xml.substringAfter("<$tag>", "").substringBefore("</$tag>", "")

    private fun readRes(fileName: String): String =
        repositoryRoot().resolve("app/src/main/res/xml/$fileName").also {
            assertTrue("バックアップ規則が見つかりません: $it", it.isFile)
        }.readText()

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

    private companion object {
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
