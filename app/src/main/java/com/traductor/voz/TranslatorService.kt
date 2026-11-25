package com.traductor.voz

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

class TranslatorService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_SRC = "src"
        const val EXTRA_MODEL = "model"
        @Volatile var running = false
        @Volatile var lastReport = "Sin iniciar"
        var onStatus: ((String) -> Unit)? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private var translator: Translator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var src = "ja"
    private var modelName = "vosk-model-small-ja-0.22"
    private var pending = 0
    @Volatile private var alive = false

    // Panel de diagnóstico
    private var state = "Iniciando..."
    @Volatile private var level = 0f
    private var heard = "-"
    private var spoken = "-"
    private var voiceInfo = "iniciando..."
    private var lastPublish = 0L

    private fun publish(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPublish < 250) return
        lastPublish = now
        val n = level.toInt().coerceIn(0, 10)
        val bar = "█".repeat(n) + "░".repeat(10 - n)
        lastReport = "Estado: $state\nMicrófono: $bar\nVoz en español: $voiceInfo\nOí: $heard\nTraducción: $spoken"
        onStatus?.invoke(lastReport)
    }

    private fun setState(s: String) { h.post { state = s; publish(true) } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onInit(s: Int) {
        val r = tts?.setLanguage(Locale("es"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED || r == null) {
            voiceInfo = "NO HAY voz en español en el teléfono"
        } else {
            voiceInfo = "OK"
            ttsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { h.post { pending = maxOf(0, pending - 1); updateRate() } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { h.post { pending = maxOf(0, pending - 1); updateRate() } }
            })
            speak("Traductor listo")
        }
        publish(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        cleanup()
        src = intent?.getStringExtra(EXTRA_SRC) ?: "ja"
        modelName = intent?.getStringExtra(EXTRA_MODEL) ?: "vosk-model-small-ja-0.22"
        heard = "-"; spoken = "-"; voiceInfo = "iniciando..."; level = 0f
        startAsForeground()
        running = true
        alive = true
        tts = TextToSpeech(this, this)
        translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.SPANISH).build()
        )
        state = "Preparando traducción (necesita internet la primera vez)..."
        publish(true)
        translator!!.downloadModelIfNeeded()
            .addOnSuccessListener { startWorker() }
            .addOnFailureListener { state = "No se pudo bajar el idioma de traducción: ${it.message}"; publish(true) }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("traduccion", "Traducción", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TranslatorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "traduccion")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Traduciendo al español")
            .setContentText("Escuchando el audio")
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    // ---- Modelo de reconocimiento (se baja una sola vez) ----
    private fun ensureModel(): File {
        val root = File(filesDir, "models"); root.mkdirs()
        val dir = File(root, modelName)
        val marker = File(dir, ".listo")
        if (marker.exists()) return dir
        dir.deleteRecursively()
        val zip = File(root, "$modelName.zip")
        val conn = URL("https://alphacephei.com/vosk/models/$modelName.zip").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        val total = conn.contentLengthLong
        conn.inputStream.use { ins ->
            zip.outputStream().use { out ->
                val buf = ByteArray(32768)
                var done = 0L; var n: Int; var last = 0L
                while (alive) {
                    n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    val now = SystemClock.uptimeMillis()
                    if (now - last > 700) {
                        last = now
                        setState("Descargando modelo de voz: ${done / 1048576} de ${if (total > 0) total / 1048576 else "?"} MB (solo la primera vez)")
                    }
                }
            }
        }
        if (!alive) { zip.delete(); throw Exception("cancelado") }
        setState("Descomprimiendo modelo...")
        ZipInputStream(zip.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(root.canonicalPath)) throw Exception("zip inválido")
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
                e = zin.nextEntry
            }
        }
        zip.delete()
        if (!File(dir, "am").exists()) throw Exception("modelo incompleto")
        marker.writeText("ok")
        return dir
    }

    private fun clean(t: String) = if (src == "ja") t.replace(" ", "") else t

    private fun startWorker() {
        Thread {
            var model: Model? = null
            var rec: AudioRecord? = null
            var vosk: Recognizer? = null
            try {
                val dir = ensureModel()
                if (!alive) return@Thread
                setState("Cargando modelo...")
                model = Model(dir.absolutePath)
                val rate = 16000
                val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16384))
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw Exception("no se pudo abrir el micrófono")
                vosk = Recognizer(model, rate.toFloat())
                rec.startRecording()
                setState("Escuchando...")
                val buf = ShortArray(2048)
                var segStart = 0L; var lastPartial = ""; var lastLevelPost = 0L
                while (alive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) throw Exception("error leyendo el micrófono ($n)")
                    if (n == 0) continue
                    var s = 0.0
                    for (i in 0 until n) s += buf[i].toDouble() * buf[i]
                    val db = 20 * Math.log10(Math.sqrt(s / n) / 32768.0 + 1e-9)
                    level = ((db + 60) / 50 * 10).toFloat().coerceIn(0f, 10f)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastLevelPost > 250) { lastLevelPost = now; h.post { publish() } }

                    if (vosk.acceptWaveForm(buf, n)) {
                        handleFinal(JSONObject(vosk.result).optString("text")); segStart = 0; lastPartial = ""
                    } else {
                        val p = JSONObject(vosk.partialResult).optString("partial")
                        if (p.isNotEmpty()) {
                            if (segStart == 0L) segStart = now
                            if (p != lastPartial) { lastPartial = p; val c = clean(p); h.post { heard = "$c …"; publish() } }
                            if (now - segStart > 8000) {
                                handleFinal(JSONObject(vosk.finalResult).optString("text")); segStart = 0; lastPartial = ""
                            }
                        } else segStart = 0
                    }
                }
            } catch (e: Exception) {
                if (alive) setState("Error: ${e.message}")
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release(); vosk?.close(); model?.close()
            }
        }.start()
    }

    private fun handleFinal(raw: String) {
        val t = clean(raw)
        if (t.isBlank()) return
        h.post { heard = t; publish(true); translate(t) }
    }

    private fun translate(text: String) {
        translator?.translate(text)
            ?.addOnSuccessListener { es -> spoken = es; publish(true); speak(es) }
            ?.addOnFailureListener { spoken = "ERROR al traducir: ${it.message}"; publish(true) }
    }

    private fun speak(es: String) {
        if (!ttsReady) return
        if (pending > 3) { tts?.stop(); pending = 0 }
        pending++
        updateRate()
        tts?.speak(es, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    private fun updateRate() { tts?.setSpeechRate((1.0f + 0.15f * pending).coerceAtMost(1.6f)) }

    private fun cleanup() {
        alive = false
        h.removeCallbacksAndMessages(null)
        translator?.close(); translator = null
        tts?.shutdown(); tts = null
        ttsReady = false; pending = 0
    }

    override fun onDestroy() {
        cleanup()
        running = false
        lastReport = "Detenido"
        onStatus?.invoke(lastReport)
        super.onDestroy()
    }
}
