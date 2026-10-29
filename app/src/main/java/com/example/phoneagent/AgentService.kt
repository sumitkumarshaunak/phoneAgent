package com.example.phoneagent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class AgentService : AccessibilityService() {

    companion object {
        var instance: AgentService? = null
        var onLog: ((String) -> Unit)? = null
        fun log(msg: String) { onLog?.invoke(msg) }

        private const val MAX_STEPS = 15
        private const val STEP_DELAY_MS = 1500L

        // The agent refuses to operate inside these apps. Extend as needed.
        private val BLOCKED_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",
            "net.one97.paytm",
            "com.sbi.lotusintouch",
            "com.snapwork.hdfc",
            "com.android.vending" // Play Store
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onServiceConnected() { instance = this; log("Accessibility service connected.") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; job?.cancel(); return super.onUnbind(intent) }

    fun start(goal: String, apiKey: String) {
        job?.cancel()

        // Fast-path: Check for direct call intent (e.g., "call Mom", "dial 9876543210")
        val callRegex = Regex("""^(?:call|dial|phone)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val match = callRegex.find(goal.trim())
        if (match != null) {
            val target = match.groupValues[1].trim()
            handleDirectCall(target)
            return
        }

        job = scope.launch { runAgent(goal, apiKey) }
    }

    fun stop() { job?.cancel(); log("Stopped.") }

    private suspend fun runAgent(goal: String, apiKey: String) {
        val history = mutableListOf<String>()
        log("Goal: $goal")
        try {
            for (step in 1..MAX_STEPS) {
                val snap = ScreenReader.read(rootInActiveWindow)
                if (snap.pkg in BLOCKED_PACKAGES) { log("Blocked app (${snap.pkg}). Stopping."); return }

                val action = withContext(Dispatchers.IO) {
                    GeminiClient.nextAction(apiKey, goal, "App: ${snap.pkg}\n${snap.text}", history)
                }
                val type = action.optString("action")
                val id = action.optInt("id", -1)
                val text = action.optString("text")
                log("Step $step: $type ${if (id >= 0) "#$id" else ""} ${text.take(60)}  — ${action.optString("thought").take(100)}")
                history.add("$type id=$id text=$text app=${action.optString("app")}")

                when (type) {
                    "done" -> { log("Done: $text"); return }
                    "ask_user" -> { log("Needs you: $text"); return }
                    "click" -> click(snap.nodes.getOrNull(id))
                    "type" -> typeText(snap.nodes.getOrNull(id), text)
                    "scroll_down" -> scroll(snap.nodes, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    "scroll_up" -> scroll(snap.nodes, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "open_app" -> openApp(action.optString("app"))
                    "wait" -> {}
                    else -> { log("Unknown action '$type'. Stopping."); return }
                }
                delay(STEP_DELAY_MS)
            }
            log("Reached the $MAX_STEPS-step limit. Stopping.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Error: ${e.message}")
        }
    }

    private fun click(node: AccessibilityNodeInfo?) {
        var n = node
        while (n != null && !n.isClickable) n = n.parent
        if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) log("Click failed.")
    }

    private fun typeText(node: AccessibilityNodeInfo?, text: String) {
        if (node == null) { log("Type failed: no such element."); return }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) log("Type failed.")
    }

    private fun scroll(nodes: List<AccessibilityNodeInfo>, direction: Int) {
        val target = nodes.firstOrNull { it.isScrollable }
        if (target?.performAction(direction) != true) log("Nothing to scroll.")
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().contains(name, ignoreCase = true)
        }
        if (match == null) { log("App '$name' not found."); return }
        if (match.activityInfo.packageName in BLOCKED_PACKAGES) { log("App '$name' is blocked."); return }
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) startActivity(intent)
    }

    private data class ContactItem(val name: String, val number: String, val type: String)

    private fun handleDirectCall(target: String) {
        log("Goal: Call '$target'")

        // If target is already a raw phone number (e.g. +1234567890, 9876543210)
        if (target.matches(Regex("""^[+]?[\d\s\-()]{3,}$"""))) {
            log("Direct phone number detected. Calling $target...")
            placeCall(target)
            return
        }

        val contacts = findContacts(target)
        if (contacts.isEmpty()) {
            log("❌ No contact found matching '$target'. Please verify the name or enter the number directly.")
            return
        }

        // Deduplicate numbers by normalized digits so identical synced numbers count as one
        val uniqueNumbers = contacts.distinctBy { it.number.replace(Regex("""[^0-9+]"""), "") }

        when {
            uniqueNumbers.size == 1 -> {
                val match = uniqueNumbers.first()
                log("📞 Found 1 number for '${match.name}': ${match.number} (${match.type}). Calling...")
                placeCall(match.number)
            }
            else -> {
                log("⚠️ Multiple numbers found for '$target':")
                uniqueNumbers.forEachIndexed { i, c ->
                    log("   [${i + 1}] ${c.name} — ${c.number} (${c.type})")
                }
                log("👉 Please specify the exact name or number to call.")
            }
        }
    }

    private fun findContacts(query: String): List<ContactItem> {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            log("⚠️ Missing Contacts permission. Please grant permission in App Info.")
            return emptyList()
        }
        val results = mutableListOf<ContactItem>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        try {
            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                    val num = if (numIdx >= 0) cursor.getString(numIdx) ?: "" else ""
                    val typeInt = if (typeIdx >= 0) cursor.getInt(typeIdx) else 0
                    val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, typeInt, "").toString()
                    if (num.isNotBlank()) {
                        results.add(ContactItem(name, num, typeLabel))
                    }
                }
            }
        } catch (e: Exception) {
            log("Error reading contacts: ${e.message}")
        }
        return results
    }

    private fun placeCall(number: String) {
        val uri = Uri.parse("tel:${Uri.encode(number)}")
        val intent = if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Intent(Intent.ACTION_CALL, uri)
        } else {
            Intent(Intent.ACTION_DIAL, uri)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            log("Failed to start call: ${e.message}")
        }
    }
}
