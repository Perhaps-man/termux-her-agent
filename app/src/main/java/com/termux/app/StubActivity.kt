package com.termux.app

import android.app.Activity
import android.os.Bundle

/**
 * 占位 Activity，由 Hook 与 Instrumentation 替换为插件 Activity 实例。
 */
class StubActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
