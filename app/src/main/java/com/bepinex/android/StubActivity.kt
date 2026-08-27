package com.bepinex.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class StubActivity : Activity() {
    override fun onCreate(bundle: Bundle?) {
        super.onCreate(null)
        startActivity(Intent(this, MainActivity::class.java))
    }
}
