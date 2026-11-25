package com.traductor.voz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var btn: Button
    private lateinit var status: TextView
    private val langs = listOf("en" to "Subtítulos en inglés", "pt" to "Subtítulos en portugués",
        "fr" to "Subtítulos en francés", "it" to "Subtítulos en italiano", "de" to "Subtítulos en alemán")
    private val zones = listOf("Zona: automática", "Zona: horizontal (pantalla completa)",
        "Zona: vertical (video arriba)", "Zona: toda la pantalla")
    private var selLang = 0
    private var selZone = 0

    private fun spinner(items: List<String>, onSel: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onSel(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        root.addView(TextView(this).apply { text = "Traductor de Subtítulos v4"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "1. Toca Empezar y acepta compartir TODA la pantalla.\n" +
                "2. Abre Crunchyroll, activa los subtítulos en inglés y ponlo en pantalla completa (horizontal).\n" +
                "3. La app lee cada subtítulo y te lo dice en español."
            setPadding(0, 20, 0, 20)
        })
        root.addView(spinner(langs.map { it.second }) { selLang = it })
        root.addView(spinner(zones) { selZone = it })
        btn = Button(this)
        btn.setOnClickListener { if (TranslatorService.running) stopSvc() else start() }
        root.addView(btn)
        status = TextView(this).apply { setPadding(0, 20, 0, 20); textSize = 16f; typeface = Typeface.MONOSPACE }
        root.addView(ScrollView(this).apply { addView(status) })
        setContentView(root)
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

    private fun refresh() { btn.text = if (TranslatorService.running) "Detener" else "Empezar" }

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
                .putExtra(TranslatorService.EXTRA_ZONE, selZone))
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
