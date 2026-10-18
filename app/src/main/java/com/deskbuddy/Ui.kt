package com.deskbuddy

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 设计 token，参考 Apple 的深色界面：
 * 纯黑底、分组面板、胶囊按钮、细分隔线、三级文字透明度。
 * 只有一个强调色（系统橙，与台灯和宠物的暖色呼应）；绿色只表示"正在听"。
 */
object Theme {
    val BG = 0xFFDCE1D9.toInt()          // 系统背景
    val GROUP = 0xFFE7EBE3.toInt()       // 分组面板
    val ELEVATED = 0xFFEEF0E9.toInt()    // 输入框、分段控件底色
    val FILL = 0xFFC4CEC2.toInt()        // 分段控件里被选中的一段
    val SEPARATOR = 0xFFBDC7BD.toInt()   // 细分隔线
    val LABEL = 0xFF253C35.toInt()
    val LABEL_2 = 0xFF58685F.toInt()     // 次要文字（60%）
    val LABEL_3 = 0xFF647369.toInt()     // 占位与提示（30%）
    val ACCENT = 0xFF365C4B.toInt()      // 系统橙
    val ACCENT_SOFT = 0x15365C4B.toInt() // 橙色淡底
    val GREEN = 0xFF365C4B.toInt()       // 系统绿：正在听

    // 旧名字保留，指向新颜色，避免其它文件大改
    val PAPER = LABEL
    val MUTED = LABEL_2
    val SURFACE = GROUP
    val AMBER = ACCENT
    val AMBER_SOFT = ACCENT_SOFT
    val LINE = SEPARATOR
    val MINT = GREEN
}

/** 字体层级：大标题用 Bold，正文用 Regular，数字和时间用 Light。 */
enum class Face { REGULAR, MEDIUM, LIGHT, BOLD }

fun face(f: Face): Typeface = when (f) {
    Face.REGULAR -> Typeface.create("sans-serif", Typeface.NORMAL)
    Face.MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    Face.LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    Face.BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** 圆角色块。strokeColor 为空则无描边。 */
fun Context.surface(fill: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

fun ripple(content: Drawable): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, null)

fun Context.label(size: Float, color: Int, f: Face = Face.REGULAR): TextView =
    TextView(this).apply {
        textSize = size
        setTextColor(color)
        typeface = face(f)
    }

/** 胶囊按钮的统一外观。 */
private fun Context.capsule(text: String, size: Float, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        typeface = face(Face.MEDIUM)
        setTextColor(textColor)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = ripple(surface(fill, 12))
        isClickable = true
        setOnClickListener { onClick() }
    }

/** 主要动作：系统橙实心胶囊。每屏只放一个。 */
fun Context.primaryButton(text: String, onClick: () -> Unit): TextView =
    capsule(text, 17f, Theme.ACCENT, 0xFFF4F6EF.toInt(), onClick)

/** 次要动作：橙色淡底胶囊，文字是橙色。 */
fun Context.tintedButton(text: String, onClick: () -> Unit): TextView =
    capsule(text, 17f, Theme.ACCENT_SOFT, Theme.ACCENT, onClick)

/** 普通按钮：灰色胶囊，文字是白色。 */
fun Context.ghostButton(text: String, size: Float = 17f, onClick: () -> Unit): TextView =
    capsule(text, size, Theme.ELEVATED, Theme.LABEL, onClick)

/** 待办状态圆点：未完成为空心圈，完成为橙色实心。形状本身表达状态。 */
fun Context.checkCircle(done: Boolean): View = View(this).apply {
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (done) {
            setColor(Theme.ACCENT)
        } else {
            setColor(0x00000000)
            setStroke(dp(2), Theme.LABEL_3)
        }
    }
}

/** 分组面板：圆角深灰底，里面的行之间用细线分隔。 */
fun Context.groupCard(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = surface(Theme.GROUP, 20)
    clipToOutline = true
}

fun Context.hairline(): View = View(this).apply { setBackgroundColor(Theme.SEPARATOR) }

/** 把若干行放进一个分组面板，行与行之间自动加左侧缩进的细线。 */
fun Context.groupOf(vararg rows: View): LinearLayout = groupCard().apply {
    rows.forEachIndexed { i, row ->
        if (i > 0) {
            addView(hairline(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = dp(16)
            })
        }
        addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}

/** 列表里的一行：左边标题，右边可选的值；可点击时有按压反馈。 */
fun Context.formRow(
    title: String,
    value: String? = null,
    titleColor: Int = Theme.LABEL,
    valueColor: Int = Theme.LABEL_2,
    onClick: (() -> Unit)? = null
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(50)
    setPadding(dp(16), dp(12), dp(16), dp(12))
    if (onClick != null) {
        isClickable = true
        background = ripple(surface(0x00000000, 0))
        setOnClickListener { onClick() }
    }
    addView(label(17f, titleColor).apply { text = title },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    if (value != null) {
        addView(label(17f, valueColor).apply {
            text = value
            gravity = Gravity.END
        })
    }
}

/** 给内容加左右留白，用于放进分组面板或页面里。 */
fun Context.padded(v: View, h: Int = 16, vPad: Int = 12): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(h), dp(vPad), dp(h), dp(vPad))
    addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
}

/**
 * 分段控件：一排等宽的选项，选中的那一段是浅灰实心。
 * current 每次都重新读取，点击后自己刷新。
 */
fun Context.segmented(
    options: List<Pair<String, String>>,
    current: () -> String,
    onPick: (String) -> Unit
): LinearLayout {
    val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    fun render() {
        box.removeAllViews()
        options.forEach { (key, title) ->
            val selected = key == current()
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            cell.addView(label(14f, if (selected) Theme.LABEL else Theme.LABEL_2,
                if (selected) Face.MEDIUM else Face.REGULAR).apply {
                text = title
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(2), dp(10), dp(2), dp(10))
                setOnClickListener { onPick(key); render() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
            cell.addView(View(this).apply {
                setBackgroundColor(if (selected) Theme.ACCENT else 0x00000000)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))
            box.addView(cell, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
    }
    render()
    box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
    return box
}

/** 角色舞台不叠加装饰光晕；材质光照全部由角色自身表达。 */
class LampGlow(context: Context) : View(context) {
    var cx = 0.5f
    var cy = 0.4f
}
