package com.tap_to_translate.snap_translate.hymt2

import android.util.Log
import android.view.View
import android.view.WindowManager
import java.util.Collections
import java.util.WeakHashMap

/** Temporarily lets manual-selection drags pass through the full-screen result overlay. */
object ManualSelectionTouchMode {
    private const val TAG = "ManualSelectionTouch"
    private val originalFlags = Collections.synchronizedMap(WeakHashMap<View, Int>())

    @JvmStatic
    fun setUntouchable(view: View?, windowManager: WindowManager?) {
        setTouchable(view, windowManager, false)
    }

    @JvmStatic
    fun restoreTouchable(view: View?, windowManager: WindowManager?) {
        setTouchable(view, windowManager, true)
    }

    @JvmStatic
    fun setTouchable(view: View?, windowManager: WindowManager?, touchable: Boolean) {
        if (view == null || windowManager == null) return
        try {
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            val oldFlags = params.flags
            val newFlags: Int
            if (touchable) {
                // Restore the exact flags that were present before selection mode,
                // rather than assuming FLAG_NOT_TOUCHABLE was originally clear.
                newFlags = originalFlags[view] ?: (oldFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv())
            } else {
                if (!originalFlags.containsKey(view)) originalFlags[view] = oldFlags
                newFlags = oldFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            if (newFlags != oldFlags) {
                params.flags = newFlags
                windowManager.updateViewLayout(view, params)
            }
            if (touchable) originalFlags.remove(view)
            Log.d(TAG, "overlay touchable=" + touchable + " flags=0x" + Integer.toHexString(newFlags))
        } catch (t: Throwable) {
            Log.e(TAG, "Could not update selection overlay touch routing", t)
        }
    }
}
