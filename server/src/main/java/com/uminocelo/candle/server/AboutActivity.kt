package com.uminocelo.candle.server

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Version, licence and third-party credits, from the LICENSE and THIRD_PARTY.md bundled as assets. */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            text = aboutText(
                getString(R.string.app_name),
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                asset("THIRD_PARTY.md"),
                asset("LICENSE"),
            )
            setTextColor(getColor(R.color.text))
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(text) })
    }

    private fun asset(name: String) = assets.open(name).bufferedReader().use { it.readText() }
}
