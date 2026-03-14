// HerApp.kt
package com.termux.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

private fun hookInstrumentation() {
    try {
        val atClass = Class.forName("android.app.ActivityThread")
        val curMethod = atClass.getDeclaredMethod("currentActivityThread")
        curMethod.isAccessible = true
        val at = curMethod.invoke(null)

        val f = atClass.getDeclaredField("mInstrumentation")
        f.isAccessible = true
        val base = f.get(at) as Instrumentation

        f.set(at, HookedInstrumentation(base))

        Log.d("TestPoint", "✅ Instrumentation hooked (newActivity swap ready)")
    } catch (t: Throwable) {
        Log.e("TestPoint", "❌ hookInstrumentation failed", t)
    }
}

class HookedInstrumentation(private val base: Instrumentation) : Instrumentation() {

    companion object {
        private const val TAG = "TestPoint"
    }

    private var mExec7: Method? = null
    private var mExec8: Method? = null

    @Suppress("unused")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): Any? {
        val fixed = rewriteIntentIfPlugin(who, intent)
        return invokeBaseExecStartActivity7(who, contextThread, token, target, fixed, requestCode, options)
    }

    @Suppress("unused")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
        resolvedType: String?
    ): Any? {
        val fixed = rewriteIntentIfPlugin(who, intent)
        return invokeBaseExecStartActivity8(who, contextThread, token, target, fixed, requestCode, options, resolvedType)
    }

    private fun rewriteIntentIfPlugin(ctx: Context, src: Intent): Intent {
        if (src.component?.className == StartActivityHook.STUB_CLASS) return src

        val targetClass = src.component?.className ?: return src
        val runtime = DexLoaderRegistry.getRuntime(targetClass) ?: return src

        Log.w(TAG, "🛟 execStartActivity redirect -> $targetClass")

        return Intent(src).apply {
            component = ComponentName(ctx.packageName, StartActivityHook.STUB_CLASS)
            putExtra(StartActivityHook.EXTRA_TARGET_CLASS, targetClass)
            putExtra(StartActivityHook.EXTRA_ORIG_INTENT, src)
        }
    }

    private fun invokeBaseExecStartActivity7(
        who: Context,
        contextThread: IBinder,
        token: IBinder,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): Any? {
        if (mExec7 == null) {
            mExec7 = Instrumentation::class.java.getDeclaredMethod(
                "execStartActivity",
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                Activity::class.java,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java
            ).apply { isAccessible = true }
        }
        return mExec7!!.invoke(base, who, contextThread, token, target, intent, requestCode, options)
    }

    private fun invokeBaseExecStartActivity8(
        who: Context,
        contextThread: IBinder,
        token: IBinder,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
        resolvedType: String?
    ): Any? {
        if (mExec8 == null) {
            mExec8 = Instrumentation::class.java.getDeclaredMethod(
                "execStartActivity",
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                Activity::class.java,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java,
                String::class.java
            ).apply { isAccessible = true }
        }
        return mExec8!!.invoke(base, who, contextThread, token, target, intent, requestCode, options, resolvedType)
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity {
        if (className == StartActivityHook.STUB_CLASS) {
            val target = intent.getStringExtra(StartActivityHook.EXTRA_TARGET_CLASS)
            if (!target.isNullOrBlank()) {
                val runtime = DexLoaderRegistry.getRuntime(target)
                    ?: return base.newActivity(cl, className, intent)

                return base.newActivity(runtime.classLoader, target, intent)
            }
        }
        return base.newActivity(cl, className, intent)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        base.callActivityOnCreate(activity, icicle)
    }
}

class HerApp : TermuxApplication() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        StartActivityHook.install(this)
        hookInstrumentation()
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) {
                HerToolbarInjector.maybeInject(activity)
            }
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    companion object {
        lateinit var appContext: Context
    }
}
