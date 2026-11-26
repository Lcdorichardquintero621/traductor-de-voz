package com.traductor.voz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class MainActivity : Activity() {
    // Colores tomados de la imagen de las aves
    private val BG = Color.parseColor("#DBF5C9")
    private val INK = Color.parseColor("#25303F")
    private val RED = Color.parseColor("#FD360D")
    private val BLUE = Color.parseColor("#1D5EC4")
    private val TEAL = Color.parseColor("#247066")
    private val YELLOW = Color.parseColor("#F0CE14")
    private val PURPLE = Color.parseColor("#AF5FBE")
    private val LEAF = Color.parseColor("#9CC38D")

    private lateinit var btn: Button
    private lateinit var status: TextView
    private val langs = listOf("en" to "Subtítulos en inglés", "pt" to "Subtítulos en portugués",
        "fr" to "Subtítulos en francés", "it" to "Subtítulos en italiano", "de" to "Subtítulos en alemán")
    private val zones = listOf("Zona: automática", "Zona: horizontal (pantalla completa)",
        "Zona: vertical (video arriba)", "Zona: toda la pantalla")
    private val audios = listOf("Audio: bajar el volumen del anime mientras habla (recomendado)",
        "Audio: voz por el volumen de alarma (independiente)", "Audio: sin cambios")
    private var selAudio = 0
    private var selLang = 0
    private var selZone = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int, radius: Int, strokeDp: Int = 2) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(strokeDp), stroke)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun ring(res: Int, color: Int): View {
        val img = ImageView(this).apply { setImageResource(res); scaleType = ImageView.ScaleType.CENTER_CROP }
        return FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(3), color) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(img, FrameLayout.LayoutParams(dp(64), dp(64)))
        }
    }

    private fun spinner(items: List<String>, onSel: (Int) -> Unit): View {
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onSel(pos) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        return LinearLayout(this).apply {
            background = box(Color.WHITE, LEAF, 14)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            addView(sp, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(24)) }

        // Cabecera: aves + nombre de la app
        val title = SpannableString("Traductor de Película").apply {
            setSpan(ForegroundColorSpan(RED), 0, 9, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(BLUE), 9, 13, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(TEAL), 13, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ring(R.drawable.ave1, YELLOW))
        header.addView(TextView(this).apply {
            text = title; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(ring(R.drawable.ave2, RED))
        root.addView(header, lp())

        root.addView(TextView(this).apply {
            text = "♪ Subtítulos que hablan español ♫"; textSize = 14f; setTextColor(PURPLE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, lp(8))

        root.addView(TextView(this).apply {
            text = "1. Toca Empezar y acepta compartir TODA la pantalla.\n" +
                "2. Abre Crunchyroll, activa los subtítulos en inglés y ponlo en pantalla completa (horizontal).\n" +
                "3. La app lee cada subtítulo y te lo dice en español."
            textSize = 14f; setTextColor(INK)
            background = box(Color.WHITE, YELLOW, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }, lp(16))

        root.addView(spinner(langs.map { it.second }) { selLang = it }, lp(14))
        root.addView(spinner(zones) { selZone = it }, lp(8))
        root.addView(spinner(audios) { selAudio = it }, lp(8))

        btn = Button(this).apply {
            textSize = 18f; isAllCaps = false; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            setOnClickListener { if (TranslatorService.running) stopSvc() else start() }
        }
        root.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(16) })

        root.addView(TextView(this).apply {
            text = "🎬 Lo que está pasando"; textSize = 14f; setTextColor(TEAL); typeface = Typeface.DEFAULT_BOLD
        }, lp(18))
        status = TextView(this).apply {
            textSize = 14f; typeface = Typeface.MONOSPACE; setTextColor(INK)
            background = box(Color.WHITE, LEAF, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(status, lp(6))

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        TranslatorService.onStatus = { status.text = it; refresh() }
        status.text = TranslatorService.lastReport
        refresh()
    }

    override fun onPause() {
        super.onPause()
        TranslatorService.onStatus = null
    }

    private fun refresh() {
        val on = TranslatorService.running
        btn.text = if (on) "■  Detener" else "▶  Empezar"
        val c = if (on) RED else TEAL
        btn.background = box(c, c, 28, 0)
    }

    private fun start() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1); return
        }
        launchCapture()
    }

    private fun launchCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), 20)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        launchCapture()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 20) return
        if (res == RESULT_OK && data != null) {
            startForegroundService(Intent(this, TranslatorService::class.java)
                .putExtra(TranslatorService.EXTRA_CODE, res)
                .putExtra(TranslatorService.EXTRA_DATA, data)
                .putExtra(TranslatorService.EXTRA_SRC, langs[selLang].first)
                .putExtra(TranslatorService.EXTRA_ZONE, selZone)
                .putExtra(TranslatorService.EXTRA_AUDIO, selAudio))
            TranslatorService.running = true
            refresh()
        } else {
            Toast.makeText(this, "Necesito el permiso para capturar la pantalla", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopSvc() {
        stopService(Intent(this, TranslatorService::class.java))
        refresh()
    }
}
