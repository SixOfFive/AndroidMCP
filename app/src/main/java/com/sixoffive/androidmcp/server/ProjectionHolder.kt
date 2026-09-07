package com.sixoffive.androidmcp.server

import android.media.projection.MediaProjection
import kotlinx.coroutines.flow.MutableStateFlow

/** Holds the live MediaProjection granted by the user (screen capture consent). */
object ProjectionHolder {
    @Volatile
    var projection: MediaProjection? = null
        private set

    val active = MutableStateFlow(false)

    fun set(p: MediaProjection?) {
        projection = p
        active.value = p != null
    }

    fun stop() {
        runCatching { projection?.stop() }
        projection = null
        active.value = false
    }
}
