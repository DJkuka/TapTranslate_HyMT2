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
 private val controls = mutableListOf<Button>()
 private var busy = false
 private val manager get() = RemoteModelManager.getInstance()

 private fun say(message: String) {
  val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
  runOnUiThread { log.append("[$stamp] $message\n") }
 }
 private fun error(stage: String, e: Exception) {
  android.util.Log.e("MLKitDiag", stage, e)
  say("$stage: ${e.javaClass.name}: ${e.message}")
  e.cause?.let { say("CAUSE: ${it.javaClass.name}: ${it.message}") }
 }
 private fun setBusy(value: Boolean) {
  busy = value
  runOnUiThread { controls.forEach { it.isEnabled = !value } }
 }
 private fun code(): String {
  val c = lang.text.toString().trim().lowercase(Locale.ROOT)
  require(TranslateLanguage.getAllLanguages().contains(c)) { "Unsupported language code: $c" }
  return c
 }
 private fun model(c: String) = TranslateRemoteModel.Builder(c).build()
 private fun conditions() = DownloadConditions.Builder().apply {
  if (wifi.isChecked) requireWifi()
 }.build()

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply { orientation=1; setPadding(24,20,24,20) }
  val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)
  root.addView(TextView(this).apply {
   text="ML Kit isolated diagnostic 17.0.3"
   textSize=20f; setTypeface(null,Typeface.BOLD)
  })
  root.addView(TextView(this).apply {
   text="Отдельный applicationId, без классов и Firebase из TapTranslate. Во время операции остальные кнопки блокируются."
  })
  lang = EditText(this).apply { setText("ru"); hint="Language code"; setSingleLine() }
  root.addView(lang)
  wifi = CheckBox(this).apply { text="Требовать Wi-Fi"; isChecked=true }
  root.addView(wifi)
  fun button(title: String, action: () -> Unit) {
   val b = Button(this).apply { text=title; setOnClickListener { action() } }
   controls.add(b); root.addView(b)
  }
  button("1. Проверить запуск и список") { actionList() }
  button("2. Скачать модель") { actionDownload() }
  button("3. Перевести тестовую фразу") { actionTranslate() }
  button("4. Удалить модель") { actionDelete() }
  button("Полный цикл: список → скачать → обнаружить → перевод → удалить → проверить") { fullCycle() }
  log = TextView(this).apply { textSize=12f; typeface=Typeface.MONOSPACE; setTextIsSelectable(true) }
  root.addView(log, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  say("DIAG_START package=$packageName sdk=${android.os.Build.VERSION.SDK_INT}")
  say("Wi-Fi only is enabled by default; disable it to permit a metered/mobile connection.")
 }

 private fun listModels(done: (List<String>?) -> Unit) {
  try {
   manager.getDownloadedModels(TranslateRemoteModel::class.java)
    .addOnSuccessListener { models ->
     val codes = models.mapNotNull { it.language }.sorted()
     say("MODEL_LIST_OK count=${codes.size} languages=$codes")
     done(codes)
    }
    .addOnFailureListener { e -> error("MODEL_LIST_FAILED",e); done(null) }
  } catch(e: Exception) { error("MODEL_LIST_THROW",e); done(null) }
 }

 private fun download(c: String, done: (Boolean) -> Unit) {
  say("DOWNLOAD_REQUEST code=$c wifiOnly=${wifi.isChecked}")
  if (wifi.isChecked) say("DOWNLOAD_WAITING_OR_RUNNING: require unmetered Wi-Fi")
  try {
   manager.download(model(c), conditions())
    .addOnSuccessListener { say("DOWNLOAD_TASK_OK code=$c"); done(true) }
    .addOnFailureListener { e -> error("DOWNLOAD_FAILED code=$c",e); done(false) }
  } catch(e: Exception) { error("DOWNLOAD_THROW code=$c",e); done(false) }
 }

 private fun translate(c: String, done: (Boolean) -> Unit) {
  val translator = try {
   Translation.getClient(TranslatorOptions.Builder()
    .setSourceLanguage(TranslateLanguage.ENGLISH)
    .setTargetLanguage(c)
    .build())
  } catch(e: Exception) { error("TRANSLATOR_CREATE_FAILED",e); done(false); return }
  say("TRANSLATE_REQUEST en->$c")
  try {
   translator.translate("Hello! This is a test.")
    .addOnSuccessListener { result ->
     say("TRANSLATE_OK: $result")
     translator.close()
     done(true)
    }
    .addOnFailureListener { e ->
     error("TRANSLATE_FAILED",e)
     translator.close()
     done(false)
    }
  } catch(e: Exception) {
   error("TRANSLATE_THROW",e)
   translator.close()
   done(false)
  }
 }

 private fun delete(c: String, done: (Boolean) -> Unit) {
  say("DELETE_REQUEST code=$c")
  try {
   manager.deleteDownloadedModel(model(c))
    .addOnSuccessListener {
     say("DELETE_TASK_OK code=$c")
     listModels { models ->
      val absent = models != null && !models.contains(c)
      say("DELETE_VERIFY code=$c absent=$absent")
      done(absent)
     }
    }
    .addOnFailureListener { e -> error("DELETE_FAILED code=$c",e); done(false) }
  } catch(e: Exception) { error("DELETE_THROW code=$c",e); done(false) }
 }

 private fun actionList() {
  if(busy) return
  setBusy(true)
  listModels { setBusy(false) }
 }
 private fun actionDownload() {
  if(busy) return
  val c = try { code() } catch(e: Exception) { error("LANGUAGE",e); return }
  setBusy(true)
  download(c) { ok ->
   if(!ok) { setBusy(false); return@download }
   listModels { models ->
    val present = models?.contains(c) == true
    say("DOWNLOAD_VERIFY code=$c present=$present")
    setBusy(false)
   }
  }
 }
 private fun actionTranslate() {
  if(busy) return
  val c = try { code() } catch(e: Exception) { error("LANGUAGE",e); return }
  setBusy(true)
  listModels { models ->
   if(models == null || !models.contains(c)) {
    say("TRANSLATE_SKIPPED reason=model_not_downloaded code=$c")
    setBusy(false)
   } else translate(c) { setBusy(false) }
  }
 }
 private fun actionDelete() {
  if(busy) return
  val c = try { code() } catch(e: Exception) { error("LANGUAGE",e); return }
  setBusy(true)
  delete(c) { ok ->
   say("DELETE_OPERATION_RESULT code=$c success=$ok")
   setBusy(false)
  }
 }

 private fun fullCycle() {
  if(busy) return
  val c = try { code() } catch(e: Exception) { error("LANGUAGE",e); return }
  setBusy(true)
  say("FULL_CYCLE_START code=$c")
  listModels { before ->
   if(before == null) { say("FULL_CYCLE_FAIL step=initial_list"); setBusy(false); return@listModels }
   say("STEP_1_LIST_OK code=$c initiallyPresent=${before.contains(c)}")
   download(c) { downloaded ->
    if(!downloaded) { say("FULL_CYCLE_FAIL step=download"); setBusy(false); return@download }
    listModels { after ->
     val present = after?.contains(c) == true
     say("STEP_3_DETECT code=$c present=$present")
     if(!present) { say("FULL_CYCLE_FAIL step=model_not_in_list_after_download"); setBusy(false); return@listModels }
     translate(c) { translated ->
      if(!translated) { say("FULL_CYCLE_STOP step=translation_failed_model_retained"); setBusy(false); return@translate }
      delete(c) { deleted ->
       say(if(deleted) "FULL_CYCLE_PASS code=$c" else "FULL_CYCLE_FAIL step=delete_verification")
       setBusy(false)
      }
     }
    }
   }
  }
 }
}
