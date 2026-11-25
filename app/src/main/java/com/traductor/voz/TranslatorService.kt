package com.traductor.voz

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class TranslatorService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SRC = "src"
        const val EXTRA_ZONE = "zone"
        @Volatile var running = false
        @Volatile var lastReport = "Sin iniciar"
        var onStatus: ((String) -> Unit)? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var projection: MediaProjection? = null
    private var projCb: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var ocr: TextRecognizer? = null
    private var translator: Translator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var src = "en"
    private var zone = 0
    private var pending = 0
    @Volatile private var alive = false
    @Volatile private var ready = false
    @Volatile private var busy = false

    // Lectura de subtítulos
    private var lastCand = ""
    private var lastSpoken = ""
    private var stable = 0
    private var emptyCount = 0

    // Panel
    private var state = "Iniciando..."
    @Volatile private var screenInfo = "-"
    private var read = "-"
    private var spoken = "-"
    private var voiceInfo = "iniciando..."
    private var lastPublish = 0L

    private fun publish(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPublish < 250) return
        lastPublish = now
        lastReport = "Estado: $state\nPantalla: $screenInfo\nVoz en español: $voiceInfo\nLeí: $read\nTraducción: $spoken"
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
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        cleanup()
        src = intent?.getStringExtra(EXTRA_SRC) ?: "en"
        zone = intent?.getIntExtra(EXTRA_ZONE, 0) ?: 0
        read = "-"; spoken = "-"; voiceInfo = "iniciando..."; screenInfo = "-"
        lastCand = ""; lastSpoken = ""; stable = 0; emptyCount = 0
        startAsForeground()
        running = true; alive = true; ready = false; busy = false
        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        tts = TextToSpeech(this, this)
        translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.SPANISH).build()
        )
        try {
            startProjection(code, data)
        } catch (e: Exception) {
            state = "No se pudo iniciar la captura de pantalla: ${e.message}"; publish(true)
            return START_NOT_STICKY
        }
        state = "Preparando traducción (necesita internet la primera vez)..."
        publish(true)
        translator!!.downloadModelIfNeeded()
            .addOnSuccessListener { ready = true; state = "Leyendo subtítulos..."; publish(true) }
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
            .setContentTitle("Leyendo subtítulos")
            .setContentText("Toca Detener para terminar")
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    // ---- Captura de pantalla ----
    @Suppress("DEPRECATION")
    private fun screenSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        var w = dm.widthPixels; var hg = dm.heightPixels
        val m = maxOf(w, hg)
        if (m > 2600) { val k = 2600f / m; w = (w * k).toInt(); hg = (hg * k).toInt() }
        return Triple(w, hg, dm.densityDpi)
    }

    private fun startProjection(code: Int, data: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mgr.getMediaProjection(code, data)
        projection = p
        val cb = object : MediaProjection.Callback() { override fun onStop() { h.post { stopSelf() } } }
        projCb = cb
        p.registerCallback(cb, h)
        val (w, hg, d) = screenSize()
        capW = w; capH = hg
        val r = ImageReader.newInstance(w, hg, PixelFormat.RGBA_8888, 2)
        reader = r
        display = p.createVirtualDisplay("subtitulos", w, hg, d,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        val t = HandlerThread("captura"); t.start()
        thread = t
        bg = Handler(t.looper).also { it.postDelayed(tick, 600) }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            try { capture() } catch (_: Exception) { busy = false }
            bg?.postDelayed(this, 400)
        }
    }

    private fun zoneFor(w: Int, hg: Int): Pair<Float, Float> = when (zone) {
        1 -> 0.62f to 0.97f
        2 -> 0.10f to 0.31f
        3 -> 0f to 1f
        else -> if (w > hg) 0.62f to 0.97f else 0.10f to 0.31f
    }

    private fun toBitmap(img: Image): Bitmap {
        val pl = img.planes[0]
        val pad = pl.rowStride - pl.pixelStride * img.width
        val bmp = Bitmap.createBitmap(img.width + pad / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(pl.buffer)
        if (pad == 0) return bmp
        val cut = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        bmp.recycle()
        return cut
    }

    private fun capture() {
        val (w, hg, d) = screenSize()
        if (w != capW || hg != capH) {            // se giró la pantalla
            capW = w; capH = hg
            val old = reader
            val nr = ImageReader.newInstance(w, hg, PixelFormat.RGBA_8888, 2)
            reader = nr
            display?.resize(w, hg, d)
            display?.surface = nr.surface
            old?.close()
            return
        }
        val img = reader?.acquireLatestImage() ?: return
        val bmp = try { toBitmap(img) } finally { img.close() }
        val (top, bot) = zoneFor(w, hg)
        val y0 = (hg * top).toInt().coerceIn(0, hg - 1)
        val hh = ((hg * bot).toInt() - y0).coerceIn(1, hg - y0)
        val crop = Bitmap.createBitmap(bmp, 0, y0, bmp.width, hh)
        bmp.recycle()
        screenInfo = "${w}x$hg ${if (w > hg) "horizontal" else "vertical"}, zona ${(top * 100).toInt()}-${(bot * 100).toInt()}%"
        if (busy || !ready) { crop.recycle(); return }
        busy = true
        ocr?.process(InputImage.fromBitmap(crop, 0))
            ?.addOnSuccessListener { r -> busy = false; crop.recycle(); onOcr(r.text) }
            ?.addOnFailureListener { busy = false; crop.recycle() }
    }

    // ---- Lectura y traducción ----
    private val timeRe = Regex("\\d{1,2}:\\d{2}(:\\d{2})?")

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun sim(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        val m = maxOf(x.length, y.length)
        if (m == 0) return 1.0
        return 1.0 - lev(x, y).toDouble() / m
    }

    private fun onOcr(raw: String) {
        if (!alive) return
        val cand = raw.split("\n").map { it.replace(timeRe, "").trim() }
            .filter { l -> l.count { it.isLetter() } >= 2 }.joinToString(" ")
        read = if (raw.isBlank()) "(nada en la zona)" else raw.replace("\n", " / ").take(150)
        if (cand.isBlank()) {
            emptyCount++; stable = 0; lastCand = ""
            if (emptyCount >= 3) lastSpoken = ""
            publish(); return
        }
        emptyCount = 0
        stable = if (lastCand.isNotEmpty() && sim(cand, lastCand) >= 0.9) stable + 1 else 1
        lastCand = cand
        if (stable >= 2 && sim(cand, lastSpoken) < 0.85) { lastSpoken = cand; translate(cand) }
        publish()
    }

    private fun translate(text: String) {
        translator?.translate(text)
            ?.addOnSuccessListener { es -> spoken = es; publish(true); speak(es) }
            ?.addOnFailureListener { spoken = "ERROR al traducir: ${it.message}"; publish(true) }
    }

    private fun speak(es: String) {
        if (!ttsReady) return
        if (pending > 2) { tts?.stop(); pending = 0 }
        pending++
        updateRate()
        tts?.speak(es, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    private fun updateRate() { tts?.setSpeechRate((1.0f + 0.15f * pending).coerceAtMost(1.6f)) }

    private fun cleanup() {
        alive = false; ready = false
        h.removeCallbacksAndMessages(null)
        bg?.removeCallbacksAndMessages(null)
        thread?.quitSafely(); thread = null; bg = null
        projCb?.let { projection?.unregisterCallback(it) }
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null; projCb = null
        ocr?.close(); ocr = null
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
