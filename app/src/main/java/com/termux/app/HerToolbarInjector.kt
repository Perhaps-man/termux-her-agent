package com.termux.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.termux.R

/**
 * 仅对「作为启动界面的 Activity」注入左上角「对话」「更新日志」按钮及抽屉：
 * - 宿主 LaunchActivity（无插件时的启动页）
 * - 插件入口 Activity（有插件时替代启动的界面）
 * 其他 Activity（如对话页、设置等）不注入。
 */
object HerToolbarInjector {

    private const val OVERLAY_TAG = "her_toolbar_overlay"

    /** 仅插件入口 Activity（替代启动的界面）需要 overlay；LaunchActivity 继承 HerToolbarActivity 自带按钮。 */
    private fun isLaunchScreenActivity(activity: Activity): Boolean {
        return getPluginEntryActivityClassName(activity) == activity.javaClass.name
    }

    fun maybeInject(activity: Activity) {
        if (activity is HerToolbarActivity) return
        if (!isLaunchScreenActivity(activity)) return
        val content = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(OVERLAY_TAG) != null) return

        val density = activity.resources.displayMetrics.density
        val marginPx = HerUiHelper.getChatButtonMarginPx(activity)
        val buttonSizePx = (40 * density).toInt()
        val gapPx = (8 * density).toInt()

        val overlay = FrameLayout(activity).apply {
            tag = OVERLAY_TAG
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        val chatBtn = HerUiHelper.createChatButton(activity)
        (chatBtn.layoutParams as? FrameLayout.LayoutParams)?.apply {
            leftMargin = marginPx
            topMargin = marginPx
        }
        overlay.addView(chatBtn)

        val popup = PopupWindow(activity).apply {
            width = (280 * density).toInt()
            height = ViewGroup.LayoutParams.MATCH_PARENT
            isFocusable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        }
        popup.contentView = buildDrawerContent(activity) { popup.dismiss() }

        val menuBtn = HerUiHelper.createCircleIconButton(activity, R.drawable.ic_note, "更新日志")
        (menuBtn.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = marginPx + buttonSizePx + gapPx
            topMargin = marginPx
            gravity = Gravity.LEFT or Gravity.TOP
        }
        menuBtn.setOnClickListener { popup.showAtLocation(overlay, Gravity.LEFT or Gravity.TOP, 0, 0) }
        overlay.addView(menuBtn)

        val depStoreBtn = HerUiHelper.createCircleIconButton(activity, R.drawable.ic_extension, "依赖商店")
        (depStoreBtn.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = marginPx + 2 * (buttonSizePx + gapPx)
            topMargin = marginPx
            gravity = Gravity.LEFT or Gravity.TOP
        }
        depStoreBtn.setOnClickListener { activity.startActivity(android.content.Intent(activity, DepStoreActivity::class.java)) }
        overlay.addView(depStoreBtn)

        content.addView(overlay)
    }

    private fun buildDrawerContent(activity: Activity, onDismiss: () -> Unit): View {
        val density = activity.resources.displayMetrics.density
        val inner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
        }
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.WHITE)
            addView(inner)
        }

        val darkText = 0xFF111827.toInt()
        val hintText = 0xFF6B7280.toInt()
        inner.addView(TextView(activity).apply {
            text = "AI 配置"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(darkText)
        })
        val providerSpinner = Spinner(activity).apply {
            val names = AI_PROVIDER_IDS.map { getAiProviderDisplayName(it) }
            adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, names) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.setTextColor(darkText)
                    return v
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getDropDownView(position, convertView, parent) as TextView
                    v.setTextColor(darkText)
                    return v
                }
            }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(AI_PROVIDER_IDS.indexOf(getAiProvider(activity)).coerceAtLeast(0))
        }
        inner.addView(providerSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        val apiKeyInput = EditText(activity).apply {
            hint = "API Key"
            setText(getAiApiKey(activity))
            setTextColor(darkText)
            setHintTextColor(hintText)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            textSize = 14f
        }
        inner.addView(apiKeyInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        val modelInput = EditText(activity).apply {
            hint = "模型（留空用默认）"
            setText(getAiModel(activity))
            setTextColor(darkText)
            setHintTextColor(hintText)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            textSize = 14f
        }
        inner.addView(modelInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        val embedKeyInput = EditText(activity).apply {
            hint = "Embedding Key（Dashscope，必填）"
            setText(getEmbedApiKey(activity))
            setTextColor(darkText)
            setHintTextColor(hintText)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            textSize = 14f
        }
        inner.addView(embedKeyInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        val searchKeyInput = EditText(activity).apply {
            hint = "Search API Key（可选）"
            setText(getSearchApiKey(activity))
            setTextColor(darkText)
            setHintTextColor(hintText)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            textSize = 14f
        }
        inner.addView(searchKeyInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        inner.addView(Button(activity).apply {
            text = "去 DeepSeek 申请 Embedding Key"
            setTextColor(darkText)
            setAllCaps(false)
            setOnClickListener {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys")))
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        inner.addView(Button(activity).apply {
            text = "去秘塔申请 Search API Key"
            setTextColor(darkText)
            setAllCaps(false)
            setOnClickListener {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://metaso.cn/search-api/playground")))
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        inner.addView(Button(activity).apply {
            text = "保存 AI 配置"
            setTextColor(darkText)
            setAllCaps(false)
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            setOnClickListener {
                val embedKey = embedKeyInput.text.toString().trim()
                if (embedKey.isBlank()) {
                    Toast.makeText(activity, "请填写 Embedding Key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                setAiProvider(activity, AI_PROVIDER_IDS[providerSpinner.selectedItemPosition])
                setAiApiKey(activity, apiKeyInput.text.toString().trim())
                setAiModel(activity, modelInput.text.toString().trim())
                setEmbedApiKey(activity, embedKey)
                setSearchApiKey(activity, searchKeyInput.text.toString().trim())
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })

        inner.addView(View(activity).apply { setBackgroundColor(0xFFE5E7EB.toInt()) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                topMargin = (20 * density).toInt()
                bottomMargin = (16 * density).toInt()
            })
        val changelogCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFFF3F4F6.toInt())
                cornerRadius = 12 * density
            }
        }
        changelogCard.addView(TextView(activity).apply {
            text = "更新日志"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF374151.toInt())
        })
        changelogCard.addView(TextView(activity).apply {
            text = "暂无更新记录"
            textSize = 14f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, (8 * density).toInt(), 0, 0)
            setLineSpacing(4 * density, 1f)
        })
        inner.addView(changelogCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val freqRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        val freqLabel = TextView(activity).apply {
            text = "每 ${getAutoBuildFreq(activity)} 条消息自动编译"
            textSize = 14f
            setTextColor(0xFF374151.toInt())
        }
        val seekBar = SeekBar(activity).apply {
            max = 95
            progress = getAutoBuildFreq(activity) - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val freq = value + 5
                    setAutoBuildFreq(activity, freq)
                    freqLabel.text = "每 $freq 条消息自动编译"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        freqRow.addView(freqLabel)
        freqRow.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * density).toInt() })
        inner.addView(View(activity).apply { setBackgroundColor(0xFFE5E7EB.toInt()) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                topMargin = (20 * density).toInt()
                bottomMargin = (16 * density).toInt()
            })
        inner.addView(freqRow)

        val buildBtn = Button(activity).apply {
            text = "编译并更新 Activity"
            setTextColor(darkText)
            setAllCaps(false)
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            setOnClickListener {
                ensureEmbeddedTerminal(activity)
                activity.startService(android.content.Intent(activity, PluginBuildService::class.java))
                onDismiss()
                Toast.makeText(activity, "已开始编译，完成后会通知", Toast.LENGTH_SHORT).show()
            }
        }
        inner.addView(buildBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (24 * density).toInt() })

        val resetBtn = Button(activity).apply {
            text = "重置为默认"
            setTextColor(darkText)
            setAllCaps(false)
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            setOnClickListener {
                resetPluginToDefault(activity)
                onDismiss()
                Toast.makeText(activity, "已重置", Toast.LENGTH_SHORT).show()
            }
        }
        inner.addView(resetBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * density).toInt() })

        return scroll
    }
}
