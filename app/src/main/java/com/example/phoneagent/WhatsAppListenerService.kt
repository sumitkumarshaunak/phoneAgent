package com.example.phoneagent

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*

class WhatsAppListenerService : NotificationListenerService() {

    companion object {
        var isConnected = false
        var instance: WhatsAppListenerService? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        instance = this
        AgentService.log("🟢 WhatsApp Notification Listener connected.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        instance = null
        AgentService.log("🔴 WhatsApp Notification Listener disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null || sbn.packageName != "com.whatsapp") return

        // Skip group summaries to prevent duplicate processing
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: return

        // Ignore empty, system status, or summary notifications
        if (title.isBlank() || text.isBlank()) return
        if (title.equals("WhatsApp", ignoreCase = true) && text.contains("messages", ignoreCase = true)) return

        val prefs = getSharedPreferences("agent", MODE_PRIVATE)
        val filterSender = prefs.getString("wa_filter_sender", "")?.trim() ?: ""
        val aiInstruction = prefs.getString("wa_ai_instruction", "")?.trim() ?: ""
        val autoReply = prefs.getBoolean("wa_auto_reply", false)
        val apiKey = prefs.getString("key", "")?.trim() ?: ""

        // Check sender filter if specified
        if (filterSender.isNotEmpty() && !title.contains(filterSender, ignoreCase = true)) {
            return
        }

        AgentService.log("📩 WhatsApp from '$title': $text")

        // If no AI instruction or no API key, skip AI analysis
        if (apiKey.isEmpty() || aiInstruction.isEmpty()) {
            return
        }

        scope.launch {
            try {
                AgentService.log("🧠 Processing message with AI...")
                val analysis = GeminiClient.analyzeMessage(apiKey, title, text, aiInstruction)
                val thought = analysis.optString("analysis", "")
                val shouldReply = analysis.optBoolean("should_reply", false)
                val reply = analysis.optString("reply", "").trim()

                if (thought.isNotEmpty()) {
                    AgentService.log("💡 AI: $thought")
                }

                if (autoReply && shouldReply && reply.isNotEmpty()) {
                    val sent = sendRemoteReply(sbn, reply)
                    if (sent) {
                        AgentService.log("📤 Auto-replied to '$title': \"$reply\"")
                    } else {
                        AgentService.log("⚠️ Could not send inline reply (no direct reply action found).")
                    }
                }
            } catch (e: Exception) {
                AgentService.log("Error processing WhatsApp AI: ${e.message}")
            }
        }
    }

    private fun sendRemoteReply(sbn: StatusBarNotification, replyText: String): Boolean {
        val actions = sbn.notification.actions ?: return false
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (remoteInput in remoteInputs) {
                val intent = Intent().apply {
                    val bundle = Bundle().apply {
                        putCharSequence(remoteInput.resultKey, replyText)
                    }
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, bundle)
                }
                try {
                    action.actionIntent.send(this, 0, intent)
                    return true
                } catch (e: Exception) {
                    AgentService.log("Remote reply failed: ${e.message}")
                }
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
