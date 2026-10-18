package com.deskbuddy

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一次注视检测的结果。yaw/pitch 为头部朝向角（度），looking 表示是否正对屏幕。
 * faceSeen / faceWidth 用来告诉用户"没看到脸"还是"脸看到了但角度偏了"。
 */
data class GazeSample(
    val looking: Boolean,
    val yaw: Float,
    val pitch: Float,
    val faceSeen: Boolean = false,
    val faceWidth: Int = 0,
    /** 人脸中心在画面中的位置，-1 到 1，用来让眼睛看向用户所在的方向 */
    val cx: Float = 0f,
    val cy: Float = 0f,
    val error: String? = null
)

/**
 * 注视状态的分发中心。DeskService 每帧发布结果，DeskActivity 订阅。
 * 监听器在主线程被调用。
 */
object Gaze {
    /** 桌面界面是否在前台，用来决定要不要弹出全屏唤醒 */
    private val visibleOwners = mutableSetOf<Any>()
    @Volatile var deskVisible = false
        private set
    fun show(owner: Any) { visibleOwners.add(owner); deskVisible = visibleOwners.isNotEmpty() }
    fun hide(owner: Any) {
        visibleOwners.remove(owner);deskVisible=visibleOwners.isNotEmpty()
        val ctx=(owner as? android.content.Context)?.applicationContext
        if(ctx!=null && !deskVisible) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if(!deskVisible)ctx.stopService(android.content.Intent(ctx,DeskService::class.java))
        },500)
    }

    private val listeners = CopyOnWriteArrayList<(GazeSample) -> Unit>()

    fun publish(sample: GazeSample) {
        listeners.forEach { it(sample) }
    }

    fun add(listener: (GazeSample) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (GazeSample) -> Unit) {
        listeners.remove(listener)
    }
}
