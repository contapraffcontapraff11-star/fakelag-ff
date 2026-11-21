package com.fakelag.ff

import android.app.Activity
import android.content.*
import android.net.VpnService
import android.os.*
import android.graphics.*
import android.view.*
import android.widget.*

class MainActivity : Activity() {

    companion object {
        const val REQ_VPN = 1001
    }

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private var lagOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0A0A0A.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0A0A0A.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        tvStatus = TextView(this).apply {
            text = "○ FAKE LAG INATIVO"
            textSize = 20f
            setTextColor(0xFFFF4444.toInt())
            gravity = Gravity.CENTER
        }

        btnToggle = Button(this).apply {
            text = "ATIVAR"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF8B0000.toInt())
            setPadding(80, 40, 80, 40)
        }

        btnToggle.setOnClickListener {
            if (!lagOn) {
                val intent = VpnService.prepare(this)
                if (intent != null) startActivityForResult(intent, REQ_VPN)
                else startFakeLag()
            } else {
                stopFakeLag()
            }
        }

        root.addView(tvStatus)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 60) })
        root.addView(btnToggle)
        setContentView(root)
    }

    private fun startFakeLag() {
        lagOn = true
        tvStatus.text = "● FAKE LAG ATIVO"
        tvStatus.setTextColor(0xFF44FF44.toInt())
        btnToggle.text = "DESATIVAR"
        val svc = Intent(this, FakeLagVpnService::class.java)
        svc.action = FakeLagVpnService.CMD_ENABLE
        startForegroundService(svc)
    }

    private fun stopFakeLag() {
        lagOn = false
        tvStatus.text = "○ FAKE LAG INATIVO"
        tvStatus.setTextColor(0xFFFF4444.toInt())
        btnToggle.text = "ATIVAR"
        val svc = Intent(this, FakeLagVpnService::class.java)
        svc.action = FakeLagVpnService.CMD_DISABLE
        startService(svc)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) startFakeLag()
    }
}
