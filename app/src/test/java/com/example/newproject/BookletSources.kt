package com.example.newproject

import java.io.File

/**
 * 冊子の本番ソース一式（`ui/screen/Booklet*.kt`）。ソースを走査する検査が共有する。
 *
 * **ファイル名で決め打ちしない。** 「無いこと」を確かめる走査を1ファイルへ当てると、
 * コードが別ファイルへ移った時点で黙って通るようになる。冊子の画面を分けても、足しても、ここが拾う。
 */
internal fun bookletSources(): List<File> {
    val dir = File("src/main/java/com/example/newproject/ui/screen")
    val files = dir.listFiles { file -> file.name.startsWith("Booklet") && file.extension == "kt" }
        ?.sortedBy { it.name }
        .orEmpty()
    check(files.any { it.name == "BookletScreen.kt" }) { "冊子のソースが見つかりません: ${dir.path}" }
    return files
}
