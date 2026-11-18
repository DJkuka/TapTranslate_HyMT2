package com.taptranslate.mlkitdiag

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
 private lateinit var log: TextView
 private lateinit var lang: EditText
 private lateinit var wifi: CheckBox
 private val manager get() = RemoteModelManager.getInstance()
 private fun model(code: String) = TranslateRemoteModel.Builder(code).build()
 private fun say(s: String) { runOnUiThread { log.append("[" + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "] " + s + "\n") } }
 private fun error(stage: String, e: Exception) { android.util.Log.e("MLKitDiag", stage, e); say("$stage: ${e.javaClass.name}: ${e.message}") }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply { orientation=1; setPadding(24,20,24,20) }
  val scroll=ScrollView(this); scroll.addView(root); setContentView(scroll)
  root.addView(TextView(this).apply { text="ML Kit isolated diagnostic 17.0.3"; textSize=20f; setTypeface(null,Typeface.BOLD) })
  root.addView(TextView(this).apply { text="Отдельный applicationId, без классов и Firebase из TapTranslate." })
  lang=EditText(this).apply { setText("ru"); hint="Language code"; setSingleLine() }; root.addView(lang)
  wifi=CheckBox(this).apply { text="Требовать Wi-Fi"; isChecked=true }; root.addView(wifi)
  log=TextView(this).apply { textSize=12f; typeface=Typeface.MONOSPACE; setTextIsSelectable(true) }; 
  fun button(title:String, action:()->Unit) { root.addView(Button(this).apply { text=title; setOnClickListener { action() } }) }
  button("Проверить запуск и список") { listModels() }
  button("Скачать модель") { download() }
  button("Перевести тестовую фразу") { translate() }
  button("Удалить модель") { delete() }
  button("Полный цикл") { fullCycle() }
  root.addView(log)
  say("DIAG_START package=$packageName sdk=${android.os.Build.VERSION.SDK_INT}")
 }
 private fun code():String {
  val c=lang.text.toString().trim().lowercase(Locale.ROOT)
  require(TranslateLanguage.getAllLanguages().contains(c)) { "Unsupported language code: $c" }
  return c
 }
 private fun conditions()=DownloadConditions.Builder().apply { if(wifi.isChecked) requireWifi() }.build()
 private fun listModels(done:((Boolean)->Unit)?=null) {
  try { manager.getDownloadedModels(TranslateRemoteModel::class.java)
   .addOnSuccessListener { ms -> say("MODEL_LIST_OK count=${ms.size} languages=${ms.mapNotNull{it.language}.sorted()}"); done?.invoke(true) }
   .addOnFailureListener { e -> error("MODEL_LIST_FAILED",e); done?.invoke(false) }
  } catch(e:Exception) { error("MODEL_LIST_THROW",e); done?.invoke(false) }
 }
 private fun download(done:((Boolean)->Unit)?=null) {
  val c=try{code()}catch(e:Exception){error("LANGUAGE",e);done?.invoke(false);return}
  say("DOWNLOAD_REQUEST $c")
  try { manager.download(model(c),conditions()).addOnSuccessListener { say("DOWNLOAD_OK $c"); listModels { ok -> done?.invoke(ok) } }
   .addOnFailureListener { e -> error("DOWNLOAD_FAILED",e);done?.invoke(false) }
  } catch(e:Exception){error("DOWNLOAD_THROW",e);done?.invoke(false)}
 }
 private fun translate(done:((Boolean)->Unit)?=null) {
  val c=try{code()}catch(e:Exception){error("LANGUAGE",e);done?.invoke(false);return}
  val t=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(c).build())
  say("TRANSLATE_REQUEST en->$c")
  try { t.translate("Hello! This is a test.").addOnSuccessListener { say("TRANSLATE_OK: $it");t.close();done?.invoke(true) }
   .addOnFailureListener { e -> error("TRANSLATE_FAILED",e);t.close();done?.invoke(false) }
  } catch(e:Exception){error("TRANSLATE_THROW",e);t.close();done?.invoke(false)}
 }
 private fun delete(done:((Boolean)->Unit)?=null) {
  val c=try{code()}catch(e:Exception){error("LANGUAGE",e);done?.invoke(false);return}
  say("DELETE_REQUEST $c")
  try { manager.deleteDownloadedModel(model(c)).addOnSuccessListener { say("DELETE_OK $c"); listModels { ok -> done?.invoke(ok) } }
   .addOnFailureListener { e -> error("DELETE_FAILED",e);done?.invoke(false) }
  } catch(e:Exception){error("DELETE_THROW",e);done?.invoke(false)}
 }
 private fun fullCycle() {
  say("FULL_CYCLE_START")
  listModels { listed -> if(!listed)return@listModels
   download { downloaded -> if(!downloaded)return@download
    listModels { detected -> if(!detected)return@listModels
     translate { translated -> if(!translated)return@translate
      delete { deleted -> if(deleted)say("FULL_CYCLE_FINISHED_CHECK_LIST_ABOVE") }
     }
    }
   }
  }
 }
}
