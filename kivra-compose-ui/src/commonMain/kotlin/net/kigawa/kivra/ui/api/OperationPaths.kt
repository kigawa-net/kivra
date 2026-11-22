package net.kigawa.kivra.ui.api

import net.kigawa.kivra.ui.percentEncode

/** parentId 未指定は親なしの一覧。フィルタリングは API 側で行う。 */
fun operationsPath(parentId: String? = null): String =
    "/api/operations" + (parentId?.let { "?parentId=${percentEncode(it)}" } ?: "")
