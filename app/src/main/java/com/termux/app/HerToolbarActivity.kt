package com.termux.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.R

/**
 * 宿主统一壳：子类只需 setContentView(内容视图)，即自动获得左上角「对话」「更新日志」按钮与左侧抽屉（更新日志、编译、重置）。
 * 避免每个 Activity 重复写按钮与抽屉逻辑。
 */
abstract class HerToolbarActivity : AppCompatActivity() {

    protected var drawerLayout: DrawerLayout? = null
        private set
    protected var contentContainer: FrameLayout? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
    }

    override fun setContentView(layoutResID: Int) {
        val view = layoutInflater.inflate(layoutResID, null)
        setContentView(view)
    }

    override fun setContentView(view: View) {
        wrapWithToolbarAndDrawer(view)
    }

    private fun wrapWithToolbarAndDrawer(userContent: View) {
        val density = resources.displayMetrics.density
        val marginPx = HerUiHelper.getChatButtonMarginPx(this)
        val buttonSizePx = (40 * density).toInt()
        val gapPx = (8 * density).toInt()

        val drawer = DrawerLayout(this).apply {
            setBackgroundColor(0xFFFDFDFD.toInt())
            setFitsSystemWindows(false)
        }
        drawerLayout = drawer

        val contentRoot = FrameLayout(this).apply {
            setBackgroundColor(0xFFFDFDFD.toInt())
        }
        contentContainer = contentRoot

        userContent.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        contentRoot.addView(userContent)

        val chatBtn = HerUiHelper.createChatButton(this)
        (chatBtn.layoutParams as? FrameLayout.LayoutParams)?.apply {
            leftMargin = marginPx
            topMargin = marginPx
        }
        contentRoot.addView(chatBtn)

        val menuBtn = HerUiHelper.createCircleIconButton(this, R.drawable.ic_note, "更新日志")
        (menuBtn.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = marginPx + buttonSizePx + gapPx
            topMargin = marginPx
            gravity = Gravity.LEFT or Gravity.TOP
        }
        menuBtn.setOnClickListener { drawer.openDrawer(Gravity.LEFT) }
        contentRoot.addView(menuBtn)

        val depStoreBtn = HerUiHelper.createCircleIconButton(this, R.drawable.ic_extension, "依赖商店")
        (depStoreBtn.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = marginPx + 2 * (buttonSizePx + gapPx)
            topMargin = marginPx
            gravity = Gravity.LEFT or Gravity.TOP
        }
        depStoreBtn.setOnClickListener { startActivity(Intent(this, DepStoreActivity::class.java)) }
        contentRoot.addView(depStoreBtn)

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
        }
        val drawerContent = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(inner)
        }
        val drawerLp = DrawerLayout.LayoutParams(
            (280 * density).toInt(),
            DrawerLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.LEFT }

        // 分区：AI 配置
        inner.addView(TextView(this).apply {
            text = "AI 配置"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        })
        val darkText = Color.parseColor("#111827")
        val hintText = Color.parseColor("#6B7280")
        val providerSpinner = Spinner(this).apply {
            val names = AI_PROVIDER_IDS.map { getAiProviderDisplayName(it) }
            adapter = object : ArrayAdapter<String>(this@HerToolbarActivity, android.R.layout.simple_spinner_item, names) {
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
            val idx = AI_PROVIDER_IDS.indexOf(getAiProvider(this@HerToolbarActivity)).coerceAtLeast(0)
            setSelection(idx)
        }
        inner.addView(providerSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        val apiKeyInput = EditText(this).apply {
            hint = "API Key"
            setText(getAiApiKey(this@HerToolbarActivity))
            setTextColor(darkText)
            setHintTextColor(hintText)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            textSize = 14f
        }
        inner.addView(apiKeyInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        val modelInput = EditText(this).apply {
            hint = "模型（留空用默认）"
            setText(getAiModel(this@HerToolbarActivity))
            setTextColor(darkText)
            setHintTextColor(hintText)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            textSize = 14f
        }
        inner.addView(modelInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        val embedKeyInput = EditText(this).apply {
            hint = "Embedding Key（Dashscope，必填）"
            setText(getEmbedApiKey(this@HerToolbarActivity))
            setTextColor(darkText)
            setHintTextColor(hintText)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            textSize = 14f
        }
        inner.addView(embedKeyInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        val searchKeyInput = EditText(this).apply {
            hint = "Search API Key（可选）"
            setText(getSearchApiKey(this@HerToolbarActivity))
            setTextColor(darkText)
            setHintTextColor(hintText)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            textSize = 14f
        }
        inner.addView(searchKeyInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        val saveAiBtn = Button(this).apply {
            text = "保存 AI 配置"
            setTextColor(darkText)
            setAllCaps(false)
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            setOnClickListener {
                val embedKey = embedKeyInput.text.toString().trim()
                if (embedKey.isBlank()) {
                    Toast.makeText(this@HerToolbarActivity, "请填写 Embedding Key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                setAiProvider(this@HerToolbarActivity, AI_PROVIDER_IDS[providerSpinner.selectedItemPosition])
                setAiApiKey(this@HerToolbarActivity, apiKeyInput.text.toString().trim())
                setAiModel(this@HerToolbarActivity, modelInput.text.toString().trim())
                setEmbedApiKey(this@HerToolbarActivity, embedKey)
                setSearchApiKey(this@HerToolbarActivity, searchKeyInput.text.toString().trim())
                Toast.makeText(this@HerToolbarActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        }
        inner.addView(saveAiBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })

        // 分隔线
        inner.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#E5E7EB"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
            topMargin = (20 * density).toInt()
            bottomMargin = (16 * density).toInt()
        })

        // 更新日志：卡片样式
        val changelogCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFFF3F4F6.toInt())
                cornerRadius = 12 * density
            }
        }
        changelogCard.addView(TextView(this).apply {
            text = "更新日志"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#374151"))
        })
        changelogCard.addView(TextView(this).apply {
            text = "暂无更新记录"
            textSize = 14f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, (8 * density).toInt(), 0, 0)
            setLineSpacing((4 * density), 1f)
        })
        inner.addView(changelogCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val freqRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        val freqLabel = TextView(this).apply {
            text = "每 ${getAutoBuildFreq(this@HerToolbarActivity)} 条消息自动编译"
            textSize = 14f
            setTextColor(Color.parseColor("#374151"))
        }
        val seekBar = SeekBar(this).apply {
            max = 95
            progress = getAutoBuildFreq(this@HerToolbarActivity) - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val freq = value + 5
                    setAutoBuildFreq(this@HerToolbarActivity, freq)
                    freqLabel.text = "每 $freq 条消息自动编译"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        freqRow.addView(freqLabel)
        freqRow.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * density).toInt() })
        inner.addView(View(this).apply { setBackgroundColor(Color.parseColor("#E5E7EB")) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                topMargin = (20 * density).toInt()
                bottomMargin = (16 * density).toInt()
            })
        inner.addView(freqRow)

        val buildBtn = Button(this).apply {
            text = "编译并更新 Activity"
            setTextColor(darkText)
            setAllCaps(false)
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            setOnClickListener {
                ensureEmbeddedTerminal(this@HerToolbarActivity)
                startService(Intent(this@HerToolbarActivity, PluginBuildService::class.java))
                drawer.closeDrawer(Gravity.LEFT)
                Toast.makeText(this@HerToolbarActivity, "已开始编译，完成后会通知", Toast.LENGTH_SHORT).show()
            }
        }
        inner.addView(buildBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (24 * density).toInt() })

        val resetBtn = Button(this).apply {
            text = "重置为默认"
            setTextColor(darkText)
            setAllCaps(false)
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
        }
        resetBtn.setOnClickListener {
            resetPluginToDefault(this@HerToolbarActivity)
            drawer.closeDrawer(Gravity.LEFT)
            Toast.makeText(this@HerToolbarActivity, "已重置", Toast.LENGTH_SHORT).show()
        }
        inner.addView(resetBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * density).toInt() })

        drawer.addView(contentRoot, DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT))
        drawer.addView(drawerContent, drawerLp)

        super.setContentView(drawer)

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        contentRoot.requestApplyInsets()
        WindowCompat.getInsetsController(window, contentRoot)?.isAppearanceLightStatusBars = true
    }
}
