package com.deskbuddy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 只传递用户主动开启的听记状态；桌面与后台共用同一录音器。 */
object MemoryBus {
    @Volatile var running=false
    @Volatile var ready=false
    @Volatile var status="全天听记未开启"
    private val listeners=CopyOnWriteArrayList<SpeechEngine.Callback>()
    fun add(c:SpeechEngine.Callback){listeners.addIfAbsent(c)}
    fun remove(c:SpeechEngine.Callback){listeners.remove(c)}
    fun partial(t:String){listeners.forEach{it.onPartial(t)}}
    fun final(t:String){listeners.forEach{it.onFinal(t)}}
    fun level(v:Float){listeners.forEach{it.onLevel(v)}}
}

/** 必须由可见页面中的明确操作启动；常驻通知提供随时停止入口，不开摄像头。 */
class MemoryService:Service() {
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var engine:SpeechEngine?=null
    private var generation=0
    private var destroyed=false
    private var muted=false
    private var retries=0
    private var wake:android.os.PowerManager.WakeLock?=null
    private var renewedAt=0L
    private fun renewWake(){
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-renewedAt>5*60*1000 || wake?.isHeld!=true){
            wake?.let{if(it.isHeld)it.release();it.acquire(10*60*1000L)}
            renewedAt=now
        }
    }
    private lateinit var memory:MemoryStore
    private val callback=object:SpeechEngine.Callback {
        override fun onPartial(text:String){MemoryBus.partial(text)}
        override fun onFinal(text:String){memory.append(text);MemoryBus.final(text)}
        override fun onLevel(level:Float){MemoryBus.level(level)}
        override fun onError(message:String){handler.post {
            MemoryBus.ready=false;MemoryBus.status=message;refreshNotification()
            if(!destroyed && !muted && retries++<3)handler.postDelayed({engine?.stop();resumeEngine()},1200)
        }}
    }
    private val cleanup=object:Runnable {
        override fun run(){renewWake();memory.prune();handler.postDelayed(this,60000)}
    }
    override fun onCreate(){
        super.onCreate();instance=this
        memory=MemoryStore(this)
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL,"全天听记",NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION,notification())
        wake=(getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"DeskBuddy:Memory")
        renewWake()
        MemoryBus.running=true;MemoryBus.status="正在准备全天听记"
        handler.post(cleanup)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){stopSelf();return START_NOT_STICKY}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY}
        if(intent?.action==RECONFIGURE || engine==null)prepareEngine()
        return START_NOT_STICKY
    }
    private fun prepareEngine(){
        val id=++generation
        MemoryBus.ready=false
        engine?.release();engine=null
        worker.execute {
            val next=AsrPrefs.engine(this,true)
            val error=runCatching {
                next.configurationError?.let{error(it)}
                if(next.needsModel)next.loadModel(VoskModel.ensureBundled(this))
            }.exceptionOrNull()
            handler.post {
                if(destroyed || id!=generation){next.release();return@post}
                engine=next
                if(error!=null){MemoryBus.status=error.message?:"听记准备失败";refreshNotification()}
                else resumeEngine()
            }
        }
    }
    private fun resumeEngine(){
        if(destroyed || muted)return
        MemoryBus.ready=engine?.start(callback)==true
        MemoryBus.status=if(MemoryBus.ready)"正在听记，只保留最近24小时文字" else "听记未启动，请检查麦克风与识别设置"
        refreshNotification()
    }
    private fun setMuted(on:Boolean){
        muted=on
        if(on){engine?.stop();MemoryBus.ready=false;MemoryBus.status="伙伴说话时暂停收音"}
        else resumeEngine()
        refreshNotification()
    }
    private fun notification():android.app.Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MemoryActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,MemoryService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle("全天听记已开启").setContentText(MemoryBus.status).setOngoing(true)
            .setContentIntent(open).addAction(0,"停止听记",stop).build()
    }
    private fun refreshNotification(){getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())}
    override fun onDestroy(){
        destroyed=true;generation++;handler.removeCallbacksAndMessages(null)
        engine?.release();engine=null;worker.shutdownNow();instance=null
        MemoryBus.ready=false;MemoryBus.running=false;MemoryBus.status="全天听记已停止"
        wake?.let{if(it.isHeld)it.release()};wake=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        private const val CHANNEL="companion_memory"
        private const val NOTIFICATION=2301
        private const val STOP="com.deskbuddy.memory.STOP"
        private const val RECONFIGURE="com.deskbuddy.memory.RECONFIGURE"
        @Volatile private var instance:MemoryService?=null
        fun start(c:Context){ContextCompat.startForegroundService(c,Intent(c,MemoryService::class.java))}
        fun stop(c:Context){c.stopService(Intent(c,MemoryService::class.java))}
        fun reconfigure(c:Context){if(MemoryBus.running)c.startService(Intent(c,MemoryService::class.java).setAction(RECONFIGURE))}
        fun muteForTts(on:Boolean){instance?.setMuted(on)}
    }
}
