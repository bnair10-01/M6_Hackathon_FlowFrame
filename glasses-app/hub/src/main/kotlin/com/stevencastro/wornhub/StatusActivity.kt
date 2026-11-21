package com.stevencastro.wornhub

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Small status screen on the glasses: is the phone connected, what's the Wi-Fi address. */
class StatusActivity : Activity() {
    private val scope = MainScope()
    private var job: Job? = null
    private lateinit var title: TextView
    private lateinit var detail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HubService.start(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        title = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.rgb(80, 220, 255))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        detail = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(160, 175, 190))
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 0)
        }
        root.addView(title)
        root.addView(detail)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        job = scope.launch {
            HubService.state.collect { s ->
                title.text = s.linkProblem ?: if (s.phones > 0) "iPhone connected" else "Waiting for iPhone…"
                detail.text = buildString {
                    append(if (s.ip.isNotEmpty()) "Wi-Fi ${s.ip} · live view and photos available" else "No Wi-Fi: live view and photos need Wi-Fi")
                    if (s.viewers > 0) append("\nCamera is being viewed on the phone")
                }
            }
        }
    }

    override fun onPause() {
        job?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Starts Worn Hub at boot so the phone can reach the glasses any time. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) HubService.start(context)
    }
}
