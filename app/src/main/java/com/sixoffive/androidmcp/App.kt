package com.sixoffive.androidmcp

import android.app.Application
import com.sixoffive.androidmcp.core.ConfigStore
import com.sixoffive.androidmcp.core.TokenStore
import com.sixoffive.androidmcp.server.McpService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        TokenStore.init(this)
        McpService.ensureChannel(this)
    }
}
