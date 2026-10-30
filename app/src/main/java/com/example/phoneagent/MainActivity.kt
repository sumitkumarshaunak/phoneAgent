package com.example.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.core.app.NotificationManagerCompat

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var notifBtn: Button
    private lateinit var waFilterInput: EditText
    private lateinit var waInstructionInput: EditText
    private lateinit var waAutoReplyCheck: CheckBox

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
            hint = "Goal, e.g. Call Mom, WhatsApp Mom: Hello, or Open Settings"
            minLines = 2
        }
        val accessBtn = Button(this).apply {
            text = "1. Enable accessibility service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        notifBtn = Button(this).apply {
            text = "Enable WhatsApp Notification Listener"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }

        waFilterInput = EditText(this).apply {
            hint = "WhatsApp Filter: Sender name (optional)"
            setText(prefs.getString("wa_filter_sender", ""))
        }

        waInstructionInput = EditText(this).apply {
            hint = "WhatsApp AI Rule (e.g. Reply saying I am driving)"
            setText(prefs.getString("wa_ai_instruction", ""))
        }

        waAutoReplyCheck = CheckBox(this).apply {
            text = "Auto-send AI replies to WhatsApp"
            isChecked = prefs.getBoolean("wa_auto_reply", false)
        }

        val runBtn = Button(this).apply {
            text = "2. Run Goal"
            setOnClickListener {
                val key = keyInput.text.toString().trim()
                val goal = goalInput.text.toString().trim()
                val svc = AgentService.instance
                val isCall = goal.matches(Regex("""^(?:call|dial|phone)\s+.+$""", RegexOption.IGNORE_CASE))
                val isWhatsApp = goal.matches(Regex("""^(?:send\s+)?(?:whatsapp|wa|message|text)\s+.+$""", RegexOption.IGNORE_CASE))
                val isFastPath = isCall || isWhatsApp

                when {
                    goal.isEmpty() -> toast("Enter a goal")
                    !isFastPath && key.isEmpty() -> toast("Enter API key")
                    svc == null -> toast("Enable the accessibility service first")
                    else -> {
                        savePrefs(key)
                        logView.text = ""
                        svc.start(goal, key)
                        if (!isFastPath) toast("Running. Switch to the app being controlled.")
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
            addView(keyInput)
            addView(goalInput)
            addView(accessBtn)
            addView(notifBtn)
            addView(waFilterInput)
            addView(waInstructionInput)
            addView(waAutoReplyCheck)
            addView(runBtn)
            addView(stopBtn)
            addView(ScrollView(this@MainActivity).apply { addView(logView) })
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        AgentService.onLog = { msg -> runOnUiThread { logView.append(msg + "\n") } }
        val isNotifEnabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        notifBtn.text = if (isNotifEnabled) "Notification Listener: Active ✅" else "Enable WhatsApp Notification Listener"
    }

    override fun onPause() {
        super.onPause()
        AgentService.onLog = null
        val key = getSharedPreferences("agent", MODE_PRIVATE).getString("key", "") ?: ""
        savePrefs(key)
    }

    private fun savePrefs(key: String) {
        val prefs = getSharedPreferences("agent", MODE_PRIVATE)
        prefs.edit().apply {
            if (key.isNotEmpty()) putString("key", key)
            putString("wa_filter_sender", waFilterInput.text.toString().trim())
            putString("wa_ai_instruction", waInstructionInput.text.toString().trim())
            putBoolean("wa_auto_reply", waAutoReplyCheck.isChecked)
            apply()
        }
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
