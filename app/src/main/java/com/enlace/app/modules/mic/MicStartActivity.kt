package com.enlace.app.modules.mic

import android.app.Activity
import android.os.Bundle

/** Invisible: asks for the microphone permission if needed, starts the service, leaves. */
class MicStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = MicModule.missingPermissions(this)
        if (needed.isEmpty()) {
            MicService.start(this)
            finish()
        } else {
            requestPermissions(needed, MicModule.PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        MicModule.onPermissions(this)
        finish()
    }
}
