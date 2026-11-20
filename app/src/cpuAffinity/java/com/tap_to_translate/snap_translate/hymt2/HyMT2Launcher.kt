package com.tap_to_translate.snap_translate.hymt2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Opens the SAF picker once when Hy-MT2 is first used without a stored model URI. */
object HyMT2Launcher {
    private const val TAG = "TapTranslateHyMT2"
    private const val EXTRA_AUTO_PICK = "hymt2_auto_pick"
    @Volatile private var launched = false

    @JvmStatic
    fun openIfModelMissing(context: Context) {
        Log.i(TAG, "openIfModelMissing() entered")
        if (HyMT2Runtime.hasModel(context)) {
            Log.i(TAG, "A model URI or legacy model is already stored; picker not opened")
            return
        }
        synchronized(this) {
            if (launched) {
                Log.i(TAG, "Picker launch already pending")
                return
            }
            launched = true
        }
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val intent = Intent(context, HyMT2SettingsActivity::class.java).apply {
                    putExtra(EXTRA_AUTO_PICK, true)
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "HyMT2SettingsActivity startActivity() requested")
            } catch (t: Throwable) {
                Log.e(TAG, "Could not start HyMT2SettingsActivity", t)
                resetPickerLaunch()
            }
        }, 200L)
        Log.i(TAG, "Picker launch scheduled")
    }

    @JvmStatic
    fun resetPickerLaunch() {
        synchronized(this) { launched = false }
    }
}
