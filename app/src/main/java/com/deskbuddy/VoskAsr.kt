package com.deskbuddy

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 离线中文语音包。语音识别始终在手机本地完成，不上传音频。
 * 两个档位：标准（约 44MB，快）和高精度（约 1.3GB，更准，占用内存更大）。
 * 首次使用时从官方地址下载，之后不需要网络。
 */
object VoskModel {
    private const val SMALL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    private const val BIG_URL = "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip"
    private const val PREF = "asr"

    fun useBig(context: Context): Boolean = try {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("big", false)
    } catch (e: Exception) {
        false
    }

    fun setUseBig(context: Context, big: Boolean) {
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("big", big).apply()
        } catch (_: Exception) {
        }
    }

    private fun root(context: Context, big: Boolean) =
        File(context.filesDir, if (big) "vosk-big" else "vosk")

    private fun validModel(dir: File): Boolean = listOf(
        "am/final.mdl", "graph/Gr.fst", "graph/HCLr.fst", "conf/mfcc.conf", "conf/model.conf", "ivector/final.ie"
    ).all { File(dir,it).length() > 0 }

    /** 首次使用从 APK 解压，不走网络；临时目录完成后原子替换，避免半个模型被加载。 */
    @Synchronized
    fun ensureBundled(context: Context): File {
        modelDir(context, false)?.let { return it }
        val staging = File(context.filesDir, "vosk-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            context.assets.open("voice/vosk-small-cn-0.22.zip").use { input ->
                extract(input, staging)
            }
            val extracted = staging.listFiles()?.firstOrNull { validModel(it) }
                ?: error("内置语音包不完整")
            val dest = root(context, false)
            dest.mkdirs()
            val finalDir = File(dest, extracted.name)
            if (finalDir.exists()) finalDir.deleteRecursively()
            check(extracted.renameTo(finalDir)) { "语音包准备失败，请重试" }
            return finalDir
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 已解压的模型目录（含 am 子目录），没有则返回 null */
    fun modelDir(context: Context, big: Boolean = useBig(context)): File? =
        root(context, big).listFiles()?.firstOrNull { it.isDirectory && validModel(it) }

    /** 阻塞下载并解压，请在后台线程调用。 */
    fun download(context: Context, big: Boolean, onProgress: (Int) -> Unit, onDone: (Boolean, String) -> Unit) {
        val dir = root(context, big).apply { mkdirs() }
        val zip = File(dir, "model.zip")
        try {
            val conn = (URL(if (big) BIG_URL else SMALL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt())
                    }
                }
            }
            unzip(zip, dir)
            zip.delete()
            if (modelDir(context, big) == null) throw IllegalStateException("解压后找不到模型")
            onDone(true, "")
        } catch (e: Exception) {
            onDone(false, e.message ?: "网络错误")
        }
    }

    private fun unzip(zip: File, dest: File) {
        extract(zip.inputStream(), dest)
    }

    private fun extract(input: java.io.InputStream, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(destPath)) throw SecurityException("非法路径")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

/**
 * 离线连续识别。开始听写后按句子端点输出文字，不需要按按钮，也不依赖手机厂商的语音服务。
 */
class VoskAsr(private val lowPower:Boolean = false) : SpeechEngine {
    private class Capture(val recorder: AudioRecord, val recognizer: Recognizer) {
        val noise = runCatching {
            if(android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled=true } else null
        }.getOrNull()
        @Volatile var active = true
        lateinit var thread: Thread
    }
    private val retiring = mutableListOf<Capture>()
    private var model: Model? = null
    @Volatile private var capture: Capture? = null
    override val hasModel: Boolean get() = model != null
    override val isRunning: Boolean get() = capture?.active == true

    @Synchronized
    override fun loadModel(dir: File) { if (model == null) model = Model(dir.absolutePath) }

    /** Activity 已检查运行时权限；只使用一个录音器，同时喂给 ASR 和真实音量反馈。 */
    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start(callback: SpeechEngine.Callback): Boolean {
        if (isRunning) return true
        val m = model ?: return false
        var recorder: AudioRecord? = null
        var recognizer: Recognizer? = null
        return try {
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return false
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风尚未准备好" }
            recognizer = Recognizer(m, 16000f)
            recognizer.setEndpointerDelays(5f,1.0f,18f)
            val session = Capture(recorder, recognizer)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            capture = session
            session.thread = Thread({
                val pcm = ShortArray(if(lowPower) 1600 else 640) // 每 40ms 一次反馈；不是伪造的呼吸动画。
                var lastPartial = ""
                var voiceUntil = 0L
                val leadIn = java.util.ArrayDeque<ShortArray>()
                var decoderActive = false
                try {
                    while (session.active) {
                        val count = session.recorder.read(pcm, 0, pcm.size)
                        if (!session.active) break
                        if (count <= 0) error("麦克风已断开，请重试")
                        if (capture !== session) break
                        val level = AudioLevel.fromPcm(pcm,count)
                        callback.onLevel(level)
                        if(lowPower) {
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(level>0.12f)voiceUntil=now+1500
                            if(!decoderActive) {
                                leadIn.addLast(pcm.copyOf(count));if(leadIn.size>3)leadIn.removeFirst()
                                if(now>voiceUntil)continue
                                decoderActive=true
                                leadIn.toList().dropLast(1).forEach{session.recognizer.acceptWaveForm(it,it.size)};leadIn.clear()
                            }
                            if(now>voiceUntil) {
                                val text=field(session.recognizer.finalResult,"text")
                                if(text.isNotBlank())callback.onFinal(text)
                                session.recognizer.reset();decoderActive=false;lastPartial="";continue
                            }
                        }
                        if (session.recognizer.acceptWaveForm(pcm, count)) {
                            val text = field(session.recognizer.result, "text")
                            lastPartial = ""
                            if (text.isNotBlank() && session.active) callback.onFinal(text)
                        } else {
                            val text = field(session.recognizer.partialResult, "partial")
                            if (text != lastPartial && text.isNotBlank() && session.active) {
                                lastPartial = text
                                callback.onPartial(text)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (session.active && capture === session) callback.onError(e.message ?: "录音失败")
                } finally {
                    session.active = false
                    runCatching { session.recorder.stop() }
                    session.noise?.release()
                    session.recorder.release()
                    session.recognizer.close()
                    if (capture === session) capture = null
                }
            }, "DeskBuddy-ASR")
            session.thread.start()
            true
        } catch (e: Exception) {
            runCatching { recorder?.release() }
            runCatching { recognizer?.close() }
            capture = null
            false
        }
    }

    @Synchronized
    override fun stop() {
        val session = capture ?: return
        capture = null
        session.active = false
        runCatching { session.recorder.stop() }
        if (Thread.currentThread() !== session.thread) session.thread.join(750)
        retiring.removeAll { !it.thread.isAlive }
        if (session.thread.isAlive) retiring.add(session)
    }

    @Synchronized
    override fun release() {
        stop()
        val oldModel = model
        model = null
        val pending = retiring.toList()
        retiring.clear()
        if (pending.isNotEmpty()) {
            Thread { pending.forEach { it.thread.join() }; oldModel?.close() }.start()
        } else oldModel?.close()
    }

    private fun field(json: String?, key: String): String = try {
        SpeechText.normalize(JSONObject(json ?: "{}").optString(key, ""))
    } catch (_: Exception) { "" }
}
