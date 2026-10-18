package com.deskbuddy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 充电期间运行的前台服务，负责持续的注视检测：
 * 1. 前置摄像头 + ML Kit 端侧人脸检测，只取头部朝向角，不保存、不上传画面
 * 2. 把注视结果发布给 Gaze，桌面界面据此做出反应
 * 3. 桌面界面不在前台时，检测到注视会弹出全屏通知把它拉起来
 */
class DeskService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAlertAt = 0L
    private var lastFrameAt = 0L
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // 没有摄像头权限就不启动，避免 Android 14 下前台服务类型校验崩溃
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeskBuddy:desk").apply { acquire() }
        startFaceWatching()
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        analysisExecutor.shutdown()
        ServiceLifecycle.stop()
        detector.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startFaceWatching() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }
            provider.unbindAll()
            provider.bindToLifecycle(ServiceLifecycle.owner(), CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (_: Exception) {
                Gaze.publish(GazeSample(false, 0f, 0f, error = "摄像头未启动，请关闭其他占用摄像头的应用后重试"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
        val frameTime=android.os.SystemClock.elapsedRealtime()
        if(frameTime-lastFrameAt<280){imageProxy.close();return}
        lastFrameAt=frameTime
        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() }
                val yaw = face?.headEulerAngleY ?: 0f
                val pitch = face?.headEulerAngleX ?: 0f
                val width = face?.boundingBox?.width() ?: 0
                // 人脸够大（离屏幕不远）且头部大致正对屏幕，才算在看。
                // 手机立在桌上时摄像头在上沿，抬头看屏幕常有 20–30 度俯仰，所以角度放宽。
                // ML Kit 的人脸框已旋转到正向坐标，分母也要使用旋转后的宽高。
                val rotated = input.rotationDegrees == 90 || input.rotationDegrees == 270
                val iw = (if (rotated) input.height else input.width).toFloat()
                val ih = (if (rotated) input.width else input.height).toFloat()
                val looking = GazeGeometry.isLooking(width.toFloat(), iw, yaw, pitch)
                val bb = face?.boundingBox
                val cx = if (bb != null && iw > 0f) (bb.centerX() / iw) * 2f - 1f else 0f
                val cy = if (bb != null && ih > 0f) (bb.centerY() / ih) * 2f - 1f else 0f
                Gaze.publish(GazeSample(looking, yaw, pitch, faceSeen = face != null, faceWidth = width, cx = cx, cy = cy))

                val now = System.currentTimeMillis()
                if (looking && !Gaze.deskVisible && now - lastAlertAt > ALERT_COOLDOWN_MS) {
                    lastAlertAt = now
                    showDeskAlert()
                }
            }
            .addOnFailureListener {
                Gaze.publish(GazeSample(false, 0f, 0f, error = "暂时无法识别人脸，请重新进入桌面"))
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** 后台直接 startActivity 会被系统拦截，所以通过全屏通知拉起桌面模式。 */
    private fun showDeskAlert() {
        val intent = Intent(this, DeskActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle("我在这儿呢")
            .setContentText("看到你了，要说点什么吗？")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "桌面伙伴运行中", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "桌面伙伴提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("桌面伙伴正在陪你")
            .setContentText("注视互动中 · 人脸检测只在本机进行")
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "desk_running"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_CHANNEL_ID = "desk_alert"
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val MIN_FACE_WIDTH = 90
        private const val LOOK_ANGLE_DEG = 35f
        private const val ALERT_COOLDOWN_MS = 8_000L

        /**
         * 启动注视检测。没有摄像头权限时直接不启动：
         * 前台服务若启动后不调用 startForeground，系统会直接判崩溃。
         */
        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            ContextCompat.startForegroundService(context, Intent(context, DeskService::class.java))
        }
    }
}
