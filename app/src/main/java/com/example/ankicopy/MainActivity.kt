package com.example.ankicopy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.instructions).text = """
            One-time setup:
            1. Tap "Open Accessibility Settings", find AnkiCopy, turn it ON.
            2. Tap "Allow Display Over Other Apps" (only needed for the
               small status overlay toggled by Start).
            3. Set your two prompts below.
            4. If a button doesn't do what you expect, turn on Debug Mode -
               any unmapped button press will show you its real keycode as
               a toast, since controllers vary in what they report.

            Button mapping:
              A, B        - untouched, configure in AnkiDroid's own gamepad
                             settings (Show Answer / Again)
              X           - bring AnkiDroid to fullscreen (collapses split)
              Y           - split screen: Anki + ChatGPT
              Select      - split screen: Anki + Gemini
              D-pad Up/Dn - controlled smooth scroll
              L1          - copy card + explanation prompt -> paste -> send
              R1          - copy card + scenario prompt -> paste -> send
              L2          - untouched (Anki's own menu navigation)
              R2          - close to home
              Start       - cycle which app L1/R1 send to, toggle overlay
        """.trimIndent()

        findViewById<Button>(R.id.openSettingsBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.overlayPermBtn).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        val explanationField = findViewById<EditText>(R.id.explanationSuffixField)
        explanationField.setText(Prefs.getExplanationSuffix(this))
        findViewById<Button>(R.id.saveExplanationBtn).setOnClickListener {
            Prefs.setExplanationSuffix(this, explanationField.text.toString())
        }

        val scenarioField = findViewById<EditText>(R.id.scenarioSuffixField)
        scenarioField.setText(Prefs.getScenarioSuffix(this))
        findViewById<Button>(R.id.saveScenarioBtn).setOnClickListener {
            Prefs.setScenarioSuffix(this, scenarioField.text.toString())
        }

        val debugSwitch = findViewById<Switch>(R.id.debugSwitch)
        debugSwitch.isChecked = Prefs.getDebugMode(this)
        debugSwitch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            Prefs.setDebugMode(this, isChecked)
        }
    }
}
