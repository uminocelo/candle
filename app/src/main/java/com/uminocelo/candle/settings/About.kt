package com.uminocelo.candle.settings

/** Where the source lives, as the GPL asks the About screen to say. */
const val SOURCE_URL = "https://github.com/uminocelo/candle"

/** The About screen's text: the app and version, where its source is, then the bundled THIRD_PARTY.md and LICENSE. */
fun aboutText(appName: String, version: String, thirdParty: String, licence: String): String =
    "$appName $version\nSource: $SOURCE_URL\n\n${thirdParty.trim()}\n\n${licence.trim()}\n"
