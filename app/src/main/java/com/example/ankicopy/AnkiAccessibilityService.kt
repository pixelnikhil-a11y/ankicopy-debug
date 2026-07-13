package com.example.ankicopy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast

/**
 * Button mapping (edit constants/keycodes below if your controller differs -
 * turn on Debug Mode from the app to see the real keycode for any button
 * you press, since gamepads vary in what they report for face buttons):
 *
 *   A            -> passthrough, not intercepted (configure "Show Answer" in
 *                   AnkiDroid's own gamepad/keymap settings)
 *   B            -> passthrough, not intercepted (leave as AnkiDroid's
 *                   existing "Again" mapping)
 *   X            -> Bring AnkiDroid to foreground / fullscreen (collapses
 *                   split screen back to just Anki)
 *   Y            -> Open AnkiDroid + ChatGPT split screen
 *   SELECT       -> Open AnkiDroid + Gemini split screen
 *   DPAD_UP      -> Controlled smooth scroll up
 *   DPAD_DOWN    -> Controlled smooth scroll down
 *   L1 (BUTTON_L1) -> Copy whole card + explanation prompt -> paste -> send
 *   R1 (BUTTON_R1) -> Copy whole card + scenario-application prompt -> paste -> send
 *   L2 (BUTTON_L2) -> passthrough (left as AnkiDroid's existing menu-nav button)
 *   R2 (BUTTON_R2) -> Close to home
 *   START        -> Toggle small status overlay + cycle which AI app L1/R1 target
 */
class AnkiAccessibilityService : AccessibilityService() {

    companion object {
        const val ANKI_PACKAGE = "com.ichi2.anki"
        private const val APP_SWITCH_TIMEOUT_MS = 4000L
        private const val POLL_INTERVAL_MS = 120L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusOverlayView: TextView? = null
    private var overlayShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        toast("AnkiCopy active - send target: ${Prefs.getTarget(this).label}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.onKeyEvent(event)

        return when (event.keyCode) {
            // A / B intentionally NOT handled here - falls through to
            // AnkiDroid's own configured gamepad mapping.
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B -> super.onKeyEvent(event)

            KeyEvent.KEYCODE_BUTTON_X -> { bringAnkiToFullscreen(); true }
            KeyEvent.KEYCODE_BUTTON_Y -> { openSplitScreen(AiTarget.CHATGPT); true }
            KeyEvent.KEYCODE_BUTTON_SELECT -> { openSplitScreen(AiTarget.GEMINI); true }

            KeyEvent.KEYCODE_DPAD_UP -> { controlledScroll(down = false); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { controlledScroll(down = true); true }

            KeyEvent.KEYCODE_BUTTON_L1 -> { copyCardAndSend(Prefs.getExplanationSuffix(this)); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { copyCardAndSend(Prefs.getScenarioSuffix(this)); true }

            // L2 intentionally NOT handled - passthrough to AnkiDroid's
            // existing menu-navigation mapping.
            KeyEvent.KEYCODE_BUTTON_L2 -> super.onKeyEvent(event)

            KeyEvent.KEYCODE_BUTTON_R2 -> { performGlobalAction(GLOBAL_ACTION_HOME); true }
            KeyEvent.KEYCODE_BUTTON_START -> { cycleTargetAndToggleOverlay(); true }

            else -> {
                if (Prefs.getDebugMode(this)) {
                    toast("Unmapped key: ${KeyEvent.keyCodeToString(event.keyCode)}")
                }
                super.onKeyEvent(event)
            }
        }
    }

    // ---------- L1 / R1: copy card + prompt, paste, send ----------

    private fun copyCardAndSend(promptSuffix: String) {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != ANKI_PACKAGE) {
            toast("Open AnkiDroid first")
            return
        }

        val webViewNode = findNodeByClass(root, "android.webkit.WebView")
        if (webViewNode == null) {
            toast("Card content not found - try again")
            return
        }

        val sb = StringBuilder()
        collectText(webViewNode, sb)
        val cleaned = cleanText(sb.toString())

        if (cleaned.isBlank()) {
            toast("Nothing to copy")
            return
        }

        val finalText = if (promptSuffix.isNotBlank()) "$cleaned\n\n$promptSuffix" else cleaned

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("anki_card", finalText))

        val target = Prefs.getTarget(this)
        launchApp(target.packageName)

        val start = System.currentTimeMillis()
        pollUntilForegroundThenSend(target, start)
    }

    private fun pollUntilForegroundThenSend(target: AiTarget, startTime: Long) {
        val root = rootInActiveWindow
        if (root != null && root.packageName?.toString() == target.packageName) {
            pasteAndSend(target, attempt = 1)
            return
        }
        if (System.currentTimeMillis() - startTime > APP_SWITCH_TIMEOUT_MS) {
            toast("${target.label} didn't come to foreground - card is still on clipboard, paste manually")
            return
        }
        mainHandler.postDelayed({ pollUntilForegroundThenSend(target, startTime) }, POLL_INTERVAL_MS)
    }

    private fun pasteAndSend(target: AiTarget, attempt: Int) {
        val root = rootInActiveWindow ?: return

        val inputNode = findNodeByHints(root, target.inputHints, editableOnly = true)
        if (inputNode == null) {
            if (attempt < 3) {
                mainHandler.postDelayed({ pasteAndSend(target, attempt + 1) }, 300L)
            } else {
                toast("Copied to clipboard - couldn't find ${target.label}'s input box, paste manually")
            }
            return
        }

        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        // Give the UI time to enable the send button once text is present,
        // and retry a couple of times since some apps are slow to update.
        trySendAfterPaste(target, inputNode, attempt = 1)
    }

    private fun trySendAfterPaste(target: AiTarget, inputNode: AccessibilityNodeInfo, attempt: Int) {
        mainHandler.postDelayed({
            val freshRoot = rootInActiveWindow
            if (freshRoot == null) return@postDelayed

            val sendNode = findNodeByHints(freshRoot, target.sendHints, editableOnly = false)
            if (sendNode != null && sendNode.isClickable && sendNode.isEnabled) {
                sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                toast("Sent to ${target.label}")
                return@postDelayed
            }

            if (attempt < 4) {
                trySendAfterPaste(target, inputNode, attempt + 1)
                return@postDelayed
            }

            // Last resort: some chat inputs treat the IME "enter" action as
            // send. This mimics pressing Enter on the field itself.
            val performedIme = inputNode.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
            if (performedIme) {
                toast("Sent to ${target.label} (via Enter)")
            } else {
                toast("Pasted - couldn't find ${target.label}'s send button, tap Send manually")
            }
        }, 350L)
    }

    // ---------- X: bring Anki to fullscreen / collapse split ----------

    private fun bringAnkiToFullscreen() {
        val intent = packageManager.getLaunchIntentForPackage(ANKI_PACKAGE)
        if (intent == null) {
            toast("AnkiDroid not installed")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    // ---------- Y / Select: split screen ----------

    private fun openSplitScreen(target: AiTarget) {
        val ankiIntent = packageManager.getLaunchIntentForPackage(ANKI_PACKAGE)
        val aiIntent = packageManager.getLaunchIntentForPackage(target.packageName)

        if (ankiIntent == null) { toast("AnkiDroid not installed"); return }
        if (aiIntent == null) { toast("${target.label} not installed"); return }

        ankiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(ankiIntent)

        mainHandler.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                aiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            } else {
                aiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(aiIntent)
        }, 400L)
    }

    // ---------- D-pad: controlled scroll ----------

    private fun controlledScroll(down: Boolean) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        val startY = if (down) height * 0.75f else height * 0.25f
        val endY = if (down) height * 0.35f else height * 0.65f
        val x = width * 0.5f

        val path = Path()
        path.moveTo(x, startY)
        path.lineTo(x, endY)

        val duration = 550L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, null, null)
        if (!dispatched && Prefs.getDebugMode(this)) {
            toast("Scroll gesture failed to dispatch")
        }
    }

    // ---------- Start: cycle L1/R1 target + toggle overlay ----------

    private fun cycleTargetAndToggleOverlay() {
        val current = Prefs.getTarget(this)
        val next = AiTarget.next(current)
        Prefs.setTarget(this, next)
        toast("L1/R1 send target: ${next.label}")

        if (overlayShown) updateOverlayText() else showOverlay()
    }

    private fun showOverlay() {
        if (statusOverlayView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            text = "AnkiCopy -> ${Prefs.getTarget(this@AnkiAccessibilityService).label}"
            setBackgroundColor(0xAA000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(16, 8, 16, 8)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 16
        params.y = 48

        wm.addView(tv, params)
        statusOverlayView = tv
        overlayShown = true
    }

    private fun updateOverlayText() {
        statusOverlayView?.text = "AnkiCopy -> ${Prefs.getTarget(this).label}"
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val view = statusOverlayView
        if (view != null) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
            statusOverlayView = null
            overlayShown = false
        }
        return super.onUnbind(intent)
    }

    // ---------- node tree helpers ----------

    private fun findNodeByClass(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className == className) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByClass(child, className)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findNodeByHints(
        node: AccessibilityNodeInfo,
        hints: List<String>,
        editableOnly: Boolean
    ): AccessibilityNodeInfo? {
        val idMatch = node.viewIdResourceName?.lowercase()?.let { id -> hints.any { id.contains(it) } } ?: false
        val descMatch = node.contentDescription?.toString()?.lowercase()?.let { d -> hints.any { d.contains(it) } } ?: false
        val textMatch = node.text?.toString()?.lowercase()?.let { t -> hints.any { t.contains(it) } } ?: false

        if ((idMatch || descMatch || textMatch) && (!editableOnly || node.isEditable)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByHints(child, hints, editableOnly)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder) {
        val nodeText = node.text
        if (!nodeText.isNullOrBlank()) out.append(nodeText).append("\n")
        val desc = node.contentDescription
        if (!desc.isNullOrBlank() && desc.toString() != nodeText?.toString()) out.append(desc).append("\n")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
            child.recycle()
        }
    }

    private fun cleanText(raw: String): String {
        val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val deduped = mutableListOf<String>()
        for (line in lines) {
            if (deduped.isEmpty() || deduped.last() != line) deduped.add(line)
        }
        return deduped.joinToString("\n")
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) { toast("$packageName not installed"); return }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
