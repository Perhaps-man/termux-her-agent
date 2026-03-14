package com.termux.app
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference
class HerAccessibilityService : AccessibilityService() {

    companion object {
        var instance: HerAccessibilityService? = null
    }


    override fun onServiceConnected() {
        super.onServiceConnected()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            packageNames = null
        }
        instance = this

        serviceInfo = info
        Log.d("TestPoint", "Her无障碍服务 已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }
    override fun onInterrupt() {}


    // --- 封装手势挂起函数 ---
    suspend fun clickAwait(x: Float, y: Float) = suspendCancellableCoroutine<Unit> { cont ->
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { cont.resume(Unit) {} }
            override fun onCancelled(gestureDescription: GestureDescription?) { cont.resume(Unit) {} }
        }, null)
    }

    suspend fun swipeAwait(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 200) = suspendCancellableCoroutine<Unit> { cont ->
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { cont.resume(Unit) {} }
            override fun onCancelled(gestureDescription: GestureDescription?) { cont.resume(Unit) {} }
        }, null)
    }

    suspend fun inputTextAwait(text: String) = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext
        val bundle = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        // 尝试找焦点输入框
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        }
        delay(200)
    }
}
