package com.deskbuddy
import kotlin.math.abs
/** 以人脸相对宽度判断距离，避免固定像素阈值随摄像头分辨率变化。头部朝向是注视的近似。 */
object GazeGeometry {
    fun isLooking(faceWidth: Float, imageWidth: Float, yaw: Float, pitch: Float): Boolean =
        imageWidth > 0f && faceWidth / imageWidth >= 0.12f && abs(yaw) < 35f && abs(pitch) < 40f
}
