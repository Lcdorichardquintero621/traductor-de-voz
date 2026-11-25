package com.traductor.voz

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class TranslatorService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_SRC = "src"
        const val EXTRA_TAG = "tag"
        @Volatile var running = false
        @Volatile var lastReport = "Sin iniciar"
        var onStatus: ((String) -> Unit)? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var src = "ja"
    private var tag = "ja-JP"
    private var pending = 0
    private var active = false

    // Datos del panel de diagnóstico
    private var state = "Iniciando..."
    private var level = 0f
    private var errCount = 0
    private var lastErr = "ninguno"
    private var heard = "-"
    private var spoken = "-"
    private var voiceInfo = "iniciando..."
    private var lastPublish = 0L

    private fun errName(e: Int) = when (e) {
        1 -> "red lenta"; 2 -> "sin red"; 3 -> "error de audio"; 4 -> "error del servidor"
        5 -> "error del cliente"; 6 -> "no oyó hablar"; 7 -> "no entendió nada"
        8 -> "reconocedor ocupado"; 9 -> "FALTA PERMISO"; 10 -> "demasiadas peticiones"
        11 -> "servidor desconectado"; 12 -> "idioma no soportado"; 13 -> "idioma no disponible"
        else -> "otro"
    }

    private fun publish(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPublish < 250) return
        lastPublish = now
        val n = ((level + 2f) / 12f * 10f).toInt().coerceIn(0, 10)
        val bar = "█".repeat(n) + "░".repeat(10 - n)
        lastReport = "Estado: $state\nMicrófono: $bar\nErrores: $errCount (último: $lastErr)\n" +
            "Voz en español: $voiceInfo\nOí: $heard\nTraducción: $spoken"
        onStatus?.invoke(lastReport)
    }

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
        tag = intent?.getStringExtra(EXTRA_TAG) ?: "ja-JP"
        errCount = 0; lastErr = "ninguno"; heard = "-"; spoken = "-"; voiceInfo = "iniciando..."
        startAsForeground()
        running = true
        tts = TextToSpeech(this, this)
        translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.SPANISH).build()
        )
        state = "Descargando idioma (necesita internet la primera vez)..."
        publish(true)
        translator!!.downloadModelIfNeeded()
            .addOnSuccessListener { active = true; state = "Escuchando..."; publish(true); listen() }
            .addOnFailureListener { state = "No se pudo bajar el idioma: ${it.message}"; publish(true) }
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
            .setContentText("Escuchando el audio del teléfono")
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun listen() {
        if (!active) return
        recognizer?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            state = "Este teléfono NO tiene reconocimiento de voz (instala/activa la app Google)"
            publish(true); return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle) {
                    val t = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t != null) { heard = t; publish(true); translate(t) }
                    h.postDelayed({ listen() }, 50)
                }
                override fun onError(e: Int) {
                    errCount++; lastErr = "$e ${errName(e)}"; publish(true)
                    val wait = when (e) { 8, 5, 9, 10 -> 1000L; else -> 150L }
                    h.postDelayed({ listen() }, wait)
                }
                override fun onReadyForSpeech(p: Bundle?) { state = "Escuchando..."; publish() }
                override fun onBeginningOfSpeech() { state = "Oyendo voz..."; publish() }
                override fun onRmsChanged(v: Float) { level = v; publish() }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { state = "Procesando..."; publish() }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            })
        }
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
        active = false
        h.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
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
