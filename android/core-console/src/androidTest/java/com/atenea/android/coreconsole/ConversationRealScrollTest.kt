package com.atenea.android.coreconsole

import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Android frame scheduling: ComposeTestRule's virtual clock is deliberately not used. */
class ConversationRealScrollTest {
    @Test
    fun realSwipesAndOffscreenPrefetchDoNotCloseConversation() {
        ActivityScenario.launch(ConversationScrollFixtureActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            repeat(20) { index ->
                swipe(up = index >= 10)
                // Give Android's actual idle-frame prefetch scheduler time to compose offscreen items.
                SystemClock.sleep(200)
                instrumentation.waitForIdleSync()
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
            }
            scenario.onActivity { activity ->
                assertEquals("Borrador intacto", activity.draft)
                assertEquals(0, activity.sends)
                assertTrue(activity.window.decorView.isShown)
            }
        }
    }

    private fun swipe(up: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val display = instrumentation.targetContext.resources.displayMetrics
        val x = display.widthPixels / 2f
        val top = display.heightPixels * 0.25f
        val bottom = display.heightPixels * 0.7f
        val start = if (up) bottom else top
        val end = if (up) top else bottom
        val downTime = SystemClock.uptimeMillis()
        for (step in 0..16) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                16 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                x, start + (end - start) * step / 16, 0)
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            SystemClock.sleep(10)
        }
    }
}
