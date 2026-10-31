// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.app.Activity
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C

class ResponderActivity : Activity() {
    private val runtime get() = (application as CadApplication).runtime
    private lateinit var content: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var selected: String? = null
    private var step = 0
    private var routeJson: String? = null
    private var configuring = false
    private var pickingServer = false
    private val observer: () -> Unit = { handler.post { if (!configuring) render() } }
    private val tick = object : Runnable {
        override fun run() {
            if (!configuring) render()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getString("selected")
        step = savedInstanceState?.getInt("step") ?: 0
        render()
    }

    override fun onStart() {
        super.onStart()
        runtime.observe(observer)
        handler.post(tick)
    }

    override fun onStop() {
        runtime.unobserve(observer)
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected)
        outState.putInt("step", step)
        super.onSaveInstanceState(outState)
    }

    private fun surface() {
        val oldScroll = if (::content.isInitialized) (content.parent as? ScrollView)?.scrollY ?: 0 else 0
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = if (resources.configuration.isScreenRound) 32 else 20
            setPadding(dp(side), dp(28), dp(side), dp(40))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
        scroll.post { scroll.scrollTo(0, oldScroll) }
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        surface()
        val incidents = runtime.visibleIncidents()
        if (pickingServer && selected == null) {
            for (server in runtime.servers()) {
                val mark = if (server.id == runtime.selectedServer()) "\u2713 " else ""
                button("$mark${server.name}", runtime.isRunning()) {
                    pickingServer = false
                    runtime.selectServer(server.id)
                    render()
                }
            }
            button("Cancel") { pickingServer = false; render() }
            return
        }
        if (selected == null) {
            button("${runtime.selectedServerName() ?: "Select server"} \u25BE", runtime.canSelectServer()) {
                pickingServer = true
                runtime.refreshServers()
                render()
            }
            for (incident in incidents) {
                val state = listOfNotNull(runtime.involvement(incident),
                    incident.ask.canonical?.status ?: stateLabel(incident.participation)).distinct().joinToString(" | ")
                button("${incident.ask.title}\n$state") {
                    selected = incident.ask.id
                    step = 0
                    routeJson = null
                    render()
                }
            }
            button(getString(R.string.configuration)) { setup() }
            return
        }
        val incident = incidents.firstOrNull { it.ask.id == selected }
        if (incident == null) {
            selected = null
            render()
            return
        }
        text(runtime.lastError ?: runtime.message)
        text(incident.detailText(), 20f)
        if (incident.ask.canonical == null) text(stateLabel(incident.participation))
        if (incident.ask.canonical != null && incident.ask.position != null) {
            button("Bloodhound", runtime.canMapAction(C.ACTION_BLOODHOUND)) {
                runtime.mapAction(incident.ask.id, C.ACTION_BLOODHOUND)
            }
            button("Target", runtime.canMapAction(C.ACTION_TARGET)) { runtime.mapAction(incident.ask.id, C.ACTION_TARGET) }
        }
        if (incident.ask.canonical == null && incident.participation == Participation.ASKED) {
            button(getString(R.string.accept)) { runtime.accept(incident.ask.id) }
            button(getString(R.string.decline)) { runtime.decline(incident.ask.id) }
        }
        val route = incident.ownRoute
        if (route != null) {
            if (routeJson != route.json) { step = 0; routeJson = route.json }
            step = step.coerceIn(0, route.steps.lastIndex)
            text(getString(R.string.guidance), 20f)
            text(getString(R.string.estimate, getString(R.string.own_route), ceil(route.seconds / 60).toInt(),
                age(incident.routeTime), if (age(incident.routeTime) > 90) getString(R.string.stale) else ""))
            text("${step + 1}/${route.steps.size}\n${route.steps[step]}", 20f)
            button(getString(R.string.previous), step > 0) { step--; render() }
            button(getString(R.string.next), step < route.steps.lastIndex) { step++; render() }
            text(getString(R.string.route_attribution), 12f)
        } else if (incident.ask.canonical == null) text(getString(R.string.route_unavailable))
        if (incident.participation == Participation.RESPONSE_QUEUED) {
            button(getString(R.string.refresh_route)) { runtime.refresh(incident.ask.id) }
        }
        if (incident.ask.canonical == null) {
            text(getString(R.string.responders), 20f)
            if (incident.responders.isEmpty()) text(getString(R.string.no_responders))
            incident.responders.values.sortedBy { it.route.seconds }.forEach {
                text(getString(R.string.estimate, it.callsign, ceil(it.route.seconds / 60).toInt(),
                    age(it.receivedAt), if (age(it.receivedAt) > 90) getString(R.string.stale) else ""))
            }
        }
    }

    private fun setup() {
        configuring = true
        surface()
        text(getString(R.string.configuration), 22f)
        val uid = input(getString(R.string.responder_uid), runtime.responderUid(), false, 256)
        val vehicle = input(getString(R.string.vehicle_uid), runtime.responderVehicleUid(), false, 256)
        text(getString(if (runtime.keyConfigured()) R.string.key_saved else R.string.key_missing))
        val key = input(getString(R.string.ors_key), "", true, 4096)
        button(getString(R.string.save)) {
            runtime.configure(uid.text.toString().trim(), key.text.toString().trim(), vehicle.text.toString().trim())
            key.text.clear()
            configuring = false
            render()
        }
        button(getString(R.string.clear_key)) {
            runtime.clearKey()
            key.text.clear()
            configuring = false
            render()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (configuring || selected != null) {
            configuring = false
            selected = null
            render()
        } else super.onBackPressed()
    }

    // Runtime provider token, not an account password; SDK has no companion provisioning API.
    @SuppressLint("WearPasswordInput")
    private fun input(label: String, value: String, secret: Boolean, limit: Int): EditText {
        val view = EditText(this).apply {
            hint = label
            contentDescription = label
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(InputFilter.LengthFilter(limit))
            minHeight = dp(48)
            setText(value)
        }
        content.addView(view)
        return view
    }

    private fun text(value: String, size: Float = 16f) {
        content.addView(TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, dp(8), 0, dp(8))
            if (size >= 20f) isAccessibilityHeading = true
        })
    }

    private fun button(label: String, enabled: Boolean = true, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(48)
            isEnabled = enabled
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    private fun age(time: Long): Int = ((System.currentTimeMillis() - time).coerceAtLeast(0) / 1000)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun stateLabel(state: Participation): String = when (state) {
        Participation.OBSERVED -> getString(R.string.state_observed)
        Participation.ASKED -> getString(R.string.state_asked)
        Participation.DECLINED -> getString(R.string.state_declined)
        Participation.RESPONSE_QUEUED -> getString(R.string.state_responding)
    }
}
