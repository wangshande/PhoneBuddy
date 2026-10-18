package com.deskbuddy

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.Model
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 只存在于 debug 构建的真机回归入口；测试操作不接触原版包的数据。 */
class QaInstrumentation : Instrumentation() {
    private val results = JSONArray()
    private lateinit var folder: File
    private var fixtureText=""
    @Volatile private var observedMain:Activity?=null
    @Volatile private var observedDesk:Activity?=null
    override fun callActivityOnResume(activity:Activity){
        super.callActivityOnResume(activity)
        if(activity is MainActivity)observedMain=activity
        if(activity is DeskActivity)observedDesk=activity
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun ui(block: () -> Unit) { runOnMainSync(block) }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun findText(a: Activity, text: String): TextView = views(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == text }
    private fun shot(name: String) {
        Thread.sleep(350)
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
    private fun avatar(a:Activity,name:String) {
        ui {
            val pet=views(a.window.decorView).filterIsInstance<PetView>().first()
            val bitmap=Bitmap.createBitmap(pet.width,pet.height,Bitmap.Config.ARGB_8888)
            pet.draw(android.graphics.Canvas(bitmap))
            File(folder,"avatar-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    private fun check(name: String, block: () -> Unit) {
        File(folder,"progress.txt").writeText(name)
        try { block(); results.put(JSONObject().put("test",name).put("pass",true)) }
        catch (e: Throwable) { results.put(JSONObject().put("test",name).put("pass",false).put("error",e.toString())) }
    }
    override fun onStart() {
        folder = File(targetContext.getExternalFilesDir(null),"qa").apply { mkdirs() }
        var main: Activity? = null
        var desk: Activity? = null
        val oldStyle = PetPrefs.load(targetContext)
        val store = TodoStore(targetContext)
        val originalIds = store.all().map { it.id }.toSet()
        val memories=MemoryStore(targetContext)
        val memoryIds=memories.entries(10000).map{it.id}.toSet()
        val oldAllDay=CompanionPrefs.allDay(targetContext)
        val cameraFrames=AtomicInteger()
        val cameraListener:(GazeSample)->Unit={if(it.error==null)cameraFrames.incrementAndGet()}
        try {
            ui{Gaze.add(cameraListener)}
            val command=uiAutomation.executeShellCommand("am start -n ${targetContext.packageName}/com.deskbuddy.MainActivity")
            java.io.FileInputStream(command.fileDescriptor).use{it.readBytes()};command.close()
            var attempts=0
            while(observedMain==null && attempts++<30)Thread.sleep(200)
            main=observedMain?:error("Main did not open")
            Thread.sleep(600)
            check("camera_frame_pipeline") {Thread.sleep(1200);require(cameraFrames.get()>0){"No camera analysis samples"}}
            check("bundled_model_ready_without_network") { require(VoskModel.ensureBundled(targetContext).resolve("am/final.mdl").isFile) }
            for (style in PetStyle.values()) {
                check("character_${style.name}") {
                    ui { findText(main!!,style.label).performClick() }
                    Thread.sleep(800)
                    ui { require(views(main!!.window.decorView).filterIsInstance<PetView>().first().style == style) }
                    shot("character-${style.name}")
                    avatar(main!!,style.name)
                }
            }
            check("autonomous_activity_has_visible_props") {
                ui { views(main!!.window.decorView).filterIsInstance<PetView>().first().react(PetView.Action.READ) }
                Thread.sleep(600)
                ui { require(views(main!!.window.decorView).filterIsInstance<PetView>().first().currentActivityLabel=="翻小书") }
                shot("busy-reading");avatar(main!!,"READ")
            }
            check("logo_native_render") {
                ui {
                    val icon=targetContext.getDrawable(com.deskbuddy.R.mipmap.ic_launcher)!!
                    val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
                    icon.setBounds(0,0,512,512);icon.draw(android.graphics.Canvas(bitmap))
                    File(folder,"logo.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                }
            }
            check("offline_asr_prerecorded_chinese_with_partials") {
                val pcm=targetContext.assets.open("qa/utterance.pcm").use{it.readBytes()}
                Model(VoskModel.ensureBundled(targetContext).absolutePath).use { model ->
                    Recognizer(model,16000f).use { rec ->
                        var partial=false
                        val chunks=ArrayList<String>()
                        var offset=0
                        while(offset<pcm.size){
                            val count=minOf(1280,pcm.size-offset)
                            val shorts=ShortArray(count/2){i->((pcm[offset+i*2].toInt() and 255) or (pcm[offset+i*2+1].toInt() shl 8)).toShort()}
                            if(rec.acceptWaveForm(shorts,shorts.size))chunks+=JSONObject(rec.result).optString("text")
                            else if(JSONObject(rec.partialResult).optString("partial").isNotBlank())partial=true
                            offset+=count
                        }
                        chunks+=JSONObject(rec.finalResult).optString("text")
                        fixtureText=SpeechText.normalize(chunks.joinToString(" "))
                        require(partial && fixtureText.isNotBlank()){"No ASR text or partial result"}
                        File(folder,"fixture-transcript.txt").writeText(fixtureText)
                    }
                }
            }
            check("actual_microphone_levels_and_release") {
                val asr = VoskAsr()
                try {
                    asr.loadModel(VoskModel.ensureBundled(targetContext))
                    repeat(2) {
                        val levels = AtomicInteger()
                        require(asr.start(object: SpeechEngine.Callback {
                            override fun onPartial(text:String) {}
                            override fun onFinal(text:String) {}
                            override fun onError(message:String) {}
                            override fun onLevel(level:Float) { if (level in 0f..1f) levels.incrementAndGet() }
                        }))
                        Thread.sleep(850)
                        asr.stop()
                        require(!asr.isRunning && levels.get() >= 3) { "No PCM callbacks or microphone was not released" }
                    }
                } finally { asr.release() }
            }
            ui { findText(main!!,"进入桌面").performClick() }
            var tries=0
            while(observedDesk==null && tries++<30)Thread.sleep(200)
            desk=observedDesk?:error("Desktop did not open")
            Thread.sleep(1200)
            ui{targetContext.stopService(Intent(targetContext,DeskService::class.java))}
            Thread.sleep(500)
            check("desktop_portrait_layout") {
                ui { desk!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                Thread.sleep(800)
                val root = desk!!.window.decorView
                ui {
                    require(views(root).filterIsInstance<PetView>().first().height > targetContext.dp(120))
                    require(findText(desk!!,"今天").isShown)
                }
                shot("desktop-portrait")
            }
            check("gaze_event_has_visible_response") {
                ui { Gaze.publish(GazeSample(true,0f,0f,true,120)) }
                Thread.sleep(400)
                ui { require(views(desk!!.window.decorView).filterIsInstance<PetView>().first().mood != PetView.Mood.SLEEP) }
                shot("gaze-response")
            }
            check("wave_and_incremental_text_callbacks") {
                ui {
                    val cls=DeskActivity::class.java
                    cls.getDeclaredField("listening").apply{isAccessible=true}.setBoolean(desk,true)
                    val wave=views(desk!!.window.decorView).filterIsInstance<VoiceWaveView>().first();wave.active=true
                    val cb=cls.getDeclaredField("asrCallback").apply{isAccessible=true}.get(desk) as SpeechEngine.Callback
                    cb.onPartial(fixtureText.ifBlank{"帮我记下明天买牛奶"})
                    for(i in 0..23)cb.onLevel((i%7)/8f)
                }
                Thread.sleep(150)
                ui{require(views(desk!!.window.decorView).filterIsInstance<TextView>().any{it.text.toString().contains("▏")})}
                shot("speech-feedback-fixture")
                ui{DeskActivity::class.java.getDeclaredField("listening").apply{isAccessible=true}.setBoolean(desk,false)}
            }
            check("not_looking_stays_awake") {
                repeat(5) { ui { Gaze.publish(GazeSample(false,0f,0f)) }; Thread.sleep(850) }
                ui { require(views(desk!!.window.decorView).filterIsInstance<PetView>().first().mood != PetView.Mood.SLEEP) }
                shot("idle-awake")
            }
            check("todo_add_confirm_complete") {
                ui {
                    DeskActivity::class.java.getDeclaredField("judge").apply { isAccessible = true }.set(desk,RuleJudge())
                    views(desk!!.window.decorView).filterIsInstance<EditText>().first().setText("帮我记下自测完成后喝水")
                    findText(desk!!,"发送").performClick()
                }
                Thread.sleep(800)
                ui { findText(desk!!,"记下").performClick() }
                Thread.sleep(400)
                require(store.pending().any { it.text == "自测完成后喝水" })
                ui { (findText(desk!!,"自测完成后喝水").parent as View).performClick() }
                Thread.sleep(300)
                require(store.all().any { it.text == "自测完成后喝水" && it.done })
            }
            check("desktop_landscape_layout") {
                ui { desk!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                Thread.sleep(1000)
                ui {
                    val pet = views(desk!!.window.decorView).filterIsInstance<PetView>().first()
                    require(pet.width > targetContext.dp(150) && pet.height > targetContext.dp(100))
                    require(findText(desk!!,"今天").isShown)
                }
                shot("desktop-landscape")
            }
            check("all_day_survives_unplug_event") {
                ui {
                    CompanionPrefs.setAllDay(targetContext,true)
                    val receiver=DeskActivity::class.java.getDeclaredField("powerReceiver").apply{isAccessible=true}.get(desk) as android.content.BroadcastReceiver
                    receiver.onReceive(desk,Intent(Intent.ACTION_POWER_DISCONNECTED))
                    require(!desk!!.isFinishing)
                }
            }
            check("farewell_finishes_activity") {
                ui { DeskActivity::class.java.getDeclaredMethod("beginExit").apply { isAccessible=true }.invoke(desk) }
                Thread.sleep(1400)
                require(desk!!.isFinishing || desk!!.isDestroyed)
            }
            check("background_memory_start_stop_and_retention") {
                ui{MemoryService.start(targetContext)}
                var n=0
                while(!MemoryBus.ready && n++<30)Thread.sleep(250)
                require(MemoryBus.running && MemoryBus.ready){MemoryBus.status}
                memories.append("自测记忆：晚上喝水")
                require(memories.entries().any{it.text=="自测记忆：晚上喝水"})
                memories.append("自测过期记录",System.currentTimeMillis()-MemoryStore.DAY-2000)
                memories.prune()
                require(memories.entries().none{it.text=="自测过期记录"})
                ui{MemoryService.stop(targetContext)}
                Thread.sleep(700);require(!MemoryBus.running)
            }
        } catch(e: Throwable) { results.put(JSONObject().put("test","harness").put("pass",false).put("error",e.toString())) }
        finally {
            store.all().filter { it.id !in originalIds || it.text=="自测完成后喝水" }.forEach { store.delete(it.id) }
            // Instrumentation finish can terminate before SharedPreferences.apply reaches disk.
            targetContext.getSharedPreferences("todos",android.content.Context.MODE_PRIVATE).edit().commit()
            memories.deleteIds(memories.entries(10000).filter{it.id !in memoryIds}.map{it.id})
            ui{Gaze.remove(cameraListener);MemoryService.stop(targetContext)}
            CompanionPrefs.setAllDay(targetContext,oldAllDay)
            PetPrefs.save(targetContext,oldStyle)
            ui { desk?.takeUnless { it.isFinishing }?.finish(); main?.finish() }
            File(folder,"results.json").writeText(results.toString(2))
            val failures = (0 until results.length()).count { !results.getJSONObject(it).optBoolean("pass") }
            finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("results",results.toString()) })
        }
    }
}
