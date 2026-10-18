package com.deskbuddy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val PI_F = 3.1415927f

/** 可选的宠物形象。 */
enum class PetStyle(val label: String, val body: Int, val light: Int, val dark: Int, val accent: Int) {
    // 保留原来的枚举键，兼容用户已保存的选择；每个键对应独立轮廓与气质。
    CAT("月光球", 0xFFB9CFE0.toInt(), 0xFFEAF8FF.toInt(), 0xFF273B54.toInt(), 0xFFD7E3D3.toInt()),
    BEAR("云团", 0xFFD9D3F1.toInt(), 0xFFF6F0FF.toInt(), 0xFF494969.toInt(), 0xFFF1D8E9.toInt()),
    BUNNY("小幽灵", 0xFFC9DCD3.toInt(), 0xFFE5FFF4.toInt(), 0xFF3C5D58.toInt(), 0xFFC9EEFF.toInt()),
    FOX("星芽", 0xFFECC18C.toInt(), 0xFFFFEAC9.toInt(), 0xFF805A3E.toInt(), 0xFFFFE2B5.toInt()),
    BLOB("水滴", 0xFF6FD3B0.toInt(), 0xFFD6FFEB.toInt(), 0xFF2F8F6F.toInt(), 0xFFFFFFFF.toInt());

    val personality: String get() = when (this) {
        CAT -> "安静又温柔，陪你慢慢来"
        BEAR -> "软绵绵的，适合一起放空"
        BUNNY -> "有点害羞，但一直在这里"
        FOX -> "把每一件小事，都当成小小的胜利"
        BLOB -> "好奇又灵动，认真听你说"
    }

    val greeting: String get() = when (this) {
        CAT -> "嗯，我在。今天过得怎么样？"
        BEAR -> "靠过来歇一会儿，也很好。"
        BUNNY -> "被你发现了。能陪着你，我很开心。"
        FOX -> "你来啦！今天有什么值得开心的小事？"
        BLOB -> "我在听，慢慢说就好。"
    }

    fun next(): PetStyle = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromName(name: String?): PetStyle = values().firstOrNull { it.name == name } ?: CAT
    }
}

/** 记住用户选的形象，只存在本机。 */
object PetPrefs {
    private const val FILE = "pet"
    private const val KEY = "style"

    fun load(context: Context): PetStyle = try {
        PetStyle.fromName(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
    } catch (e: Exception) {
        PetStyle.CAT
    }

    fun save(context: Context, style: PetStyle) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, style.name).apply()
        } catch (_: Exception) {
        }
    }
}

/**
 * 有生命力的宠物。
 *
 * - 无手脚的玻璃伙伴，通过表情、变形和悬浮传达情绪。
 * - 打招呼、雀跃、伸展等动作各有时长，结束后自然回归。
 * - 没有互动时，它会按注视状态随机做动作，所以不会一直一个姿势。
 * - 休息时慢慢合眼、降低浮动幅度。
 */
class PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mood { SLEEP, IDLE, LISTENING, HAPPY, THINKING }

    enum class Action(val label: String, val dur: Float) {
        WAVE("打招呼", 2.2f),
        HOP("跳一跳", 1.4f),
        SPIN("转圈", 1.0f),
        STRETCH("伸懒腰", 1.8f),
        YAWN("打哈欠", 2.2f),
        CLAP("雀跃", 1.8f),
        DANCE("跳舞", 2.6f),
        PEEK("偷看", 1.8f),
        SHY("害羞", 2.0f),
        CELEBRATE("庆祝", 2.0f),
        READ("翻小书", 11f),
        SKETCH("画几笔", 10f),
        BUBBLES("玩泡泡", 9f),
        STACK("叠石子", 10f)
    }

    /** 当前值朝目标值平滑追随。所有动态都靠它，所以切换是连续的。 */
    private class Spring(init: Float) {
        var v = init
        var target = init
        fun step(k: Float) {
            v += (target - v) * k
        }
    }

    var style: PetStyle = PetStyle.CAT
        set(value) {
            field = value
            invalidate()
        }

    var mood: Mood = Mood.IDLE
        private set

    // 表情与身体
    private val eyeS = Spring(1f)
    private val smileS = Spring(0.3f)
    private val mouthS = Spring(0f)
    private val earS = Spring(0f)
    private val blushS = Spring(0.25f)
    private val sleepS = Spring(0f)
    private val sparkleS = Spring(0f)
    private val pupXS = Spring(0f)
    private val pupYS = Spring(0f)
    private val sqXS = Spring(1f)
    private val sqYS = Spring(1f)
    private val hopS = Spring(0f)
    private val yawnS = Spring(0f)
    private val leanS = Spring(0f)   // 身体转向用户的角度（度）
    private val glowS = Spring(0f)   // 眼睛光晕强度

    // 模式的目标值（由 setMood / setGaze 设置）
    private var mEye = 1f
    private var mSmile = 0.3f
    private var mMouth = 0f
    private var mEar = 0f
    private var mBlush = 0.25f
    private var mSleep = 0f
    private var mSparkle = 0f
    private var mPupX = 0f
    private var mPupY = 0f
    private var leanTarget = 0f
    private var saccX = 0f          // 注视时眼睛的轻微游移
    private var saccY = 0f
    private var saccT = 1f

    // 动作覆盖（动作期间生效，null 表示不覆盖）
    private var aEye: Float? = null
    private var aSmile: Float? = null
    private var aBlush: Float? = null
    private var aSparkle: Float? = null
    private var aPupX: Float? = null
    private var aPupY: Float? = null
    private var propAction: Action? = null
    private val propOpacity = Spring(0f)
    val currentActivityLabel: String get() = action?.takeIf { it in BUSY_ACTIONS }?.label.orEmpty()

    private var action: Action? = null
    private var actionT = 0f
    private var idleIn = 2.2f
    private var spinAngle = 0f

    private var attention = 0f
    private var lastNotice = -10f
    private var lastGazeAt = -10f
    private var gazing = false
    private var talking = false
    private var time = 0f
    private var pop = 0f
    private var blinkT = -1f
    private var nextBlink = 2.5f
    private var scaleAnim = 1f
    private var arrivalY = 0f
    private var transition: ValueAnimator? = null
    private var lastNs = 0L
    private var running = false

    private val ink = 0xFF273B54.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ink
    }
    private val frame = Runnable { tick() }

    // ---------- 对外接口 ----------

    fun setMood(m: Mood) {
        if (m == Mood.SLEEP) {
            action = null
            clearOverrides()
        }
        mood = m
        when (m) {
            Mood.SLEEP -> { mEye = 0f; mSmile = 0f; mMouth = 0f; mEar = -0.35f; mBlush = 0f; mSleep = 1f; mSparkle = 0f }
            Mood.IDLE -> { mEye = 1f; mSmile = 0.3f; mMouth = 0f; mEar = 0f; mBlush = 0.25f; mSleep = 0f; mSparkle = 0f }
            Mood.LISTENING -> { mEye = 1.12f; mSmile = 0.1f; mMouth = 0.22f; mEar = 0.7f; mBlush = 0.35f; mSleep = 0f; mSparkle = 0f }
            Mood.HAPPY -> { mEye = 0.4f; mSmile = 1f; mMouth = 0.6f; mEar = 0.45f; mBlush = 1f; mSleep = 0f; mSparkle = 1f }
            Mood.THINKING -> { mEye = 1f; mSmile = -0.25f; mMouth = 0f; mEar = -0.15f; mBlush = 0f; mSleep = 0f; mSparkle = 0f }
        }
        if (m == Mood.THINKING) {
            mPupX = 0.55f
            mPupY = -0.7f
        } else if (!gazing) {
            mPupX = 0f
            mPupY = 0f
        }
        invalidate()
    }

    /**
     * 注视时眼睛看向用户所在的位置（cx/cy 为人脸在画面中的相对位置，-1 到 1），
     * 身体也会微微转向用户；不注视时回到中心。
     */
    fun setGaze(yawDeg: Float, pitchDeg: Float, looking: Boolean, cx: Float = 0f, cy: Float = 0f) {
        if (looking) lastGazeAt = time
        val stableLooking = looking || time - lastGazeAt < 1.2f
        if (stableLooking && !gazing && time - lastNotice > 5f) {
            lastNotice = time
            attention = 1f
            pop = 0.45f
            if (mood != Mood.THINKING && !talking) playAction(Action.WAVE)
        }
        if (gazing && !stableLooking) idleIn = min(idleIn, 1f)
        gazing = stableLooking
        leanTarget = if (looking) (cx * 4f).coerceIn(-5f, 5f) else 0f
        if (mood == Mood.THINKING) return
        if (looking) {
            mPupX = (cx * 0.85f + yawDeg / 40f).coerceIn(-1f, 1f)
            mPupY = (cy * 0.6f - pitchDeg / 40f).coerceIn(-1f, 1f)
        } else {
            mPupX = 0f
            mPupY = 0f
        }
    }

    fun setTalking(on: Boolean) {
        talking = on
    }

    /** 做一个动作。睡着时不会动。 */
    fun react(a: Action) = playAction(a)

    /** 醒来：睁眼并伸个懒腰；本来就醒着则只是眼睛弹一下。 */
    fun playWakeUp() {
        val wasSleeping = mood == Mood.SLEEP
        pop = 1f
        if (wasSleeping) {
            setMood(Mood.IDLE)
            playAction(Action.STRETCH)
        } else {
            eyeS.v = min(eyeS.v, 0.15f)
        }
    }

    /** 先聚光，再轻轻浮起；无弹跳式缩放。 */
    fun playEnter(awake: Boolean = true) {
        transition?.cancel()
        if (awake) playWakeUp() else setMood(Mood.IDLE)
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                scaleAnim = 0.86f + t * 0.14f
                arrivalY = (1f - t) * 0.3f
                alpha = t
            }
            start()
        }
    }

    /** 告别时慢慢合眼、回落，完整淡出后才结束桌面。 */
    fun playBye(onEnd: () -> Unit) {
        transition?.cancel()
        setTalking(false)
        setMood(Mood.SLEEP)
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                scaleAnim = 1f - 0.12f * t
                arrivalY = 0.34f * t
                alpha = 1f - t
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) onEnd() }
            })
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastNs = 0L
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(frame)
        transition?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        running = visibility == VISIBLE && isAttachedToWindow
        removeCallbacks(frame)
        if (running) {
            lastNs = 0L
            postOnAnimation(frame)
        }
    }

    // ---------- 动作 ----------

    private fun playAction(a: Action) {
        if (mood == Mood.SLEEP) return
        restPose()
        action = a
        actionT = 0f
        idleIn = nextGap()
    }

    private fun clearOverrides() {
        aEye = null
        aSmile = null
        aBlush = null
        aSparkle = null
        aPupX = null
        aPupY = null
        spinAngle = 0f
    }

    private fun pickAction(): Action = if (gazing) Action.PEEK else when (style) {
        PetStyle.CAT -> listOf(Action.READ, Action.STACK).random()
        PetStyle.BEAR -> listOf(Action.READ, Action.SKETCH).random()
        PetStyle.BUNNY -> listOf(Action.SKETCH, Action.BUBBLES).random()
        PetStyle.FOX -> listOf(Action.STACK, Action.BUBBLES).random()
        PetStyle.BLOB -> listOf(Action.BUBBLES, Action.STACK).random()
    }

    private fun nextGap(): Float = if (gazing) 12f + Random.nextFloat()*8f else 1.8f+Random.nextFloat()*1.8f

    private fun updateAction(dt: Float) {
        clearOverrides()
        val a = action
        if (a == null) {
            restPose()
            if (mood == Mood.IDLE && !talking) {
                idleIn -= dt
                if (idleIn <= 0f) playAction(pickAction())
            }
            return
        }
        actionT += dt
        val p = actionT / a.dur
        if (p >= 1f) {
            action = null
            restPose()
            idleIn = nextGap()
            return
        }
        applyAction(a, p.coerceIn(0f, 1f), actionT)
    }

    /** 每个动作结束后恢复圆润、平稳的悬浮姿态。 */
    private fun restPose() {
        hopS.target = 0f
        sqXS.target = 1f
        sqYS.target = 1f
        yawnS.target = 0f
    }

    private fun applyAction(a: Action, p: Float, t: Float) {
        val envelope = sin(p * PI_F)
        when (a) {
            Action.WAVE -> { aSmile = 0.9f; sqYS.target = 1f + envelope * 0.035f }
            Action.HOP -> {
                val lift = abs(sin(t * 5.2f))
                hopS.target = lift * 0.23f * envelope
                sqXS.target = 1f + 0.06f * (1f-lift) * envelope
                sqYS.target = 1f - 0.07f * (1f-lift) * envelope
                aSmile = 0.8f
            }
            Action.SPIN -> { spinAngle = 360f * p*p*(3f-2f*p); aSmile = 0.9f }
            Action.STRETCH -> { sqXS.target = 1f-0.05f*envelope; sqYS.target = 1f+0.10f*envelope; aEye = 0.65f }
            Action.YAWN -> { yawnS.target = envelope; aEye = 0.3f; sqYS.target = 1f+envelope*0.04f }
            Action.CLAP -> { hopS.target = abs(sin(t*7f))*0.09f*envelope; aSmile = 0.9f; aSparkle = envelope }
            Action.DANCE -> { hopS.target = abs(sin(t*5f))*0.09f; aSmile = 1f }
            Action.PEEK -> { aPupX = sin(t*2.2f)*0.65f; aEye = 1.05f; aSmile = 0.4f }
            Action.SHY -> { aBlush = 1f; aEye = 0.65f; aSmile = 0.7f; sqXS.target = 1f-0.025f*envelope }
            Action.CELEBRATE -> { hopS.target = abs(sin(t*6f))*0.20f*envelope; aSmile = 1f; aEye = 0.4f; aBlush = 0.9f; aSparkle = 1f }
            Action.READ -> { aPupX = sin(t*0.9f)*0.35f; aPupY = 0.85f; aEye = 0.9f; aSmile = 0.2f }
            Action.SKETCH -> { aPupX = sin(t*2f)*0.4f; aPupY = 0.9f; aEye = 0.85f; aSmile = 0.45f }
            Action.BUBBLES -> { aPupX = 0.85f; aPupY = sin(t*1.2f)*0.5f-0.3f; aEye = 1.08f; aSmile = 0.6f }
            Action.STACK -> { aPupX = 0.8f; aPupY = 0.75f; aEye = 0.9f; aSmile = 0.3f }
        }
    }

    // ---------- 帧循环 ----------

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else min(0.05f, (now - lastNs) / 1_000_000_000f)
        lastNs = now
        time += dt

        updateAction(dt)

        // 动作覆盖优先于模式目标
        eyeS.target = aEye ?: mEye
        smileS.target = aSmile ?: mSmile
        mouthS.target = mMouth
        earS.target = mEar
        blushS.target = aBlush ?: mBlush
        sleepS.target = mSleep
        sparkleS.target = aSparkle ?: mSparkle
        // 注视时眼睛有很轻微的游移，像真的在看人，而不是定格
        saccT -= dt
        if (saccT <= 0f) {
            saccT = 0.9f + Random.nextFloat() * 0.9f
            saccX = if (gazing) (Random.nextFloat() - 0.5f) * 0.25f else 0f
            saccY = if (gazing) (Random.nextFloat() - 0.5f) * 0.18f else 0f
        }
        pupXS.target = (aPupX ?: mPupX) + saccX
        pupYS.target = (aPupY ?: mPupY) + saccY
        leanS.target = leanTarget
        glowS.target = when {
            mood == Mood.SLEEP -> 0f
            gazing -> 1f
            else -> 0.3f
        }

        if (action in BUSY_ACTIONS) propAction = action
        propOpacity.target = if (action in BUSY_ACTIONS) 1f else 0f
        propOpacity.step(1f-exp(-8f*dt))
        val k = 1f - exp(-9f * dt)
        val kl = 1f - exp(-14f * dt)
        eyeS.step(k)
        smileS.step(k)
        mouthS.step(k)
        earS.step(k)
        blushS.step(k)
        sleepS.step(k)
        sparkleS.step(k)
        pupXS.step(k)
        pupYS.step(k)
        hopS.step(kl)
        sqXS.step(kl)
        sqYS.step(kl)
        yawnS.step(kl)
        leanS.step(kl)
        glowS.step(k)

        pop = max(0f, pop - dt * 2.2f)
        attention = max(0f, attention - dt / 1.7f)

        nextBlink -= dt
        if (blinkT < 0f && nextBlink <= 0f) {
            blinkT = 0f
            nextBlink = 2.2f + Random.nextFloat() * 3f
        }
        if (blinkT >= 0f) {
            blinkT += dt / 0.18f
            if (blinkT >= 1f) blinkT = -1f
        }

        invalidate()
        if (running) postOnAnimation(frame)
    }

    // ---------- 绘制 ----------

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = min(w / 2.8f, h / 3.1f)

        val sleepK = sleepS.v.coerceIn(0f, 1f)
        val pace = when (style) { PetStyle.BEAR -> 0.72f; PetStyle.BLOB -> 1.18f; PetStyle.BUNNY -> 0.86f; else -> 1f }
        val breath = 1f + sin(time * 2.1f * pace) * 0.012f
        val base = breath * (1f + pop * 0.1f) * scaleAnim
        val sx = base * sqXS.v * (1f + 0.1f * sleepK)
        val sy = base * sqYS.v * (1f - 0.10f * sleepK)
        val hopY = -hopS.v * r - sin(attention * PI_F) * r * 0.15f
        val bob = sin(time * 1.6f * pace) * r * 0.04f * (1f - sleepK)
        val lieY = (sleepK * 0.14f + arrivalY) * r
        val greet = if (action == Action.WAVE) sin(actionT * 5.4f) * 7f * sin((actionT / Action.WAVE.dur) * PI_F) else if (action == Action.DANCE) sin(actionT * 5f) * 6f else 0f

        drawShadow(c, r, hopS.v)

        c.save()
        c.translate(w / 2f, h / 2f + bob + hopY + lieY)
        c.rotate(sin(time * 1.1f) * 1.4f * (1f - sleepK) + spinAngle + leanS.v + greet)
        c.scale(sx, sy)
        drawBody(c, r)
        drawFace(c, r)
        drawProps(c, r)
        c.restore()

        drawSparkles(c, w, h, r)
        drawZzz(c, w, h, r)
        drawNotice(c, w, h, r)
    }

    /** 活动有真实可见的道具；转向用户时，原道具在约 300ms 内收起。 */
    private fun drawProps(c: Canvas, r: Float) {
        val opacity = propOpacity.v.coerceIn(0f,1f)
        if (opacity < 0.01f) return
        val count = c.saveLayerAlpha(-r*1.5f,-r*1.5f,r*1.5f,r*1.6f,(opacity*255).toInt())
        c.translate(0f,(1f-opacity)*r*0.15f)
        paint.shader = null
        strokePaint.shader = null
        when (propAction) {
            Action.READ -> {
                // 一本打开的袖珍书，有封皮、页脊和缓慢翻起的一页。
                paint.color = 0xFF496652.toInt()
                c.drawPath(Path().apply {
                    moveTo(-r*0.56f,r*0.44f); quadTo(-r*0.25f,r*0.37f,0f,r*0.51f)
                    quadTo(r*0.25f,r*0.37f,r*0.56f,r*0.44f); lineTo(r*0.52f,r*0.91f)
                    quadTo(r*0.23f,r*0.86f,0f,r*0.98f);quadTo(-r*0.23f,r*0.86f,-r*0.52f,r*0.91f);close()
                },paint)
                paint.color = 0xFFF0EBD8.toInt()
                c.drawPath(Path().apply {
                    moveTo(-r*0.50f,r*0.46f);quadTo(-r*0.23f,r*0.43f,0f,r*0.55f)
                    quadTo(r*0.23f,r*0.43f,r*0.50f,r*0.46f);lineTo(r*0.46f,r*0.85f)
                    quadTo(r*0.23f,r*0.82f,0f,r*0.91f);quadTo(-r*0.23f,r*0.82f,-r*0.46f,r*0.85f);close()
                },paint)
                strokePaint.color = 0x55767D62;strokePaint.strokeWidth = r*0.008f
                c.drawLine(0f,r*0.55f,0f,r*0.91f,strokePaint)
                for(i in 0..2) for(side in floatArrayOf(-1f,1f)) {
                    c.drawLine(side*r*0.09f,r*(0.62f+i*0.075f),side*r*0.39f,r*(0.57f+i*0.075f),strokePaint)
                }
                val flip = ((time*0.32f)%1f)
                if(flip>0.62f) {
                    val x = r*0.45f*cos((flip-0.62f)/0.38f*PI_F)
                    paint.color=0xFFF8F5E8.toInt()
                    c.drawPath(Path().apply { moveTo(0f,r*0.55f);quadTo(x,r*0.22f,x,r*0.52f);lineTo(x,r*0.84f);quadTo(x*0.5f,r*0.80f,0f,r*0.91f);close() },paint)
                }
            }
            Action.SKETCH -> {
                c.save();c.rotate(-8f,0f,r*0.7f)
                paint.color=0xFF7F8D7A.toInt();c.drawRoundRect(RectF(-r*0.53f,r*0.43f,r*0.53f,r*0.98f),r*0.035f,r*0.035f,paint)
                paint.color=0xFFF1EDDD.toInt();c.drawRect(-r*0.47f,r*0.46f,r*0.47f,r*0.93f,paint)
                strokePaint.color=0xFF71917D.toInt();strokePaint.strokeWidth=r*0.015f
                c.drawPath(Path().apply { moveTo(-r*0.23f,r*0.77f);quadTo(-r*0.35f,r*0.50f,-r*0.04f,r*0.60f);quadTo(r*0.18f,r*0.68f,r*0.06f,r*0.81f) },strokePaint)
                c.restore()
                val px=r*(0.12f+sin(time*2f)*0.17f);val py=r*(0.64f+cos(time*3f)*0.05f)
                strokePaint.color=0xFFB7925C.toInt();strokePaint.strokeWidth=r*0.055f
                c.drawLine(px,py,px+r*0.31f,py-r*0.32f,strokePaint)
                paint.color=0xFF354D40.toInt();c.drawCircle(px,py,r*0.025f,paint)
            }
            Action.BUBBLES -> {
                for(i in 0..2) {
                    val bx=r*(0.88f+0.15f*sin(time*0.8f+i));val by=r*(-0.25f+0.52f*sin(time*0.7f+i*1.1f))
                    val br=r*(0.11f+i*0.035f)
                    paint.color=0x286A9C90;c.drawCircle(bx,by,br,paint)
                    strokePaint.color=0xAADDFFF1.toInt();strokePaint.strokeWidth=r*0.012f;c.drawCircle(bx,by,br,strokePaint)
                    strokePaint.color=0xA0648C79.toInt();strokePaint.strokeWidth=r*0.006f
                    c.drawArc(RectF(bx-br,by-br,bx+br,by+br),25f,100f,false,strokePaint)
                    paint.color=0xDCFFFFFF.toInt();c.drawCircle(bx-br*0.35f,by-br*0.4f,br*0.18f,paint)
                }
            }
            Action.STACK -> {
                for(i in 0..2) {
                    val x=r*(0.89f+sin(time*0.9f+i)*0.03f)
                    val y=r*(0.92f-i*0.18f)
                    val rx=r*(0.26f-i*0.045f)
                    paint.color=intArrayOf(0xFF879A88.toInt(),0xFFB2BEA4.toInt(),0xFFD8D9BC.toInt())[i]
                    c.drawOval(RectF(x-rx,y-r*0.095f,x+rx,y+r*0.08f),paint)
                    strokePaint.color=0x80FFFFFF.toInt();strokePaint.strokeWidth=r*0.008f
                    c.drawArc(RectF(x-rx*0.8f,y-r*0.07f,x+rx*0.8f,y+r*0.04f),210f,80f,false,strokePaint)
                }
                paint.color=0xFFC0C6B0.toInt()
                val y=r*(0.23f+sin(time*1.8f)*0.06f)
                c.drawOval(RectF(r*0.75f,y-r*0.07f,r*1.05f,y+r*0.07f),paint)
            }
            else -> Unit
        }
        paint.shader=null;paint.color=Theme.LABEL_2
        paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        paint.textSize=r*0.135f;paint.textAlign=Paint.Align.CENTER
        c.drawText(propAction?.label.orEmpty(),0f,r*1.43f,paint)
        paint.textAlign=Paint.Align.LEFT
        c.restoreToCount(count)
    }

    private fun drawNotice(c: Canvas, w: Float, h: Float, r: Float) {
        if (attention <= 0.01f) return
        val x = w/2f + r*1.13f
        val y = h/2f - r*(0.55f+(1f-attention)*0.4f)
        paint.shader = null
        paint.color = withAlpha(0xFF6B8E72.toInt(), sin(attention*PI_F).coerceIn(0f,1f))
        c.save()
        c.translate(x,y)
        c.scale(r*0.14f,r*0.14f)
        c.drawPath(Path().apply {
            moveTo(0f,0.7f)
            cubicTo(-1.5f,-0.2f,-0.7f,-1.1f,0f,-0.5f)
            cubicTo(0.7f,-1.1f,1.5f,-0.2f,0f,0.7f)
        },paint)
        c.restore()
    }

    private fun drawShadow(c: Canvas, r: Float, lift: Float) {
        val k = (1f - 0.45f * lift).coerceIn(0.4f, 1f)
        c.save()
        c.translate(width / 2f, height / 2f + r * 1.12f)
        c.scale(k * scaleAnim, 0.16f * k * scaleAnim)
        paint.alpha = 255
        paint.shader = RadialGradient(
            0f, 0f, r * 1.05f,
            intArrayOf(0x39334332, 0x00334332), null, Shader.TileMode.CLAMP
        )
        c.drawCircle(0f, 0f, r * 1.05f, paint)
        paint.shader = null
        c.restore()
    }

    /** 一个完整的玻璃卵石：雾面内核、冷暖折射、双层菲涅尔边缘和弧形窗光。 */
    private fun drawBody(c: Canvas, r: Float) {
        c.save()
        c.scale(r, r)
        val silhouette = silhouette()
        c.clipPath(silhouette)
        paint.alpha = 255
        paint.shader = RadialGradient(-0.34f, -0.56f, 1.86f,
            intArrayOf(0xFFFBFCF6.toInt(), 0xFFD5E0DA.toInt(), 0xFF9CAFAB.toInt(), 0xFF6D8583.toInt()),
            floatArrayOf(0f, 0.37f, 0.74f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(-1.3f, -1.4f, 1.3f, 1.2f, paint)
        // 色彩从球体内部透出；保留五种配色。
        paint.alpha = 255
        paint.shader = RadialGradient(0.48f, 0.43f, 1.3f,
            intArrayOf(withAlpha(style.body, 0.8f), withAlpha(style.body, 0f)), null, Shader.TileMode.CLAMP)
        c.drawRect(-1.3f, -1.4f, 1.3f, 1.2f, paint)
        paint.alpha = 255
        paint.shader = RadialGradient(-0.65f, 0.64f, 1.05f,
            intArrayOf(0x387E9F8D, 0x007E9F8D), null, Shader.TileMode.CLAMP)
        c.drawRect(-1.3f, -1.4f, 1.3f, 1.2f, paint)
        paint.alpha = 255
        paint.shader = RadialGradient(0.74f, -0.05f, 0.95f,
            intArrayOf(withAlpha(style.accent, 0.25f), withAlpha(style.accent, 0f)), null, Shader.TileMode.CLAMP)
        c.drawRect(-1.3f, -1.4f, 1.3f, 1.2f, paint)
        // 折射的底边：薄暖光和一条冷色弧线。
        strokePaint.alpha = 255
        strokePaint.shader = LinearGradient(-1f, 0f, 1f, 0.8f,
            intArrayOf(0x0079C7DA, 0x60E5F9F2, 0x75F9E7CE, 0x00F9D9B3),
            null, Shader.TileMode.CLAMP)
        strokePaint.strokeWidth = 0.05f
        c.drawArc(RectF(-0.94f, -0.91f, 0.94f, 0.91f), 16f, 151f, false, strokePaint)
        strokePaint.shader = null
        // 一道大而柔的窗光，避免扁平的白色贴纸高光。
        paint.alpha = 255
        paint.shader = LinearGradient(-0.6f, -0.9f, -0.1f, -0.3f,
            intArrayOf(0xA6FFFFFF.toInt(), 0x08FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawPath(Path().apply {
            moveTo(-0.91f, -0.29f)
            cubicTo(-0.81f, -0.86f, -0.32f, -1.04f, 0.14f, -0.84f)
            cubicTo(-0.30f, -0.81f, -0.66f, -0.61f, -0.91f, -0.29f)
            close()
        }, paint)
        paint.shader = null
        strokePaint.strokeWidth = 0.018f
        strokePaint.color = 0x99FFFFFF.toInt()
        c.drawArc(RectF(-0.91f, -0.88f, 0.91f, 0.88f), 211f, 50f, false, strokePaint)
        strokePaint.color = 0x65FFFFFF
        strokePaint.strokeWidth = 0.007f
        c.drawArc(RectF(-0.85f, -0.83f, 0.85f, 0.83f), 211f, 55f, false, strokePaint)
        // 极少量内部微光，缓慢漂移，不在眼睛前闪烁。
        for (i in 0..4) {
            val x = -0.62f + i * 0.30f
            val y = 0.48f + sin(time * 0.45f + i * 1.7f) * 0.13f
            paint.color = withAlpha(0xFFF2FBFF.toInt(), 0.18f + 0.12f * sin(time + i))
            c.drawCircle(x, y, 0.009f + (i % 2) * 0.006f, paint)
        }
        c.restore()
        strokePaint.alpha = 255
        strokePaint.shader = LinearGradient(-r, -r, r, r,
            intArrayOf(0xC4FFFFFF.toInt(), 0x22FFFFFF, withAlpha(style.light, 0.5f)),
            null, Shader.TileMode.CLAMP)
        strokePaint.strokeWidth = r * 0.012f
        c.save()
        c.scale(r, r)
        strokePaint.strokeWidth = 0.012f
        // 在规范坐标内重新创建边缘渐变。
        strokePaint.alpha = 255
        strokePaint.shader = LinearGradient(-1f, -1f, 1f, 1f,
            intArrayOf(0xC4FFFFFF.toInt(), 0x22FFFFFF, withAlpha(style.light, 0.42f + glowS.v * 0.20f)), null, Shader.TileMode.CLAMP)
        c.drawPath(silhouette, strokePaint)
        c.restore()
        strokePaint.shader = null
    }

    /** 所有配色共用光照语言，但拥有真正不同的轮廓。 */
    private fun silhouette(): Path = Path().apply {
        when (style) {
            PetStyle.CAT -> addOval(RectF(-1f, -0.96f, 1f, 0.96f), Path.Direction.CW)
            PetStyle.BLOB -> {
                moveTo(0.12f, -1.23f)
                cubicTo(0.18f, -0.68f, 1.02f, -0.38f, 0.94f, 0.26f)
                cubicTo(0.86f, 1.23f, -0.88f, 1.23f, -0.95f, 0.30f)
                cubicTo(-1.02f, -0.27f, -0.26f, -0.63f, 0.12f, -1.23f)
                close()
            }
            PetStyle.BEAR -> {
                moveTo(-0.78f, 0.72f)
                cubicTo(-1.40f, 0.48f, -1.21f, -0.29f, -0.80f, -0.42f)
                cubicTo(-0.77f, -0.99f, -0.15f, -1.15f, 0.20f, -0.72f)
                cubicTo(0.68f, -0.96f, 1.05f, -0.49f, 0.94f, -0.14f)
                cubicTo(1.40f, 0.18f, 1.10f, 0.78f, 0.71f, 0.76f)
                cubicTo(0.44f, 1.02f, -0.38f, 0.96f, -0.78f, 0.72f)
                close()
            }
            PetStyle.BUNNY -> {
                moveTo(-0.86f, 0.14f)
                cubicTo(-0.90f, -1.31f, 0.90f, -1.31f, 0.86f, 0.14f)
                cubicTo(0.86f, 0.48f, 1.04f, 0.75f, 0.91f, 0.85f)
                cubicTo(0.77f, 0.99f, 0.62f, 0.64f, 0.46f, 0.82f)
                cubicTo(0.27f, 1.08f, 0.13f, 0.71f, -0.03f, 0.83f)
                cubicTo(-0.27f, 1.08f, -0.39f, 0.68f, -0.57f, 0.83f)
                cubicTo(-0.93f, 1.09f, -1.03f, 0.74f, -0.86f, 0.14f)
                close()
            }
            PetStyle.FOX -> {
                // 五个圆润的星角，二次曲线消除尖刺。
                val points = (0..9).map { i ->
                    val angle = -PI_F / 2f + i * PI_F / 5f
                    val rad = if (i % 2 == 0) 1.18f else 0.77f
                    cos(angle) * rad to sin(angle) * rad
                }
                moveTo((points[9].first + points[0].first)/2f, (points[9].second + points[0].second)/2f)
                points.forEachIndexed { i, pt ->
                    val next = points[(i+1)%10]
                    quadTo(pt.first, pt.second, (pt.first+next.first)/2f, (pt.second+next.second)/2f)
                }
                close()
            }
        }
    }

    private fun drawFace(c: Canvas, r: Float) {
        val blink = if (blinkT < 0f) 1f else (1f - sin(blinkT * PI_F)).coerceIn(0f, 1f)
        val openness = (eyeS.v * blink).coerceIn(0f, 1.2f)
        val sleeping = sleepS.v > 0.55f
        // 整张脸轻轻朝用户转动，瞳孔与身体分层移动。
        c.save()
        c.translate(pupXS.v * r * 0.07f, pupYS.v * r * 0.05f)
        for (side in floatArrayOf(-1f, 1f)) {
            val ex = side * r * 0.30f
            val ey = -r * 0.075f
            val er = r * 0.105f
            val wink = if (action == Action.WAVE && side > 0f) {
                val phase = (actionT / Action.WAVE.dur - 0.43f) / 0.085f
                1f - exp(-phase * phase)
            } else 1f
            val eyeOpen = openness * wink
            strokePaint.strokeWidth = r * 0.035f
            strokePaint.color = 0xFF2D463A.toInt()
            when {
                sleeping -> c.drawArc(RectF(ex-er, ey-r*0.05f, ex+er, ey+r*0.06f), 0f, 180f, false, strokePaint)
                smileS.v > 0.7f && eyeS.v < 0.7f -> c.drawArc(RectF(ex-er, ey-r*0.04f, ex+er, ey+r*0.09f), 190f, 160f, false, strokePaint)
                eyeOpen < 0.15f -> c.drawLine(ex-er, ey, ex+er, ey, strokePaint)
                else -> {
                    val hh = r * 0.155f * eyeOpen
                    val ix = ex + pupXS.v * r * 0.025f
                    val iy = ey + pupYS.v * r * 0.025f
                    paint.alpha = 255
                    paint.shader = LinearGradient(ix, iy-hh, ix, iy+hh,
                        intArrayOf(0xFF253C35.toInt(), 0xFF51675E.toInt()), null, Shader.TileMode.CLAMP)
                    c.drawRoundRect(RectF(ix-er, iy-hh, ix+er, iy+hh), er, er, paint)
                    paint.shader = null
                    paint.color = 0xEEFFFFFF.toInt()
                    c.drawOval(RectF(ix-er*0.48f, iy-hh*0.61f, ix+er*0.02f, iy-hh*0.17f), paint)
                    paint.color = 0x70E6F8FF
                    c.drawCircle(ix+er*0.40f, iy+hh*0.45f, er*0.18f, paint)
                }
            }
            // 很淡的桃色内晕，害羞时才明显。
            paint.alpha = 255
            paint.shader = RadialGradient(side*r*0.50f, r*0.21f, r*0.19f,
                intArrayOf(withAlpha(0xFFEFA7AB.toInt(), blushS.v*0.33f), 0x00EFA7AB), null, Shader.TileMode.CLAMP)
            c.drawCircle(side*r*0.50f, r*0.21f, r*0.19f, paint)
            paint.shader = null
        }
        val my = r * 0.23f
        val amount = when {
            yawnS.v > 0.05f -> yawnS.v * 0.8f
            talking -> 0.25f + 0.35f * (sin(time*12f)*0.5f+0.5f)
            else -> mouthS.v * 0.5f
        }
        if (amount > 0.18f && !sleeping) {
            paint.color = 0xFF2F3A51.toInt()
            c.drawOval(RectF(-r*0.075f, my-r*0.025f, r*0.075f, my+r*(0.025f+0.19f*amount)), paint)
        } else {
            strokePaint.color = 0xFF31483F.toInt()
            strokePaint.strokeWidth = r*0.026f
            c.drawPath(Path().apply {
                moveTo(-r*0.09f, my)
                quadTo(0f, my+r*(0.035f+smileS.v*0.10f), r*0.09f, my)
            }, strokePaint)
        }
        // 倾听时两侧浮起短弧；思考时是一颗绕行的光点。
        if (mood == Mood.LISTENING && !sleeping) {
            strokePaint.strokeWidth = r*0.014f
            strokePaint.color = withAlpha(style.light, 0.5f + 0.2f*sin(time*2.5f))
            for (side in floatArrayOf(-1f, 1f)) {
                c.drawArc(RectF(side*r*0.88f-r*0.15f, -r*0.26f, side*r*0.88f+r*0.15f, r*0.26f),
                    if (side > 0) -45f else 135f, 90f, false, strokePaint)
            }
        }
        if (mood == Mood.THINKING) {
            paint.color = 0xDDF9EEE0.toInt()
            c.drawCircle(sin(time*1.6f)*r*0.19f, -r*0.43f, r*0.025f, paint)
        }
        c.restore()
    }

    private fun drawSparkles(c: Canvas, w: Float, h: Float, r: Float) {
        val sparkle = sparkleS.v
        if (sparkle < 0.02f) return
        val cx = w / 2f
        val cy = h / 2f
        for (i in 0 until 3) {
            val ang = time * 0.8f + i * 2.1f
            val x = cx + cos(ang) * r * 1.35f
            val y = cy + sin(ang * 1.3f) * r * 0.9f - r * 0.2f
            val a = sparkle * (0.5f + 0.5f * sin(time * 5f + i))
            paint.color = withAlpha(0xFFD8F6EE.toInt(), a)
            val s = r * 0.045f
            c.drawPath(Path().apply {
                moveTo(x, y - s)
                quadTo(x, y, x + s, y)
                quadTo(x, y, x, y + s)
                quadTo(x, y, x - s, y)
                quadTo(x, y, x, y - s)
                close()
            }, paint)
        }
    }

    private fun drawZzz(c: Canvas, w: Float, h: Float, r: Float) {
        val sleep = sleepS.v
        if (sleep < 0.4f) return
        paint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        for (i in 0 until 3) {
            val p = (time * 0.35f + i / 3f) % 1f
            val x = w / 2f + r * (0.9f + p * 0.4f)
            val y = h / 2f - r * (0.6f + p * 1.0f)
            paint.color = withAlpha(0xFFBFD4FF.toInt(), (1f - p) * sleep)
            paint.textSize = r * (0.12f + p * 0.08f)
            c.drawText("z", x, y, paint)
        }
    }

    companion object {
        private val BUSY_ACTIONS = setOf(Action.READ, Action.SKETCH, Action.BUBBLES, Action.STACK)
    }

    private fun withAlpha(color: Int, a: Float): Int =
        Color.argb(
            (a.coerceIn(0f, 1f) * 255).toInt(),
            Color.red(color), Color.green(color), Color.blue(color)
        )
}
