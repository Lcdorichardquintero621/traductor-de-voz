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
        var onText: ((String) -> Unit)? = null
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

    override fun onBind(i: Intent?): IBinder? = null

    override fun onInit(s: Int) {
        tts?.language = Locale("es")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { h.post { pending = maxOf(0, pending - 1); updateRate() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { h.post { pending = maxOf(0, pending - 1); updateRate() } }
        })
        ttsReady = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        cleanup()
        src = intent?.getStringExtra(EXTRA_SRC) ?: "ja"
        tag = intent?.getStringExtra(EXTRA_TAG) ?: "ja-JP"
        startAsForeground()
        running = true
        tts = TextToSpeech(this, this)
        translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.SPANISH).build()
        )
        onStatus?.invoke("Preparando idioma (la primera vez necesita internet)...")
        translator!!.downloadModelIfNeeded()
            .addOnSuccessListener { active = true; onStatus?.invoke("Escuchando..."); listen() }
            .addOnFailureListener { onStatus?.invoke("No se pudo bajar el idioma. Revisa tu internet."); stopSelf() }
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
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle) {
                    r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { translate(it) }
                    h.postDelayed({ listen() }, 50)
                }
                override fun onError(e: Int) {
                    val wait = if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800L else 150L
                    h.postDelayed({ listen() }, wait)
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
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
        translator?.translate(text)?.addOnSuccessListener { es ->
            onText?.invoke(es)
            speak(es)
        }
    }

    private fun speak(es: String) {
        if (!ttsReady) return
        if (pending > 3) { tts?.stop(); pending = 0 }
        pending++
        updateRate()
        tts?.speak(es, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    // Si se acumulan frases, habla más rápido para no atrasarse
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
        onStatus?.invoke("Detenido")
        super.onDestroy()
    }
}
