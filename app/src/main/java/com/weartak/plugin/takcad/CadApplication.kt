// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.app.Application
import java.io.File

class CadApplication : Application() {
    lateinit var runtime: CadRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = CadRuntime(this, IncidentStore(File(filesDir, "incidents.json")), SecretStore(this))
        runtime.restore()
    }
}
