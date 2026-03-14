package com.termux.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.lang.reflect.Proxy

object StartActivityHook {
    private const val TAG = "TestPoint"

    const val EXTRA_TARGET_CLASS = "target_class"
    const val EXTRA_ORIG_INTENT = "orig_intent"

    const val STUB_CLASS = "com.termux.app.StubActivity"

    fun install(appContext: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 29) hookIActivityTaskManager(appContext)
            else hookIActivityManager(appContext)
            Log.d(TAG, "✅ hook installed")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ hook install failed", t)
        }
    }

    private fun hookIActivityTaskManager(ctx: Context) {
        val atmClass = Class.forName("android.app.ActivityTaskManager")
        val singletonField = atmClass.getDeclaredField("IActivityTaskManagerSingleton")
        singletonField.isAccessible = true
        replaceSingletonInstance(ctx, singletonField.get(null), "android.app.IActivityTaskManager")
    }

    private fun hookIActivityManager(ctx: Context) {
        val amClass = Class.forName("android.app.ActivityManager")
        val singletonField = amClass.getDeclaredField("IActivityManagerSingleton")
        singletonField.isAccessible = true
        replaceSingletonInstance(ctx, singletonField.get(null), "android.app.IActivityManager")
    }

    private fun replaceSingletonInstance(ctx: Context, singletonObj: Any, iFaceName: String) {
        val singletonClass = Class.forName("android.util.Singleton")
        val mInstanceField = singletonClass.getDeclaredField("mInstance")
        mInstanceField.isAccessible = true

        var raw = mInstanceField.get(singletonObj)
        if (raw == null) {
            val getMethod = singletonClass.getDeclaredMethod("get")
            getMethod.isAccessible = true
            raw = getMethod.invoke(singletonObj)
        }

        val iFace = Class.forName(iFaceName)
        val proxy = Proxy.newProxyInstance(iFace.classLoader, arrayOf(iFace)) { _, method, args ->
            if (method.name.startsWith("startActivity")) {
                tryRedirect(ctx, args)
            }
            method.invoke(raw, *(args ?: emptyArray()))
        }

        mInstanceField.set(singletonObj, proxy)
        Log.d(TAG, "✅ singleton replaced: $iFaceName")
    }

    private fun tryRedirect(ctx: Context, args: Array<Any?>?) {
        if (args == null) return

        val idx = args.indexOfFirst { it is Intent }
        if (idx < 0) return

        val intent = args[idx] as Intent

        if (intent.component?.className == STUB_CLASS) return

        var targetClass = intent.component?.className
        if (targetClass.isNullOrBlank()) return

        val runtime = DexLoaderRegistry.getRuntime(targetClass) ?: return

        Log.w(TAG, "🛟 redirect plugin startActivity -> $targetClass")

        val stubIntent = Intent(intent)
        stubIntent.component = ComponentName(ctx.packageName, STUB_CLASS)
        stubIntent.putExtra(EXTRA_ORIG_INTENT, intent)
        stubIntent.putExtra(EXTRA_TARGET_CLASS, targetClass)

        args[idx] = stubIntent
    }
}
