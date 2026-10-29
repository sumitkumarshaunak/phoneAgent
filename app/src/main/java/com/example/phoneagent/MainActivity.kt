package com.example.phoneagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("agent", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val keyInput = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val goalInput = EditText(this).apply {
            hint = "Goal, e.g. Open Settings and turn on Wi-Fi"
            minLines = 2
        }
        val accessBtn = Button(this).apply {
            text = "1. Enable accessibility service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val runBtn = Button(this).apply {
            text = "2. Run"
            setOnClickListener {
                val key = keyInput.text.toString().trim()
                val goal = goalInput.text.toString().trim()
                val svc = AgentService.instance
                when {
                    key.isEmpty() || goal.isEmpty() -> toast("Enter API key and goal")
                    svc == null -> toast("Enable the accessibility service first")
                    else -> {
                        prefs.edit().putString("key", key).apply()
                        logView.text = ""
                        svc.start(goal, key)
                        toast("Running. Switch to the app being controlled.")
                    }
                }
            }
        }
        val stopBtn = Button(this).apply {
            text = "Stop"
            setOnClickListener { AgentService.instance?.stop() }
        }
        logView = TextView(this).apply { textSize = 13f }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(keyInput); addView(goalInput); addView(accessBtn); addView(runBtn); addView(stopBtn)
            addView(ScrollView(this@MainActivity).apply { addView(logView) })
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        AgentService.onLog = { msg -> runOnUiThread { logView.append(msg + "\n") } }
    }

    override fun onPause() {
        super.onPause()
        AgentService.onLog = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
