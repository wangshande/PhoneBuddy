package com.deskbuddy

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.io.FileInputStream

/** Native character frames for promotional editing. Gaze events are scripted, not a gaze benchmark. */
class PromoInstrumentation : Instrumentation() {
    @Volatile private var main: MainActivity? = null
    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        if (activity is MainActivity) main = activity
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    override fun onStart() {
        val previous = PetPrefs.load(targetContext)
        var code=0
        val result=Bundle()
        try {
            val fd=uiAutomation.executeShellCommand("am start -n ${targetContext.packageName}/com.deskbuddy.MainActivity")
            FileInputStream(fd.fileDescriptor).use { it.readBytes() }; fd.close()
            var tries=0
            while(main==null && tries++<40) Thread.sleep(100)
            val activity=main?:error("Main did not open")
            Thread.sleep(700)
            lateinit var pet:PetView
            runOnMainSync {
                pet=views(activity.window.decorView).filterIsInstance<PetView>().first()
                targetContext.stopService(Intent(targetContext,DeskService::class.java))
                views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString()==PetStyle.BLOB.label }.performClick()
                pet.setMood(PetView.Mood.IDLE);pet.setGaze(0f,0f,false)
            }
            val output=File(targetContext.getExternalFilesDir(null),"promo").apply { mkdirs() }
            val start=SystemClock.uptimeMillis()
            for(frame in 0 until 288) {
                val wait=start+frame*1000/24-SystemClock.uptimeMillis()
                if(wait>0) Thread.sleep(wait)
                val bitmap=Bitmap.createBitmap(pet.width,pet.height,Bitmap.Config.ARGB_8888)
                runOnMainSync {
                    if(frame==96)pet.react(PetView.Action.READ)
                    if(frame==192)pet.setGaze(0f,0f,true)
                    pet.draw(Canvas(bitmap))
                }
                File(output,"frame-%03d.png".format(frame)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            result.putString("result","288 native animation frames; gaze events scripted")
        } catch (e:Throwable) { code=1;result.putString("error",e.toString()) }
        finally {
            PetPrefs.save(targetContext,previous)
            targetContext.getSharedPreferences("pet",0).edit().commit()
        }
        finish(code,result)
    }
}
