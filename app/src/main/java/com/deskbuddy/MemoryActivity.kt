package com.deskbuddy

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryActivity:AppCompatActivity() {
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=Theme.BG;window.navigationBarColor=Theme.BG
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val store=MemoryStore(this);store.prune()
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(28),dp(24),dp(32))}
        column.addView(label(26f,Theme.LABEL,Face.MEDIUM).apply{text="最近24小时"})
        column.addView(label(14f,Theme.LABEL_2).apply{text="${MemoryBus.status}\n只保存文字，不保留录音。";setPadding(0,dp(10),0,dp(16))})
        val search=EditText(this).apply{hint="查找听记";textSize=16f;setTextColor(Theme.LABEL);setHintTextColor(Theme.LABEL_3);background=surface(Theme.ELEVATED,10);setPadding(dp(14),dp(10),dp(14),dp(10));isSingleLine=true}
        column.addView(search)
        val rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        fun render(){
            rows.removeAllViews();val query=search.text.toString().trim()
            val entries=store.entries(1000).filter{query.isBlank() || it.text.contains(query,true)}
            if(entries.isEmpty())rows.addView(label(16f,Theme.LABEL_2).apply{text=if(query.isBlank())"还没有听记。开启后，识别到的文字会出现在这里。" else "没有找到相关记录";setPadding(0,dp(28),0,dp(28))})
            val time=SimpleDateFormat("MM月dd日 HH:mm",Locale.CHINA)
            entries.forEach { e ->
                rows.addView(label(12f,Theme.LABEL_2).apply{text=time.format(Date(e.time));setPadding(0,dp(22),0,dp(5))})
                rows.addView(label(17f,Theme.LABEL).apply{text=e.text;setTextIsSelectable(true);setPadding(0,0,0,dp(16))})
                rows.addView(hairline(),LinearLayout.LayoutParams(-1,1))
            }
        }
        search.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()}
            override fun afterTextChanged(s:Editable?){}
        })
        column.addView(rows)
        column.addView(formRow("清空听记",titleColor=Theme.LABEL_2){AlertDialog.Builder(this).setMessage("删除最近24小时的全部听记？").setNegativeButton("保留",null).setPositiveButton("删除"){_,_->store.clear();render()}.show()})
        column.addView(ghostButton("返回"){finish()})
        setContentView(ScrollView(this).apply{setBackgroundColor(Theme.BG);addView(column)})
        render()
    }
}
