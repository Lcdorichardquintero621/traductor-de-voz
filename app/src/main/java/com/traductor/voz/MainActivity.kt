package com.traductor.voz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var btn: Button
    private lateinit var status: TextView
    private lateinit var last: TextView
    private val langs = listOf(
        Triple("ja", "ja-JP", "Japonés"), Triple("en", "en-US", "Inglés"),
        Triple("ko", "ko-KR", "Coreano"), Triple("zh", "zh-CN", "Chino"),
        Triple("fr", "fr-FR", "Francés"), Triple("pt", "pt-BR", "Portugués"),
        Triple("it", "it-IT", "Italiano"), Triple("de", "de-DE", "Alemán")
    )
    private var sel = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        root.addView(TextView(this).apply { text = "Traductor de Voz"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = "1. Elige el idioma del video.\n2. Toca Empezar.\n3. Abre Crunchyroll (o lo que quieras ver) y ponte audífonos con cable. Deja el volumen original bajito."
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
        status = TextView(this).apply { setPadding(0, 20, 0, 20) }
        last = TextView(this).apply { textSize = 18f }
        root.addView(status); root.addView(last)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        TranslatorService.onStatus = { status.text = it; refresh() }
        TranslatorService.onText = { last.text = it }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        TranslatorService.onStatus = null
        TranslatorService.onText = null
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
            .putExtra(TranslatorService.EXTRA_TAG, l.second))
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
