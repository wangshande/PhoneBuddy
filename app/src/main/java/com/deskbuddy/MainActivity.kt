package com.deskbuddy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 主页，结构参考 iOS 设置：大标题 → 形象舞台 → 分组列表。
 * 只有底部一个主操作（预览桌面模式），其余都是列表行和胶囊按钮。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var memoryButton: TextView
    private val statusHandler=Handler(Looper.getMainLooper())
    private val memoryPoll=object:Runnable { override fun run(){if(::memoryButton.isInitialized)memoryButton.text=if(MemoryBus.running) "停止全天听记" else "开启全天听记";statusHandler.postDelayed(this,1200)} }
    private val memoryPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) MemoryService.start(this)
        else android.widget.Toast.makeText(this,"未获得麦克风权限，全天听记未开启",android.widget.Toast.LENGTH_LONG).show()
    }
    private lateinit var store: TodoStore
    private lateinit var preview: PetView
    private lateinit var personalityLabel: TextView
    private lateinit var providerHint: TextView
    private lateinit var providerStatus: TextView
    private lateinit var keyInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var asrStatus: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var todoBox: LinearLayout
    private var selectedProvider: Provider = Provider.CLAUDE

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
        startWatching()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TodoStore(this)
        selectedProvider = AiPrefs.selected(this)

        // 输入框先建好，分组里的行要用到
        keyInput = field("粘贴 API Key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        urlInput = field("服务地址", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        modelInput = field("模型名", InputType.TYPE_CLASS_TEXT)
        providerHint = footer("")
        providerStatus = footer("")
        asrStatus = footer("")
        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        todoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(40))
        }

        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        content.addView(label(22f, Theme.LABEL, Face.MEDIUM).apply { text = "桌面伙伴" }, matchWrap())
        content.addView(stage(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280)))
        personalityLabel = label(14f, Theme.LABEL_2).apply {
            text = PetPrefs.load(this@MainActivity).personality
            gravity = Gravity.CENTER
        }
        content.addView(personalityLabel, matchWrap())
        content.addView(segmented(
            options = PetStyle.values().map { it.name to it.label },
            current = { preview.style.name },
            onPick = { name -> selectStyle(PetStyle.values().first { it.name == name }) }
        ), matchWrap())

        content.addView(primaryButton("进入桌面") { DeskActivity.launch(this@MainActivity) }, matchWrap(top = 24))
        // 形象
        // 玩一玩：横向滑动的胶囊，点一下让它做动作
        content.addView(section("玩一玩"))
        content.addView(playRow(), matchWrap())

        // 日常界面只留下伙伴；连接配置和权限按需展开。
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(label(15f, Theme.LABEL_2, Face.MEDIUM).apply {
            text = "陪伴与语音设置  ＋"
            setPadding(dp(4), dp(24), dp(4), dp(16))
            minimumHeight = dp(48)
            isClickable = true
            setOnClickListener {
                val show = advanced.visibility != View.VISIBLE
                advanced.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "陪伴与语音设置  －" else "陪伴与语音设置  ＋"
            }
        }, matchWrap())
        content.addView(advanced, matchWrap())
        advanced.addView(section("陪伴与听记"))
        advanced.addView(segmented(
            listOf("charge" to "充电时陪伴","all" to "全天陪伴"),
            {if(CompanionPrefs.allDay(this)) "all" else "charge"},
            {CompanionPrefs.setAllDay(this,it=="all")}
        ),matchWrap(top=16))
        memoryButton=ghostButton(if(MemoryBus.running) "停止全天听记" else "开启全天听记") {
            if(MemoryBus.running) MemoryService.stop(this) else {
                val permissions=mutableListOf(Manifest.permission.RECORD_AUDIO)
                if(Build.VERSION.SDK_INT>=33) permissions+=Manifest.permission.POST_NOTIFICATIONS
                memoryPermission.launch(permissions.toTypedArray())
            }
        }
        advanced.addView(memoryButton,matchWrap(top=12))
        advanced.addView(formRow("最近24小时听记") {startActivity(Intent(this,MemoryActivity::class.java))},matchWrap())
        advanced.addView(footer("全天听记需手动开启，通知栏可随时停止。会持续使用麦克风，静音时暂停识别计算；电池上的全天桌面会遵循系统息屏时间。"))
        // 对话模型
        advanced.addView(section("对话模型"))
        advanced.addView(groupOf(
            padded(segmented(
                options = Provider.values().map { it.name to it.title },
                current = { selectedProvider.name },
                onPick = { selectProvider(Provider.from(it)) }
            )),
            inputRow("密钥", keyInput),
            inputRow("地址", urlInput),
            inputRow("模型", modelInput)
        ))
        advanced.addView(providerHint, matchWrap())
        advanced.addView(providerStatus, matchWrap())
        advanced.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), 0)
            addView(tintedButton("测试连接") { testProvider() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            addView(tintedButton("保存并启用") { saveProvider() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        }, matchWrap())
        selectProvider(selectedProvider)

        advanced.addView(section("语音识别引擎"))
        advanced.addView(voiceSettings(),matchWrap())
        advanced.addView(section("对话回忆"))
        val recall=android.widget.CheckBox(this).apply {
            text="允许对话模型检索听记";setTextColor(Theme.LABEL);isChecked=CompanionPrefs.recallEnabled(this@MainActivity)
            setOnCheckedChangeListener{_,on->CompanionPrefs.setRecall(this@MainActivity,on)}
        }
        advanced.addView(recall)
        advanced.addView(footer("开启后，询问过去发生的事时，会把相关听记片段发给你配置的对话服务。关闭时仍可在本机查看和查找听记。"))
        // 标准中文语音已内置，完全离线。
        advanced.addView(section("离线语音"))
        advanced.addView(formRow("标准中文语音", "已内置"))
        advanced.addView(asrStatus, matchWrap())
        // 权限与待办：内容随状态刷新
        advanced.addView(section("权限"))
        advanced.addView(permBox, matchWrap())
        advanced.addView(footer("人脸检测在手机本地完成，画面不保存、不上传。"), matchWrap())
        advanced.addView(groupOf(
            formRow("授予权限（含日历）", titleColor = Theme.ACCENT) {
                permLauncher.launch(arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                ))
            }
        ), matchWrap())

        content.addView(section("待办"))
        content.addView(todoBox, matchWrap())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            addView(content)
        })
        prepareVoice()
        refresh()
    }

    private fun prepareVoice() {
        VoskModel.setUseBig(this, false)
        asrStatus.text = "正在准备内置语音…"
        Thread {
            val ready = runCatching { VoskModel.ensureBundled(this) }
            runOnUiThread {
                if (!isDestroyed) asrStatus.text = if (ready.isSuccess) "中文语音已就绪，无需联网下载" else "语音准备失败，请重新打开应用重试"
            }
        }.start()
    }

    private val gazeListener: (GazeSample) -> Unit = { sample ->
        if (::preview.isInitialized) preview.setGaze(sample.yaw, sample.pitch, sample.looking, sample.cx, sample.cy)
    }
    override fun onStart() {
        super.onStart()
        Gaze.show(this)
        Gaze.add(gazeListener)
        startWatching()
    }
    override fun onStop() {
        Gaze.remove(gazeListener)
        Gaze.hide(this)
        super.onStop()
    }
    private fun startWatching() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) DeskService.start(this)
    }

    override fun onResume() {
        super.onResume()
        statusHandler.removeCallbacks(memoryPoll);statusHandler.post(memoryPoll)
        refresh()
        refreshAsr()
    }

    override fun onPause(){statusHandler.removeCallbacks(memoryPoll);super.onPause()}

    private fun voiceSettings(): View {
        val saved=AsrPrefs.config(this)
        var external=saved.external
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val cloud=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=if(external)View.VISIBLE else View.GONE}
        val endpoint=field("HTTPS 转录接口地址",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply{setText(saved.endpoint)}
        val key=field(if(saved.key.isBlank()) "API Key（可选）" else "已保存，留空沿用",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val model=field("语音模型名",InputType.TYPE_CLASS_TEXT).apply{setText(saved.model)}
        cloud.addView(inputRow("地址",endpoint));cloud.addView(inputRow("密钥",key));cloud.addView(inputRow("模型",model))
        cloud.addView(footer("需要兼容 audio/transcriptions 的语音接口。外部模式会将录音片段发送到此地址，可能产生服务费用；文字分段更新，速度取决于网络和服务。"))
        box.addView(segmented(listOf("local" to "内置离线","external" to "外部服务"),{if(external)"external" else "local"},{external=it=="external";cloud.visibility=if(external)View.VISIBLE else View.GONE}))
        box.addView(cloud)
        val status=footer("")
        box.addView(tintedButton("保存语音设置") {
            val cfg=AsrConfig(external,endpoint.text.toString().trim(),key.text.toString().trim().ifBlank{saved.key},model.text.toString().trim())
            if(cfg.error!=null) status.text=cfg.error
            else {AsrPrefs.save(this,cfg);MemoryService.reconfigure(this);key.setText("");key.hint=if(cfg.key.isBlank())"API Key（可选）" else "已保存，留空沿用";status.text=if(external)"已切换外部识别，重新进入桌面后生效" else "已切换内置中文离线识别"}
        })
        box.addView(status)
        return box
    }

    // ---- 舞台 ----

    private fun stage(): View = FrameLayout(this).apply {
        addView(LampGlow(this@MainActivity).apply { cx = 0.5f; cy = 0.5f },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        preview = PetView(this@MainActivity).apply {
            style = PetPrefs.load(this@MainActivity)
            contentDescription = "玻璃伙伴，轻触和它打招呼"
            setOnClickListener { playWakeUp(); react(PetView.Action.WAVE) }
        }
        addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(280), Gravity.CENTER))
    }

    private fun selectStyle(s: PetStyle) {
        preview.style = s
        preview.playWakeUp()
        preview.react(PetView.Action.WAVE)
        personalityLabel.text = s.personality
        PetPrefs.save(this, s)
    }

    // ---- 玩一玩 ----

    private fun playRow(): View {
        val items: List<Pair<String, () -> Unit>> =
            PetView.Action.values().map { a -> a.label to { preview.react(a) } } +
                listOf(
                    "睡觉" to { preview.setMood(PetView.Mood.SLEEP) },
                    "醒来" to { preview.playWakeUp() }
                )
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(4), dp(16), dp(4))
                items.forEach { (name, act) ->
                    addView(ghostButton(name, size = 15f) { act() },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            rightMargin = dp(8)
                        })
                }
            })
        }
    }

    // ---- 对话模型 ----

    private fun selectProvider(p: Provider) {
        selectedProvider = p
        val cfg = AiPrefs.config(this, p)
        urlInput.setText(cfg.baseUrl)
        modelInput.setText(cfg.model)
        keyInput.setText("")
        keyInput.hint = if (cfg.key.isNotBlank()) "已保存，留空则沿用" else "粘贴 API Key"
        providerHint.text = p.hint
        providerStatus.text = activeStatus()
    }

    /** 表单里的配置；密钥留空时沿用已保存的。 */
    private fun fieldConfig(): ModelConfig {
        val p = selectedProvider
        val key = keyInput.text.toString().trim().ifBlank { AiPrefs.config(this, p).key }
        return ModelConfig(
            provider = p,
            baseUrl = urlInput.text.toString().trim(),
            model = modelInput.text.toString().trim(),
            key = key
        )
    }

    private fun saveProvider() {
        val cfg = fieldConfig()
        if (!cfg.isReady) {
            providerStatus.text = "还缺少密钥、服务地址或模型名，没有保存。"
            return
        }
        AiPrefs.save(this, cfg)
        keyInput.setText("")
        keyInput.hint = "已保存，留空则沿用"
        providerStatus.text = activeStatus()
    }

    /** 发一条极短的请求，确认密钥、地址、模型名都对。 */
    private fun testProvider() {
        val cfg = fieldConfig()
        if (!cfg.isReady) {
            providerStatus.text = "还缺少密钥、服务地址或模型名。"
            return
        }
        providerStatus.text = "正在测试…"
        Thread {
            val msg = try {
                val reply = ChatClient(cfg).complete("你是测试助手，只回复一个字：好", listOf("user" to "测试"), 16)
                "连接成功，${cfg.provider.title} 回复：${reply.trim().take(20)}"
            } catch (e: Exception) {
                "连接失败：${e.message ?: "未知错误"}。检查密钥、地址和模型名。"
            }
            runOnUiThread { providerStatus.text = msg }
        }.start()
    }

    private fun activeStatus(): String {
        val a = AiPrefs.config(this)
        return if (a.isReady) {
            "正在使用：${a.provider.title}（${a.model}）。说话时由它理解和回答。"
        } else {
            "还没启用对话模型，现在只用离线规则：能记待办，但不能闲聊。"
        }
    }

    // ---- 离线语音 ----

    private fun refreshAsr() {
        asrStatus.text = if (VoskModel.modelDir(this, false) != null) "中文语音已就绪，无需联网下载" else "中文语音已内置，首次使用正在准备"
    }


    private fun section(title: String): TextView =
        label(13f, Theme.LABEL_2, Face.MEDIUM).apply {
            text = title
            setPadding(dp(16), dp(28), 0, dp(8))
        }

    /** 分组下方的说明文字，缩进与分组内文字对齐。 */
    private fun footer(text: String): TextView =
        label(13f, Theme.LABEL_2).apply {
            this.text = text
            setLineSpacing(0f, 1.2f)
            setPadding(dp(16), dp(8), dp(16), 0)
        }

    /** 分组里的一行：左边标题，右边输入框（无下划线，右对齐）。 */
    private fun inputRow(title: String, edit: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(50)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(label(17f, Theme.LABEL).apply { text = title },
            LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun field(hint: String, inputType: Int): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Theme.LABEL_3)
        setTextColor(Theme.LABEL)
        textSize = 17f
        isSingleLine = true
        this.inputType = inputType
        background = null
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
    }

    private fun matchWrap(top: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        }

    // ---- 权限与待办 ----

    private fun refresh() {
        if (!::permBox.isInitialized) return

        permBox.removeAllViews()
        permBox.addView(groupOf(
            formRow("摄像头", yesNo(granted(Manifest.permission.CAMERA)),
                valueColor = if (granted(Manifest.permission.CAMERA)) Theme.GREEN else Theme.LABEL_2),
            formRow("麦克风", yesNo(granted(Manifest.permission.RECORD_AUDIO)),
                valueColor = if (granted(Manifest.permission.RECORD_AUDIO)) Theme.GREEN else Theme.LABEL_2),
            formRow("日历", yesNo(Actions.hasCalendarPermission(this)),
                valueColor = if (Actions.hasCalendarPermission(this)) Theme.GREEN else Theme.LABEL_2)
        ), matchWrap())

        val pending = store.pending()
        todoBox.removeAllViews()
        if (pending.isEmpty()) {
            todoBox.addView(groupOf(formRow("没有待办", titleColor = Theme.LABEL_2)), matchWrap())
        } else {
            todoBox.addView(groupOf(*pending.map { formRow(it.text) }.toTypedArray()), matchWrap())
        }
    }

    private fun yesNo(ok: Boolean) = if (ok) "已授权" else "未授权"

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
