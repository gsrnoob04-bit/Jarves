package com.gopal.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var log: ((String) -> Unit)? = null
        @Volatile var running = false
        const val MODEL = "gemini-2.5-flash"
        val WAKES = listOf("jarvis", "jarves", "jarwis", "जार्विस", "जारविस", "जार्वीस", "जर्विस")
        const val SYS = """Tu Jarvis hai, Gopal ka personal Android phone assistant. User Hinglish/Hindi mein bolta hai.
Reply ONLY valid JSON: {"say":"ek chhota sentence Devanagari Hindi mein","actions":[...],"done":true}
done=false tab jab actions ke baad naya screen dekhna zaroori ho taaki agla step plan kar sake. Kaam poora ho ya koi action na chahiye to done=true.
Actions (har ek {"type":..., fields}):
open_app{app} url{url} search{query} youtube{query} maps{place}
call{to: naam ya number} sms{to,text} whatsapp{to,text}
flashlight{on:true/false} volume{level:0-100} media{key:play_pause|next|prev}
alarm{hour,minute} timer{seconds} settings{page:wifi|bluetooth|display|sound|battery|apps|location|airplane|main}
global{name:back|home|recents|notifications|quick_settings|screenshot|lock|power}
click_text{text} type{text} enter{} scroll{dir:up|down} tap{x,y} swipe{x1,y1,x2,y2} (x,y 0 se 1 tak, screen fraction) wait{ms}
Rules: CURRENT SCREEN mein jo dikh raha hai wahi use kar, click_text mein screen ka exact text de. Sirf zaroori actions de. Paisa bhejna, delete, purchase jaise risky kaam se pehle say mein confirm pooch aur actions khali rakh. Sirf sawal ho to say se jawab de aur actions khali rakh."""
    }

    private val h = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sr: SpeechRecognizer? = null
    private val pending = AtomicInteger(0)
    @Volatile private var busy = false
    private var oneShot = false
    private var awakeUntil = 0L
    private val history = mutableListOf<String>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val ch = NotificationChannel("j", "Jarvis", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val n = Notification.Builder(this, "j")
            .setContentTitle("Jarvis active")
            .setContentText("Bolo: Jarvis ...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        if (!running) {
            running = true
            tts = TextToSpeech(this, this)
        }
        if (i?.action == "talk") oneShot = true
        return START_STICKY
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        ttsReady = status == TextToSpeech.SUCCESS
        if (t.setLanguage(Locale("hi", "IN")) < 0) t.language = Locale.US
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { spoke() }
            @Deprecated("deprecated") override fun onError(id: String?) { spoke() }
        })
        say("जार्विस ऑनलाइन है")
    }

    private fun spoke() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            h.post {
                awakeUntil = System.currentTimeMillis() + 8000
                if (!busy) listen()
            }
        }
    }

    private fun ui(s: String) { h.post { log?.invoke(s) } }

    private fun say(s: String) {
        ui("Jarvis: $s")
        val t = tts
        if (s.isBlank() || !ttsReady || t == null) return
        pending.incrementAndGet()
        t.speak(s, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    // ---------------- Listening ----------------
    private fun listen() {
        if (!running || busy || pending.get() > 0) return
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) restart() else handle(t)
            }
            override fun onError(e: Int) {
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    ui("Mic permission do (button 1)"); return
                }
                restart(if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400)
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        r.startListening(i)
    }

    private fun restart(d: Long = 400) { h.postDelayed({ listen() }, d) }

    private fun handle(t: String) {
        val woke = WAKES.any { t.contains(it, ignoreCase = true) }
        if (!woke && !oneShot && System.currentTimeMillis() > awakeUntil) { restart(100); return }
        oneShot = false
        var cmd = t
        WAKES.forEach { cmd = cmd.replace(it, "", ignoreCase = true) }
        cmd = cmd.trim()
        if (cmd.isEmpty()) { say("जी सर, बोलिए"); return }
        busy = true
        ui("Tu: $cmd")
        thread { runAgent(cmd) }
    }

    // ---------------- Agent loop ----------------
    private fun runAgent(goal: String) {
        var lastSay = ""
        try {
            val did = StringBuilder()
            for (step in 1..8) {
                val screen = JarvisAccess.inst?.dump() ?: "(Accessibility OFF hai)"
                val reply = gemini(goal, screen, did.toString())
                val msg = reply.optString("say")
                if (msg.isNotBlank()) { lastSay = msg; say(msg) }
                val acts = reply.optJSONArray("actions") ?: JSONArray()
                for (k in 0 until acts.length()) {
                    val a = acts.getJSONObject(k)
                    did.append(a.toString()).append("\n")
                    try { exec(a) } catch (e: Exception) { ui("Error: ${e.message}"); did.append("-> FAILED ${e.message}\n") }
                    Thread.sleep(700)
                }
                if (reply.optBoolean("done", true) || acts.length() == 0) break
                Thread.sleep(1200)
            }
        } catch (e: Exception) {
            say("एरर: ${e.message}")
        }
        history.add("User: $goal"); history.add("Jarvis: $lastSay")
        busy = false
        h.post { awakeUntil = System.currentTimeMillis() + 8000; if (pending.get() == 0) listen() }
    }

    private fun gemini(goal: String, screen: String, did: String): JSONObject {
        val key = getSharedPreferences("j", MODE_PRIVATE).getString("k", "") ?: ""
        if (key.isBlank()) throw Exception("API key daalo")
        val user = "Conversation:\n" + history.takeLast(6).joinToString("\n") +
            "\n\nGOAL: $goal\n\nACTIONS ALREADY DONE:\n$did\n\nCURRENT SCREEN:\n$screen"
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYS))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 40000
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw Exception("Gemini $code")
        val out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return JSONObject(out.trim().removePrefix("```json").removeSuffix("```").trim())
    }

    // ---------------- Actions ----------------
    private fun acc(): JarvisAccess = JarvisAccess.inst ?: throw Exception("Accessibility ON karo")

    private fun go(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = JarvisAccess.inst ?: this
        c.startActivity(i)
    }

    private fun number(n: String): String {
        val d = n.replace(Regex("[^0-9+]"), "")
        if (d.length >= 6) return d
        val P = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        contentResolver.query(
            P, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$n%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0).replace(Regex("[^0-9+]"), "") }
        throw Exception("Contact '$n' nahi mila")
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(q, 0)
        val n = name.lowercase().trim()
        val m = list.firstOrNull { it.loadLabel(pm).toString().lowercase() == n }
            ?: list.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(n) }
            ?: throw Exception("App '$name' nahi mila")
        go(pm.getLaunchIntentForPackage(m.activityInfo.packageName)!!)
    }

    private fun exec(a: JSONObject) {
        when (a.optString("type")) {
            "open_app" -> openApp(a.optString("app"))
            "url" -> go(Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url"))))
            "search" -> go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.optString("query")))))
            "youtube" -> go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(a.optString("query")))))
            "maps" -> go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(a.optString("place")))))
            "call" -> go(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number(a.optString("to")))))
            "sms" -> {
                val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
                sm.sendMultipartTextMessage(number(a.optString("to")), null,
                    sm.divideMessage(a.optString("text")), null, null)
            }
            "whatsapp" -> {
                var num = number(a.optString("to")).removePrefix("+")
                if (num.length == 10) num = "91$num"
                val u = Uri.parse("https://wa.me/$num?text=" + Uri.encode(a.optString("text")))
                try { go(Intent(Intent.ACTION_VIEW, u).setPackage("com.whatsapp")) }
                catch (e: Exception) { go(Intent(Intent.ACTION_VIEW, u)) }
                Thread.sleep(3000)
                acc().clickText("Send")
            }
            "flashlight" -> {
                val cm = getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                cm.setTorchMode(id, a.optBoolean("on", true))
            }
            "volume" -> {
                val am = getSystemService(AudioManager::class.java)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max * a.optInt("level", 50) / 100, 0)
            }
            "media" -> {
                val am = getSystemService(AudioManager::class.java)
                val k = when (a.optString("key")) {
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k))
            }
            "alarm" -> go(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "timer" -> go(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "settings" -> go(Intent(when (a.optString("page")) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS
                "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }))
            "global" -> acc().global(a.optString("name"))
            "click_text" -> if (!acc().clickText(a.optString("text"))) throw Exception("'${a.optString("text")}' click nahi hua")
            "type" -> if (!acc().typeText(a.optString("text"))) throw Exception("type nahi hua")
            "enter" -> acc().enter()
            "scroll" -> acc().scroll(a.optString("dir"))
            "tap" -> acc().tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat())
            "swipe" -> acc().swipe(a.optDouble("x1").toFloat(), a.optDouble("y1").toFloat(),
                a.optDouble("x2").toFloat(), a.optDouble("y2").toFloat())
            "wait" -> Thread.sleep(a.optLong("ms", 1000))
            else -> ui("Unknown action: $a")
        }
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        sr?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
