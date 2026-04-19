package com.furkn.vpn.domain.selector

import android.content.Context

object TransportSelectorRegistry {

    @Volatile
    private var store: TransportScoreStore? = null

    fun init(context: Context) {
        if (store == null) {
            synchronized(this) {
                if (store == null) {
                    store = TransportScoreStore(context.applicationContext)
                }
            }
        }
    }

    val scoreStore: TransportScoreStore
        get() = requireNotNull(store) {
            "TransportSelectorRegistry is not initialized"
        }
}