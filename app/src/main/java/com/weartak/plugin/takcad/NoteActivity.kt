// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Wear text entry (keyboard or voice through the system IME); text is kept as a draft until CAD confirms it. */
class NoteActivity : Activity() {
    companion object {
        const val EXTRA_INCIDENT_ID = "incidentId"

        fun intent(context: Context, incidentId: String): Intent =
            Intent(context, NoteActivity::class.java).putExtra(EXTRA_INCIDENT_ID, incidentId)
    }

    private val runtime get() = (application as CadApplication).runtime
    private lateinit var incidentId: String
    private lateinit var input: EditText
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_INCIDENT_ID)
        val incident = runtime.visibleIncidents().firstOrNull { it.ask.id == id && it.ask.canonical != null }
        if (id == null || incident == null) {
            finish()
            return
        }
        incidentId = id
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = if (resources.configuration.isScreenRound) 32 else 20
            setPadding(dp(side), dp(28), dp(side), dp(40))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.note_title, incident.ask.title)
            textSize = 16f
            isAccessibilityHeading = true
        })
        input = EditText(this).apply {
            hint = getString(R.string.note_hint)
            contentDescription = getString(R.string.note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_DONE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(InputFilter.LengthFilter(CadStatus.MAX_NOTE_CHARS))
            minHeight = dp(48)
            setText(savedInstanceState?.getString("text") ?: runtime.noteDraft(id))
            setSelection(text.length)
        }
        content.addView(input)
        button(content, getString(R.string.note_send)) {
            val text = input.text.toString()
            if (text.isBlank()) {
                input.error = getString(R.string.note_empty)
                return@button
            }
            sent = true
            runtime.addNote(incidentId, text)
            finish()
        }
        button(content, getString(R.string.note_discard)) {
            sent = true
            runtime.saveNoteDraft(incidentId, "")
            finish()
        }
        setContentView(ScrollView(this).apply { addView(content) })
        input.requestFocus()
    }

    override fun onPause() {
        if (::input.isInitialized && !sent) runtime.saveNoteDraft(incidentId, input.text.toString())
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::input.isInitialized) outState.putString("text", input.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun button(content: LinearLayout, label: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
