package com.example.newproject.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 退避ファイルの既定のファイル名。
 *
 * **64文字未満であることが安全条件になっている。** 痕跡の置き場（`_ReadingTraces/`）の
 * 索引はフォルダ内の**全ファイルの先頭64文字をキーとして解釈**し、
 * 64文字に満たない名前は索引に載せない。したがって既定名のまま `_ReadingTraces/` へ
 * 保存されても、退避ファイルが痕跡として索引に載って孤児スキャンの削除候補に出ることはない。
 * **長い名前へ変えるときはこの前提が消える**ので、`ReadingTraceBackupFileNameTest` が固定する。
 *
 * 日付だけで秒までは入れない。同じ日に2回書き出すと保存先のピッカーが
 * 「(1)」を付けるか上書きを尋ねるので、どちらもユーザーが決められる。
 */
internal fun readingTraceBackupFileName(
    atEpochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val date = Instant.ofEpochMilli(atEpochMillis).atZone(zone).toLocalDate()
    return "vigilith_traces_${DATE_FORMAT.format(date)}.json"
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
