package com.tap_to_translate.snap_translate.hymt2

import android.util.Log
import android.view.View
import android.view.WindowManager

/** Temporarily lets manual-selection drags pass through the full-screen result overlay. */
object ManualSelectionTouchMode {
    private const val TAG = "ManualSelectionTouch"

    @JvmStatic
    fun setTouchable(view: View?, windowManager: WindowManager?, touchable: Boolean) {
        if (view == null || windowManager == null) return
        try {
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            val oldFlags = params.flags
            val newFlags = if (touchable) {
                oldFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                oldFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            if (newFlags == oldFlags) return
            params.flags = newFlags
            windowManager.updateViewLayout(view, params)
            Log.e(TAG, "overlay touchable=" + touchable + " flags=0x" + Integer.toHexString(newFlags))
        } catch (t: Throwable) {
            Log.e(TAG, "Could not update selection overlay touch routing", t)
        }
    }
}
