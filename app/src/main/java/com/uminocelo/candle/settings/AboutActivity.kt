package com.uminocelo.candle.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import com.uminocelo.candle.R

/** Version, licence and third-party credits, from the LICENSE and THIRD_PARTY.md bundled as assets. Up/Down scroll. */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = resources.getDimensionPixelSize(R.dimen.screen_padding_horizontal)
        val text = TextView(this).apply {
            text = aboutText(
                getString(R.string.app_name),
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                asset("THIRD_PARTY.md"),
                asset("LICENSE"),
            )
            setTextColor(getColor(R.color.text))
            textSize = 15f
            setPadding(pad, pad, pad, pad)
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(getColor(R.color.background))
                isFocusable = true
                addView(text)
                requestFocus()
            },
        )
    }

    private fun asset(name: String) = assets.open(name).bufferedReader().use { it.readText() }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AboutActivity::class.java)
    }
}
