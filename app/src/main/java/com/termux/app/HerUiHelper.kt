package com.termux.app

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.ViewCompat
import com.termux.R

/**
 * 宿主提供的 UI 工具，供插件 Activity 使用。
 * AI 生成的 Activity 必须通过本类添加对话按钮，不得自行实现或修改该按钮。
 */
object HerUiHelper {

    private const val BUTTON_SIZE_DP = 40
    private const val BUTTON_MARGIN_DP = 16

    /**
     * 创建左上角圆形描边的对话按钮，点击跳转到 SimpleExecutorActivity。
     * 插件 Activity 在 onCreate 中必须调用此方法添加按钮，不得用其他方式实现或修改对话按钮。
     *
     * @param context 一般为 Activity（this）
     * @return 已设置好样式和点击事件的 ImageButton，需由调用方添加到布局（如左上角）
     */
    @JvmStatic
    fun createChatButton(context: Context): ImageButton {
        val btn = ImageButton(context)
        btn.setBackgroundResource(R.drawable.bg_btn_input_circle)
        btn.setImageResource(R.drawable.ic_chat)
        btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
        btn.setColorFilter(0xFF6B7280.toInt())
        btn.contentDescription = "对话"
        val sizePx = dpToPx(context, BUTTON_SIZE_DP)
        btn.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        ViewCompat.setElevation(btn, dpToPx(context, 2).toFloat())
        btn.setOnClickListener {
            context.startActivity(Intent(context, SimpleExecutorActivity::class.java))
        }
        return btn
    }

    /** 供插件布局时使用：按钮建议外边距（dp 转 px） */
    @JvmStatic
    fun getChatButtonMarginPx(context: Context): Int = dpToPx(context, BUTTON_MARGIN_DP)

    /** 与对话按钮同款的圆形描边按钮，仅设图标与描述，点击由调用方设置（如打开抽屉） */
    @JvmStatic
    fun createCircleIconButton(context: Context, iconResId: Int, contentDescription: String): ImageButton {
        val btn = ImageButton(context)
        btn.setBackgroundResource(R.drawable.bg_btn_input_circle)
        btn.setImageResource(iconResId)
        btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
        btn.setColorFilter(0xFF6B7280.toInt())
        btn.contentDescription = contentDescription
        val sizePx = dpToPx(context, BUTTON_SIZE_DP)
        btn.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        ViewCompat.setElevation(btn, dpToPx(context, 2).toFloat())
        return btn
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
