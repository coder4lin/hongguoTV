// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

enum class ContentType(val storedValue: String, val label: String, val genre: String, val scene: String) {
    COMIC("comic","漫剧","comic_series","comic_series"),
    SHORT("short","短剧","short_play","default"),
    AI("ai","AI剧","ai_series","ai_series");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: COMIC
    }
}
