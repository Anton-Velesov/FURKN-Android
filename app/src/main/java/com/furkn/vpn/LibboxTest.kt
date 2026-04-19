package com.furkn.vpn

import android.util.Log
import io.nekohasekai.libbox.Libbox

object LibboxTest {

    fun test() {
        try {
            val version = Libbox.version()
            Log.d("FURKN_LIBBOX", "Libbox version: $version")
        } catch (e: Throwable) {
            Log.e("FURKN_LIBBOX", "Libbox error: ${e.message}", e)
        }
    }
}