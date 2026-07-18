package com.example.newproject.domain

// AI応答の1行からノートタイトルを取り出すための整形。
// 箇条書き記号・連番・引用符・[linked] マーカーなど、モデルが付けがちな装飾を剥がす。
// RelatedNotesUseCase / SearchPickerUseCase で共用（以前は同一実装が重複していた）。
internal fun String.cleanAiTitle(): String =
    trim()
        .removePrefix("-")
        .removeSuffix("[linked]")
        .replace(Regex("^\\d+[.)]\\s*"), "")
        .trim('"')
        .trim()
