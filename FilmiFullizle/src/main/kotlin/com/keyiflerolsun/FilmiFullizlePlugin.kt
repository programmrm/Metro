package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FilmiFullizlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmiFullizle())
    }
}
