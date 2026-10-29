package com.example.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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

        checkAndRequestPermissions()

        val keyInput = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val goalInput = EditText(this).apply {
            hint = "Goal, e.g. Call Mom or Open Settings"
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
                val isCall = goal.matches(Regex("""^(?:call|dial|phone)\s+.+$""", RegexOption.IGNORE_CASE))

                when {
                    goal.isEmpty() -> toast("Enter a goal")
                    !isCall && key.isEmpty() -> toast("Enter API key")
                    svc == null -> toast("Enable the accessibility service first")
                    else -> {
                        if (key.isNotEmpty()) prefs.edit().putString("key", key).apply()
                        logView.text = ""
                        svc.start(goal, key)
                        if (!isCall) toast("Running. Switch to the app being controlled.")
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

    private fun checkAndRequestPermissions() {
        val needed = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 101)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
