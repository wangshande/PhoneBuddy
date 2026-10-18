package com.deskbuddy

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 用户主动选择并配置后，才发送音频。兼容 multipart audio/transcriptions；分段更新文字。 */
class CloudAsr(private val config:AsrConfig):SpeechEngine {
    private class Session(val recorder:AudioRecord) {
        @Volatile var active=true
        @Volatile var connection:HttpURLConnection?=null
        val worker=Executors.newSingleThreadExecutor()
        val partialBusy=AtomicBoolean(false)
        val utterance=AtomicLong(0)
        lateinit var thread:Thread
    }
    @Volatile private var session:Session?=null
    override val hasModel get()=true
    override val needsModel get()=false
    override val configurationError get()=config.error
    override val isRunning get()=session?.active==true
    override fun loadModel(dir:java.io.File) {}

    @SuppressLint("MissingPermission")
    @Synchronized override fun start(callback:SpeechEngine.Callback):Boolean {
        if(isRunning)return true
        if(config.error!=null)return false
        var record:AudioRecord?=null
        return try {
            val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            record=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(6400,minimum))
            check(record.state==AudioRecord.STATE_INITIALIZED)
            val s=Session(record);record.startRecording();check(record.recordingState==AudioRecord.RECORDSTATE_RECORDING)
            session=s
            s.thread=Thread({
                val pcm=ShortArray(640)
                val pre=java.util.ArrayDeque<ByteArray>()
                var phrase:ByteArrayOutputStream?=null
                var silence=0;var lastPartial=0;var sequence=0L;var voicedFrames=0
                try {
                    while(s.active) {
                        val count=s.recorder.read(pcm,0,pcm.size)
                        if(!s.active)break
                        if(count<=0)error("麦克风中断")
                        val level=AudioLevel.fromPcm(pcm,count);callback.onLevel(level)
                        val bytes=ByteArray(count*2)
                        for(i in 0 until count){bytes[i*2]=pcm[i].toByte();bytes[i*2+1]=(pcm[i].toInt() shr 8).toByte()}
                        val voiced=level>0.12f
                        if(phrase==null) {
                            pre.addLast(bytes);if(pre.size>7)pre.removeFirst()
                            if(!voiced)continue
                            phrase=ByteArrayOutputStream();pre.forEach{phrase!!.write(it)};pre.clear()
                            sequence=s.utterance.incrementAndGet();voicedFrames=1;silence=0;lastPartial=0
                        } else {
                            phrase!!.write(bytes)
                            if(voiced){silence=0;voicedFrames++}else silence+=count
                        }
                        val size=phrase!!.size()
                        if(silence>=16000 || size>=16000*2*10) {
                            if(voicedFrames>=5)submit(s,phrase!!.toByteArray(),sequence,true,callback)
                            phrase=null
                        } else if(size-lastPartial>=16000*2*2 && s.partialBusy.compareAndSet(false,true)) {
                            lastPartial=size;submit(s,phrase!!.toByteArray(),sequence,false,callback)
                        }
                    }
                } catch(e:Exception){ if(s.active)callback.onError(e.message?:"外部识别收音失败") }
                finally {
                    s.active=false;runCatching{s.recorder.stop()};s.recorder.release()
                    s.worker.shutdownNow();s.connection?.disconnect()
                    if(session===s)session=null
                }
            },"DeskBuddy-CloudCapture")
            s.thread.start();true
        }catch(_:Exception){runCatching{record?.release()};session=null;false}
    }

    private fun submit(s:Session,pcm:ByteArray,sequence:Long,final:Boolean,callback:SpeechEngine.Callback) {
        if(!s.active)return
        s.worker.execute {
            try {
                if(!s.active)return@execute
                val text=transcribe(s,Wav.wrap(pcm))
                if(s.active && text.isNotBlank()) {
                    if(final)callback.onFinal(text)
                    else if(s.utterance.get()==sequence)callback.onPartial(text)
                }
            }catch(e:Exception){if(s.active && final)callback.onError(e.message?:"外部语音服务未响应")}
            finally{if(!final)s.partialBusy.set(false)}
        }
    }
    private fun transcribe(s:Session,wav:ByteArray):String {
        val boundary="DeskBuddyAudioBoundary"
        val connection=(URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod="POST";doOutput=true;connectTimeout=12000;readTimeout=20000
            setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
            if(config.key.isNotBlank())setRequestProperty("Authorization","Bearer ${config.key}")
        }
        s.connection=connection
        try {
            connection.outputStream.use { out ->
                fun part(name:String,value:String){out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())}
                part("model",config.model);part("language","zh");part("response_format","json")
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                out.write(wav);out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code=connection.responseCode
            if(code !in 200..299)error("语音接口返回 $code，请检查地址、模型和密钥")
            val raw=connection.inputStream.bufferedReader().use{it.readText().take(65536)}
            return SpeechText.normalize(JSONObject(raw).optString("text",""))
        }finally{connection.disconnect();s.connection=null}
    }
    @Synchronized override fun stop(){
        val old=session?:return;session=null;old.active=false;old.connection?.disconnect();old.worker.shutdownNow()
        runCatching{old.recorder.stop()};if(Thread.currentThread()!==old.thread)old.thread.join(600)
    }
    override fun release()=stop()
}
