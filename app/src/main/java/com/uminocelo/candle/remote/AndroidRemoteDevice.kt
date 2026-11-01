package com.uminocelo.candle.remote

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.uminocelo.candle.MainActivity
import com.uminocelo.candle.R

/**
 * Carries out remote commands. Key presses are injected into Candle's own window, which
 * needs no permission; Android refuses (SecurityException) when another app is in front.
 * The PIN overlay and starting the home screen from the background rely on the
 * SYSTEM_ALERT_WINDOW app op that `scripts/deploy.sh` grants.
 */
class AndroidRemoteDevice(private val context: Context) : RemoteDevice {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val instrumentation = Instrumentation()
    private var pinView: TextView? = null

    override fun showPin(pin: String) {
        main.post {
            hidePin()
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, context.getString(R.string.remote_pin, pin), Toast.LENGTH_LONG).show()
                return@post
            }
            val view = TextView(context).apply {
                text = context.getString(R.string.remote_pin, pin)
                textSize = 28f
                setTextColor(context.getColor(R.color.text))
                setBackgroundColor(context.getColor(R.color.background))
                val padding = context.resources.getDimensionPixelSize(R.dimen.tile_gap)
                setPadding(padding, padding, padding, padding)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.END }
            context.getSystemService(WindowManager::class.java).addView(view, params)
            pinView = view
            main.postDelayed(::hidePin, Pairing.PIN_LIFETIME_MS)
        }
    }

    /** Removes the PIN overlay (main thread). */
    fun hidePin() {
        main.removeCallbacksAndMessages(null)
        pinView?.let { runCatching { context.getSystemService(WindowManager::class.java).removeView(it) } }
        pinView = null
    }

    override fun pressKey(keyCode: Int): Boolean = try {
        instrumentation.sendKeyDownUpSync(keyCode)
        true
    } catch (e: SecurityException) {
        false
    }

    override fun mediaKey(keyCode: Int) {
        val time = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0))
    }

    override fun volume(command: VolumeCommand) {
        val direction = when (command) {
            VolumeCommand.UP -> AudioManager.ADJUST_RAISE
            VolumeCommand.DOWN -> AudioManager.ADJUST_LOWER
            VolumeCommand.MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    override fun goHome() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}
