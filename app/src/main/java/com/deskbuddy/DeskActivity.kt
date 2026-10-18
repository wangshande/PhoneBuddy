package com.deskbuddy

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 充电时的桌面模式。
 *
 * 交互流程：
 * 注视屏幕（Gaze）→ 宠物醒来并开始离线听写（Vosk）→ 整句文字
 * → 语义判断（是否对它说、要做什么）→ 直接执行，或拿不准时先问用户
 * → 宠物用语音回应。
 *
 * 麦克风只在"注视、且宠物没在说话、没在思考"时开启，避免录到自己的声音。
 */
class DeskActivity : AppCompatActivity() {

    private lateinit var deskRoot: FrameLayout
    private lateinit var deskContent: LinearLayout
    private lateinit var todoSheet: LinearLayout
    private lateinit var store: TodoStore
    private lateinit var pet: PetView
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var bubble: TextView
    private lateinit var voiceWave: VoiceWaveView
    private lateinit var transcript: TextView
    private var lastTranscript = ""
    private var interactive = false
    private lateinit var hearing: TextView
    private lateinit var listenDot: View
    private lateinit var countText: TextView
    private lateinit var todoList: LinearLayout
    private lateinit var confirmBar: LinearLayout
    private lateinit var confirmText: TextView
    private lateinit var input: EditText

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var judge: SemanticJudge = RuleJudge()

    private lateinit var asr: SpeechEngine
    private var asrConfig: AsrConfig? = null
    private var asrLoading = false
    private var asrLoadFailed = false
    private var pulse: ValueAnimator? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var listening = false   // 麦克风当前是否开启
    private var speaking = false    // 宠物正在说话
    private var thinking = false    // 正在做语义判断
    private var exiting = false
    private var looking = false
    private var lastLookAt = 0L
    private var permissionAsked = false

    /** 拿不准的判断，等用户说"对/不对"或点按钮后再执行 */
    private var pendingJudgement: Judgement? = null

    private var lastHintAt = 0L     // 上一次刷新注视状态提示的时间
    private var requestId = 0       // 每次语义判断的编号，用来丢弃超时的晚到结果
    private var startRetries = 0    // 麦克风启动失败后的重连次数

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            updateListening()
        } else {
            bubble.text = "没有麦克风权限，我听不见你说话。可以用下面的输入框打字"
        }
    }

    private val gazeListener: (GazeSample) -> Unit = { onGaze(it) }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if(intent.action==Intent.ACTION_POWER_CONNECTED){window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);return}
            if (!CompanionPrefs.allDay(this@DeskActivity)) beginExit()
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            main.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    private val asrCallback = object : SpeechEngine.Callback {
        override fun onPartial(text: String) {
            main.post {
                if (listening && text.isNotBlank()) {
                    lastTranscript = text
                    transcript.text = text + "▏"
                    hearing.text = "正在听"
                }
            }
        }

        override fun onFinal(text: String) {
            main.post {
                if (listening && !exiting) {
                    lastTranscript = text
                    transcript.text = text
                    onHeard(text, typed = false)
                }
            }
        }

        override fun onLevel(level: Float) {
            main.post { if (listening && ::voiceWave.isInitialized) voiceWave.push(level) }
        }

        override fun onError(message: String) {
            main.post {
                if (interactive && !exiting) {
                    listening = false
                    showListening(false)
                    hearing.text = "收音中断，正在重试"
                    main.postDelayed({ if (interactive && !exiting) updateListening() }, 900)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(!CompanionPrefs.allDay(this) || isCharging())window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = TodoStore(this)
        asrConfig = AsrPrefs.config(this)
        asr = AsrPrefs.engine(this)
        judge = AiPrefs.judge(this)
        buildUi()
        tts = TextToSpeech(this) { status -> onTtsInit(status) }
        ContextCompat.registerReceiver(
            this, powerReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED).apply{addAction(Intent.ACTION_POWER_CONNECTED)},
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 没人看它时，进入后直接睡着（冒 Zzz），注视时才醒来
        pet.playEnter(awake = looking)
        todoSheet.alpha = 0f
        todoSheet.translationY = dp(16).toFloat()
        todoSheet.animate().alpha(1f).translationY(0f).setStartDelay(320).setDuration(850).start()
        // 时钟是进入时的第二个动作：稍后淡入，与宠物错开
        clockTime.alpha = 0f
        clockDate.alpha = 0f
        clockTime.animate().alpha(1f).setStartDelay(300).setDuration(700).start()
        clockDate.animate().alpha(1f).setStartDelay(420).setDuration(700).start()
        updateClock()
        main.postDelayed(clockTick, CLOCK_INTERVAL_MS)
        refreshList()
        bubble.text = when {
            VoskModel.modelDir(this, false) == null -> "中文语音已内置，首次使用正在准备"
            !hasMicPermission() -> "先授予麦克风权限，我才能听你说话"
            else -> "我在这儿，看着我就能跟我说话"
        }
    }

    override fun onStart() {
        super.onStart()
        Gaze.show(this)
        Gaze.add(gazeListener)
        // 注视检测由前台服务提供。从主页预览进来时服务不会自动启动，这里确保它在跑。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            DeskService.start(this)
            hearing.text = "正在打开摄像头"
        } else {
            hearing.text = "没有摄像头权限，我看不见你。去主页授权一下"
        }
    }

    override fun onResume() {
        super.onResume()
        interactive = true
        if (asrConfig != AsrPrefs.config(this)) {
            asr.release(); asrConfig = AsrPrefs.config(this); asr = AsrPrefs.engine(this)
            asrLoading=false;asrLoadFailed=false
        }
        updateListening()
    }

    override fun onPause() {
        interactive = false
        listening = false
        showListening(false)
        asr.stop()
        MemoryBus.remove(asrCallback)
        super.onPause()
    }

    override fun onStop() {
        Gaze.remove(gazeListener)
        Gaze.hide(this)
        // 不在充电时，离开桌面模式就关掉摄像头，省电。充电时由充电广播管理。
        // Gaze.hide 会在所有可见页面离开后释放摄像头；全天听记独立继续。
        MemoryBus.remove(asrCallback)
        super.onStop()
    }

    private fun isCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(powerReceiver) }
        main.removeCallbacksAndMessages(null)
        pulse?.cancel()
        asr.release()
        MemoryBus.remove(asrCallback)
        MemoryService.muteForTts(false)
        tts?.stop()
        tts?.shutdown()
        worker.shutdown()
    }

    /** 横竖屏切换时只重新排版，状态保留。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
        updateClock()
        refreshList()
        resetMood()
    }

    // ---------- 界面 ----------

    private fun buildUi() {
        pulse?.cancel()
        pulse = null
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        deskRoot = root
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        WindowCompat.setDecorFitsSystemWindows(window,false)
        if(android.os.Build.VERSION.SDK_INT>=28)window.attributes=window.attributes.apply {
            layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window,window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars=true
            isAppearanceLightNavigationBars=true
        }

        // 台灯光晕只落在宠物一侧，其余保持纯黑
        val glow = LampGlow(this).apply {
            cx = if (landscape) 0.28f else 0.5f
            cy = if (landscape) 0.48f else 0.35f
        }
        root.addView(glow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(24), dp(if (landscape) 22 else 28), dp(24), dp(20))
        }

        deskContent = content
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe=insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            content.setPadding(dp(24)+safe.left,dp(if(landscape)22 else 28)+safe.top,dp(24)+safe.right,dp(20)+safe.bottom)
            insets
        }

        // 左 / 上：时间、宠物、一句话、听写状态。宠物是唯一的主角。
        val deskCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        clockTime = label(if (landscape) 50f else 46f, Theme.LABEL, Face.LIGHT).apply {
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
        }
        if (!landscape) deskCol.addView(clockTime, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        clockDate = label(13f, Theme.LABEL_2).apply { gravity = Gravity.CENTER }
        if (!landscape) deskCol.addView(clockDate, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        pet = PetView(this).apply {
            style = PetPrefs.load(this@DeskActivity)
            contentDescription = "玻璃伙伴，轻触打招呼，长按更换配色"
            setGaze(0f, 0f, looking)
            setOnClickListener { onPetTapped() }
            setOnLongClickListener { cycleStyle(); true }
        }
        val petParams = if (landscape) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        deskCol.addView(pet, petParams)
        voiceWave = VoiceWaveView(this)
        deskCol.addView(voiceWave, LinearLayout.LayoutParams(dp(144), dp(if (landscape) 20 else 28)))
        transcript = label(17f, Theme.LABEL).apply {
            text = lastTranscript
            gravity = Gravity.CENTER
            minLines = 2
            maxLines = 2
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        deskCol.addView(transcript, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (landscape) 44 else 58)))

        // 对话：宠物下方一条半透明胶囊，不再是整块白色色块
        bubble = label(16f, Theme.LABEL).apply {
            gravity = Gravity.CENTER
            background = null
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        deskCol.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })

        // 听写状态：绿点呼吸 + 一句话
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listenDot = View(this).apply {
            background = surface(Theme.GREEN, 5)
            alpha = 0f
            visibility = if (listening) View.VISIBLE else View.INVISIBLE
        }
        statusRow.addView(listenDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        hearing = label(12f, Theme.LABEL_2).apply { maxLines = 2; gravity = Gravity.CENTER }
        statusRow.addView(hearing)
        deskCol.addView(statusRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        content.addView(deskCol, if (landscape) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        // 右 / 下：待办面板。大标题 + 列表，和 Apple 提醒事项的读法一致。
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = null
            setPadding(dp(22), dp(22), dp(22), dp(18))
        }
        todoSheet = sheet
        if (landscape) {
            sheet.addView(clockTime)
            sheet.addView(clockDate, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        sheet.addView(label(21f, Theme.LABEL, Face.MEDIUM).apply {
            text = "今天"
            letterSpacing = -0.01f
        })
        countText = label(12f, Theme.LABEL_2).apply { setPadding(0, dp(2), 0, 0) }
        sheet.addView(countText)

        todoList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(ScrollView(this).apply { addView(todoList) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })

        // 拿不准时的确认条：淡橙底
        confirmBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(Theme.ACCENT_SOFT, 18)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = if (pendingJudgement != null) View.VISIBLE else View.GONE
        }
        confirmText = label(16f, Theme.LABEL).apply {
            text = pendingJudgement?.let { "我好像听到：${describe(it)}。说「对」或「不对」就行" }.orEmpty()
        }
        confirmBar.addView(confirmText)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        buttons.addView(primaryButton("记下") { confirmPending() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        buttons.addView(ghostButton("不对") { cancelPending() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        confirmBar.addView(buttons)
        sheet.addView(confirmBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        // 输入：圆角输入框 + 淡橙发送按钮
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "记下一件小事…"
            setHintTextColor(Theme.LABEL_3)
            setTextColor(Theme.LABEL)
            textSize = 14f
            isSingleLine = true
            background = surface(Theme.ELEVATED, 22)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { _, _, _ -> sendTyped(); true }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        row.addView(tintedButton("发送") { sendTyped() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        sheet.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        content.addView(sheet, if (landscape) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.82f).apply { leftMargin = dp(24) }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.72f).apply { topMargin = dp(24) }
        })

        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        showListening(listening)
    }

    private fun updateClock() {
        if (!::clockTime.isInitialized) return
        val now = Date()
        clockTime.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        clockDate.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now)
    }

    private fun refreshList() {
        if (!::todoList.isInitialized) return
        todoList.removeAllViews()
        val pending = store.pending()
        countText.text = "${pending.size} 项待办"

        if (pending.isEmpty()) {
            todoList.addView(label(17f, Theme.LABEL_2).apply {
                text = "看着我说「帮我记下…」即可添加。"
                setPadding(0, dp(8), 0, 0)
            })
        }
        pending.forEachIndexed { i, t ->
            if (i > 0) addTodoSeparator()
            todoList.addView(todoRow(t.text, done = false) {
                store.complete(t.id)
                refreshList()
                pet.react(PetView.Action.CELEBRATE)
                say("「${t.text}」完成啦，又做好了一件小事。")
            })
        }
        val done = store.all().filter { it.done }.takeLast(3)
        if (done.isNotEmpty()) {
            todoList.addView(label(15f, Theme.LABEL_2, Face.MEDIUM).apply {
                text = "最近完成"
                setPadding(0, dp(20), 0, dp(4))
            })
            done.forEachIndexed { i, t ->
                if (i > 0) addTodoSeparator()
                todoList.addView(todoRow(t.text, done = true, onTap = null))
            }
        }
    }

    /** 行与行之间的细线，左侧缩进到文字的位置。 */
    private fun addTodoSeparator() {
        todoList.addView(hairline(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            leftMargin = dp(36)
        })
    }

    /** 一行待办：圆点 + 文字。完成的项目加删除线、变灰。 */
    private fun todoRow(text: String, done: Boolean, onTap: (() -> Unit)?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(10), 0, dp(10))
            if (onTap != null) {
                isClickable = true
                setOnClickListener { onTap() }
            }
            addView(checkCircle(done), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(14) })
            addView(label(17f, if (done) Theme.LABEL_3 else Theme.LABEL).apply {
                this.text = text
                if (done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun cycleStyle() {
        val next = pet.style.next()
        pet.style = next
        PetPrefs.save(this, next)
        bubble.text = "${next.label} · ${next.personality}"
    }

    private fun onPetTapped() {
        pet.playWakeUp()
        // 被点击时随机做一个动作回应，动作本身就是对用户动作的反馈
        pet.react(PetView.Action.values().random())
        say(pet.style.greeting)
    }

    /** 按"有没有人看、是否在听"决定基础表情：没人看=睡觉，在听=耳朵竖起，其余=清醒。 */
    private fun resetMood() {
        pet.setMood(
            when {
                !looking -> PetView.Mood.IDLE
                listening -> PetView.Mood.LISTENING
                else -> PetView.Mood.IDLE
            }
        )
    }

    // ---------- 注视 ----------

    private fun onGaze(s: GazeSample) {
        if (s.error != null) { hearing.text = s.error; return }
        pet.setGaze(s.yaw, s.pitch, s.looking, s.cx, s.cy)
        showGazeHint(s)
        val now = System.currentTimeMillis()
        if (s.looking) {
            lastLookAt = now
            if (!looking) {
                looking = true
                onStartLooking()
            }
        } else if (looking && now - lastLookAt > LOOK_TIMEOUT_MS) {
            looking = false
            onStopLooking()
        }
    }

    /** 没在听写时，把"看没看见你"写在状态行里，方便判断问题出在哪。 */
    private fun showGazeHint(s: GazeSample) {
        if (listening || speaking || thinking || pendingJudgement != null) return
        val now = System.currentTimeMillis()
        if (now - lastHintAt < 800L) return
        lastHintAt = now
        hearing.text = when {
            s.looking -> "看见你啦"
            !s.faceSeen -> "我在这儿，等你看过来"
            else -> "再看向我一点，就能开始说话"
        }
    }

    private fun onStartLooking() {
        // 先睁眼伸懒腰醒来，再挥手打招呼（挥手放在伸懒腰之后，否则会被打断）
        pet.playWakeUp()
        updateListening()
        resetMood()
        pet.react(PetView.Action.WAVE)
        if (pendingJudgement == null) bubble.text = when(pet.style) {
            PetStyle.CAT -> "欸，看见你啦。"
            PetStyle.BEAR -> "来啦，一起歇会儿。"
            PetStyle.BUNNY -> "你看见我啦。"
            PetStyle.FOX -> "你来啦！"
            PetStyle.BLOB -> "嗯？我在听。"
        }
    }

    private fun onStopLooking() {
        pet.setMood(PetView.Mood.IDLE)
        bubble.text = "你忙你的，我在这儿。"
        updateListening()
    }

    // ---------- 听写 ----------

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 唯一决定麦克风开关的地方。 */
    private fun updateListening() {
        val eligible = interactive && looking && !speaking && !thinking && !exiting
        if (!eligible) {
            MemoryBus.remove(asrCallback)
            listening = false
            showListening(false)
            asr.stop()
            if (!thinking && !speaking && !exiting) resetMood()
            return
        }
        if (MemoryBus.running) {
            asr.stop()
            MemoryBus.add(asrCallback)
            listening=MemoryBus.ready
            showListening(listening)
            hearing.text=if(listening) "正在听 · 全天听记已开启" else MemoryBus.status
            resetMood()
            if(!listening)main.postDelayed({if(interactive && !exiting)updateListening()},800)
            return
        }
        MemoryBus.remove(asrCallback)
        if (!hasMicPermission()) {
            listening = false
            showListening(false)
            if (!permissionAsked) {
                permissionAsked = true
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            hearing.text = "请允许麦克风，才能听你说话"
            return
        }
        asr.configurationError?.let { hearing.text=it;listening=false;showListening(false);return }
        if (!asr.hasModel) {
            listening = false
            showListening(false)
            loadModelAsync()
            return
        }
        if (!asr.isRunning && !asr.start(asrCallback)) {
            listening = false
            showListening(false)
            if (startRetries++ < MAX_START_RETRIES) {
                hearing.text = "麦克风忙，正在重新连接"
                main.postDelayed({ if (interactive && !exiting) updateListening() }, 700L)
            } else hearing.text = "收音未启动，请检查麦克风权限"
            return
        }
        startRetries = 0
        listening = true
        showListening(true)
        hearing.text = "正在听"
        resetMood()
    }

    private fun loadModelAsync() {
        if (asrLoading || asrLoadFailed) {
            if (asrLoadFailed) bubble.text = "离线语音加载失败，可以打字"
            return
        }
        asrLoading = true
        hearing.text = "正在准备内置中文语音"
        worker.execute {
            val ok = try {
                val dir = VoskModel.ensureBundled(this)
                asr.loadModel(dir)
                true
            } catch (e: Exception) {
                false
            }
            main.post {
                if (isDestroyed) return@post
                asrLoading = false
                if (!ok) asrLoadFailed = true
                updateListening()
            }
        }
    }

    private fun showListening(on: Boolean) {
        if (!::listenDot.isInitialized) return
        if (::voiceWave.isInitialized) voiceWave.active = on
        listenDot.visibility = if (on) View.VISIBLE else View.INVISIBLE
        if (on) {
            if (pulse == null) {
                pulse = ValueAnimator.ofFloat(0.25f, 1f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener { listenDot.alpha = it.animatedValue as Float }
                    start()
                }
            }
        } else {
            pulse?.cancel()
            pulse = null
            listenDot.alpha = 0f
        }
    }

    // ---------- 语义判断与执行 ----------

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        onHeard(text, typed = true)
    }

    private fun onHeard(text: String, typed: Boolean) {
        hearing.text = "听到：「$text」"

        // 正在等待确认：只接受"对/不对"，其它话忽略
        if (pendingJudgement != null) {
            val t = text.replace(FILLER, "")
            when {
                NO_WORDS.any { t.contains(it) } -> cancelPending()
                YES_WORDS.any { t.contains(it) } -> confirmPending()
            }
            return
        }

        thinking = true
        val id = ++requestId
        updateListening()
        pet.setMood(PetView.Mood.THINKING)
        bubble.text = "我想想…"
        val snapshot = store.pending()
        val j = judge
        worker.execute {
            val result = try {
                j.judge(text, snapshot)
            } catch (e: Throwable) {
                RuleJudge().judge(text, snapshot)
            }
            // 直接打字视为对它说的话
            val finalResult = when {
                // 输入框本身是明确的记事入口；语音没有这个默认行为。
                typed && j is RuleJudge && !result.addressed -> Judgement(true, 1f, addTodos = listOf(text))
                typed -> result.copy(addressed = true)
                else -> result
            }
            main.post {
                // 超时后才返回的结果已经作废
                if (id != requestId || exiting || isDestroyed) return@post
                applyJudgement(text, finalResult)
            }
        }
        // 保险：网络慢或判断卡住时，超时就恢复听觉，不让麦克风一直关着
        main.postDelayed({
            if (id == requestId && thinking) {
                thinking = false
                updateListening()
                pet.setMood(PetView.Mood.IDLE)
                bubble.text = "我没反应过来，你再说一遍？"
            }
        }, THINK_TIMEOUT_MS)
    }

    private fun applyJudgement(text: String, j: Judgement) {
        thinking = false
        updateListening()
        if (!j.addressed) {
            // 不是对我说的：安静忽略，不记、不回应
            hearing.text = "我在这里，安静陪着你"
            resetMood()
            return
        }
        if (!j.isAction) {
            pet.setMood(PetView.Mood.IDLE)
            say(j.reply ?: "嗯嗯，我在听")
            return
        }
        if (j.confidence < CONFIRM_BELOW) {
            pendingJudgement = j
            confirmText.text = "我好像听到：${describe(j)}。说「对」或「不对」就行"
            confirmBar.visibility = View.VISIBLE
            pet.setMood(PetView.Mood.THINKING)
            bubble.text = "我不太确定，你看一下？"
            return
        }
        executeJudgement(j)
    }

    private fun executeJudgement(j: Judgement) {
        val results = mutableListOf<String>()
        if (j.addTodos.isNotEmpty()) {
            j.addTodos.forEach { store.add(it) }
            results += "记下了：${j.addTodos.joinToString("、")}"
        }
        if (j.completeIds.isNotEmpty()) {
            store.completeIds(j.completeIds).forEach { results += "划掉啦：${it.text}" }
        } else {
            j.completeHint?.takeIf { it.isNotBlank() }?.let { hint ->
                val t = store.completeBestMatch(hint)
                results += if (t != null) "划掉啦：${t.text}" else "没找到和「$hint」对应的待办"
            }
        }
        j.calendar?.let { ev ->
            val err = Actions.addCalendarEvent(this, ev.title, ev.startMs, ev.minutes)
            results += if (err == null) "已加入日历：${ev.title}" else "没能加入日历：$err"
        }
        j.alarm?.let { a ->
            results += if (Actions.setAlarm(this, a.hour, a.minute, a.label)) {
                "闹钟设在 %02d:%02d".format(a.hour, a.minute)
            } else {
                "没能设闹钟，手机上没有可用的时钟应用"
            }
        }
        refreshList()
        // 有失败时如实说失败，不用模型的"好的"去掩盖
        val failed = results.any { it.startsWith("没") }
        pet.setMood(if (failed) PetView.Mood.THINKING else PetView.Mood.HAPPY)
        if (!failed) pet.react(PetView.Action.CELEBRATE)
        val reply = when {
            failed -> results.joinToString("，")
            else -> j.reply ?: results.joinToString("，").ifBlank { "好的" }
        }
        say(reply)
        main.postDelayed({ if (!exiting && !speaking) resetMood() }, 3000)
    }

    private fun confirmPending() {
        val j = pendingJudgement ?: return
        pendingJudgement = null
        confirmBar.visibility = View.GONE
        executeJudgement(j)
    }

    private fun cancelPending() {
        pendingJudgement = null
        confirmBar.visibility = View.GONE
        say("好的，那我不记")
    }

    private fun describe(j: Judgement): String {
        j.calendar?.let {
            val time = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it.startMs))
            return "加入日历：${it.title}（$time）"
        }
        j.alarm?.let { return "设闹钟到 %02d:%02d".format(it.hour, it.minute) }
        return when {
            j.addTodos.isNotEmpty() -> "记下：${j.addTodos.joinToString("、")}"
            j.completeIds.isNotEmpty() || !j.completeHint.isNullOrBlank() ->
                "把「${j.completeHint ?: "这件事"}」划掉"
            else -> "这样处理"
        }
    }

    // ---------- 说话 ----------

    private fun onTtsInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return
        tts?.language = Locale.SIMPLIFIED_CHINESE
        tts?.setSpeechRate(1.05f)
        tts?.setPitch(1.2f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post { onSpeechEnded() }
            }

            override fun onError(utteranceId: String?) {
                main.post { onSpeechEnded() }
            }
        })
    }

    private fun say(text: String) {
        bubble.text = text
        val engine = tts
        if (!ttsReady || engine == null) return
        speaking = true
        MemoryService.muteForTts(true)
        pet.setTalking(true)
        updateListening()   // 说话期间关麦克风
        val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}")
        if (r == TextToSpeech.ERROR) {
            speaking = false
            MemoryService.muteForTts(false)
            pet.setTalking(false)
            updateListening()
        } else {
            // 保险：部分手机的 TTS 不回调"说完了"，超时后自己恢复麦克风
            main.removeCallbacks(speakWatchdog)
            main.postDelayed(speakWatchdog, (text.length * 260L + 2000L).coerceAtMost(25_000L))
        }
    }

    private val speakWatchdog = Runnable { if (speaking) onSpeechEnded() }

    private fun onSpeechEnded() {
        main.removeCallbacks(speakWatchdog)
        speaking = false
        MemoryService.muteForTts(false)
        pet.setTalking(false)
        if (exiting) return
        updateListening()
    }

    // ---------- 退出 ----------

    /** 拔掉充电线：宠物闭眼缩小淡出，然后关闭。 */
    private fun beginExit() {
        if (exiting) return
        exiting = true
        listening = false
        asr.stop()
        tts?.stop()
        bubble.text = "先休息一会儿，下次见。"
        todoSheet.animate().alpha(0f).translationY(dp(14).toFloat()).setDuration(650).start()
        clockTime.animate().alpha(0f).setDuration(750).start()
        clockDate.animate().alpha(0f).setDuration(750).start()
        pet.playBye { finishSafely() }
        main.postDelayed({ finishSafely() }, 1500)
    }

    private fun finishSafely() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    companion object {
        /** 超过这个时间没看到人，才认为不在看（避免眨眼、低头短暂丢失） */
        private const val LOOK_TIMEOUT_MS = 3_000L
        /** 语义判断把握低于此值，先向用户确认 */
        private const val CONFIRM_BELOW = 0.75f
        private const val CLOCK_INTERVAL_MS = 20_000L
        /** 语义判断超过这个时间还没结果，就放弃这次，恢复听觉 */
        private const val THINK_TIMEOUT_MS = 20_000L
        private const val MAX_START_RETRIES = 5
        private val FILLER = Regex("[嗯啊呃额哦诶唉]+")
        private val YES_WORDS = listOf("对", "是", "好", "嗯", "行", "记")
        private val NO_WORDS = listOf("不", "别", "算了", "取消")
        private val GREETINGS = listOf("嗯？我在呢", "想跟我说什么呀？", "嘿嘿，被你发现啦")

        fun launch(context: Context) {
            val intent = Intent(context, DeskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }
    }
}
