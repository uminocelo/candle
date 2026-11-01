package com.uminocelo.candle

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun homeScreenShowsSourceTilesWithTheFirstFocused() {
        // Remote-driven like on the projector: in touch mode tiles cannot take focus.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.root).visibility)
                val sources = activity.findViewById<ViewGroup>(R.id.sources_row)
                assertTrue("expected at least USB, Network and Settings", sources.childCount >= 3)
                assertTrue(sources.getChildAt(0).isFocused)
            }
        }
    }
}
