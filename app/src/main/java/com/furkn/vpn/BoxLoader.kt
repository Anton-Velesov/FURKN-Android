package com.furkn.vpn

import android.util.Log

object BoxLoader {

    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true

        return try {
            System.loadLibrary("box")
            loaded = true
            Log.d("FURKN_BOX", "libbox loaded")
            true
        } catch (e: Throwable) {
            Log.e("FURKN_BOX", "failed to load libbox: ${e.message}", e)
            false
        }
    }
}