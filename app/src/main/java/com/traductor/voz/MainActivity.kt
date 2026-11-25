package com.traductor.voz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var btn: Button
    private lateinit var status: TextView
    // idioma de traducción, nombre del modelo de voz, texto
    private val langs = listOf(
        Triple("ja", "vosk-model-small-ja-0.22", "Japonés"),
        Triple("en", "vosk-model-small-en-us-0.15", "Inglés")
    )
    private var sel = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        root.addView(TextView(this).apply { text = "Traductor de Voz v3"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Elige el idioma y toca Empezar. La primera vez descarga un modelo de voz (unos 50 MB), con internet. Después ya no lo necesita."
            setPadding(0, 20, 0, 20)
        })
        val spin = Spinner(this)
        spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs.map { it.third })
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { sel = pos }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(spin)
        btn = Button(this)
        btn.setOnClickListener { if (TranslatorService.running) stopService() else start() }
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
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        val l = langs[sel]
        startForegroundService(Intent(this, TranslatorService::class.java)
            .putExtra(TranslatorService.EXTRA_SRC, l.first)
            .putExtra(TranslatorService.EXTRA_MODEL, l.second))
        TranslatorService.running = true
        refresh()
    }

    private fun stopService() {
        stopService(Intent(this, TranslatorService::class.java))
        refresh()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else Toast.makeText(this, "Necesito el permiso del micrófono", Toast.LENGTH_LONG).show()
    }
}
