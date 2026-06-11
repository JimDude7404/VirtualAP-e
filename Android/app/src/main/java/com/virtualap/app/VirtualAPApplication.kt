package com.virtualap.app

import android.app.Application
import com.topjohnwu.superuser.Shell

class VirtualAPApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30)
        )
    }
}
