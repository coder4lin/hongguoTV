// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

enum class ContentType(val storedValue: String, val label: String) {
    COMIC("comic","漫剧"),
    SHORT("short","短剧");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: COMIC
    }
}
