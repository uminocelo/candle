package com.uminocelo.candle.resume

import android.content.Context

private const val RESUME_PREFS = "resume"

/** The app-wide resume store shared by the home screen and the player. */
fun Context.resumeStore(): ResumeStore =
    PrefsResumeStore(getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE))
