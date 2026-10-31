// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.app.Activity
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
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
    private var serverOffset = 0
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

    override fun onResume() {
        super.onResume()
        runtime.refreshMapState()
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

    private fun surface(resetScroll: Boolean = false) {
        val oldScroll = if (!resetScroll && ::content.isInitialized) (content.parent as? ScrollView)?.scrollY ?: 0 else 0
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = if (resources.configuration.isScreenRound) 32 else 20
            setPadding(dp(side), dp(28), dp(side), dp(40))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
        scroll.post { scroll.scrollTo(0, oldScroll) }
    }

    private fun render(resetScroll: Boolean = false) {
        if (isFinishing || isDestroyed) return
        surface(resetScroll)
        val incidents = runtime.visibleIncidents()
        if (pickingServer && selected == null) {
            backArrow { pickingServer = false; render(resetScroll = true) }
            text("Active Server", 20f)
            val servers = runtime.servers()
            serverOffset = serverOffset.coerceIn(0, ((servers.size - 1) / SERVER_PAGE).coerceAtLeast(0) * SERVER_PAGE)
            for (server in servers.drop(serverOffset).take(SERVER_PAGE)) {
                val mark = if (server.id == runtime.selectedServer()) "\u2713 " else ""
                button("$mark${server.name}", runtime.isRunning()) {
                    pickingServer = false
                    runtime.selectServer(server.id)
                    render(resetScroll = true)
                }
            }
            if (servers.size > SERVER_PAGE) {
                button("Previous", serverOffset > 0) { serverOffset -= SERVER_PAGE; render() }
                button("Next", serverOffset + SERVER_PAGE < servers.size) { serverOffset += SERVER_PAGE; render() }
            }
            return
        }
        if (selected == null) {
            writeResult()?.let { text(it) }
            text("Active Server", 20f)
            button("${runtime.selectedServerName() ?: "Select"} \u25BE", runtime.canSelectServer()) {
                pickingServer = true
                serverOffset = 0
                runtime.refreshServers()
                render(resetScroll = true)
            }
            text("Active Incident", 20f)
            for (incident in incidents) {
                val state = if (incident.ask.canonical == null) stateLabel(incident.participation) else
                    runtime.involvement(incident)
                button(listOfNotNull(incident.ask.title, state).joinToString("\n")) {
                    selected = incident.ask.id
                    runtime.clearWriteResult()
                    step = 0
                    routeJson = null
                    render(resetScroll = true)
                }
            }
            button(getString(R.string.configuration)) { setup() }
            return
        }
        val incident = incidents.firstOrNull { it.ask.id == selected }
        if (incident == null) {
            selected = null
            render(resetScroll = true)
            return
        }
        backArrow {
            selected = null
            runtime.clearWriteResult()
            render(resetScroll = true)
        }
        if (incident.ask.canonical != null) {
            val hasPosition = incident.ask.position != null
            val stopping = runtime.isBloodhounding(incident.ask.id)
            actionRow(
                bloodhound = { runtime.toggleBloodhound(incident.ask.id) },
                target = { runtime.mapAction(incident.ask.id, C.ACTION_TARGET) },
                note = { startActivity(NoteActivity.intent(this, incident.ask.id)) },
                bloodhoundEnabled = stopping || (hasPosition && runtime.canMapAction(C.ACTION_BLOODHOUND)),
                targetEnabled = hasPosition && runtime.canMapAction(C.ACTION_TARGET),
                noteEnabled = runtime.canWrite(),
                stopping = stopping,
            )
        }
        incident.detailSections(if (incident.ask.canonical == null) stateLabel(incident.participation) else null)
            .forEach { (heading, value) ->
                text(heading, 20f, bold = true)
                text(value)
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
        }
        if (incident.participation == Participation.RESPONSE_QUEUED) {
            button(getString(R.string.refresh_route)) { runtime.refresh(incident.ask.id) }
        }
        if (incident.ask.canonical == null) {
            val responders = incident.responders.values.sortedBy { it.route.seconds }
            if (responders.isNotEmpty()) {
                text(getString(R.string.responders), 20f)
                responders.forEach {
                    text(getString(R.string.estimate, it.callsign, ceil(it.route.seconds / 60).toInt(),
                        age(it.receivedAt), if (age(it.receivedAt) > 90) getString(R.string.stale) else ""))
                }
            }
        }
    }

    private fun writeResult(): String? =
        if (runtime.isWriting()) "Sending to CAD\u2026" else runtime.writeError ?: runtime.writeNotice

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
            render(resetScroll = true)
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

    private fun text(value: String, size: Float = 16f, bold: Boolean = false) {
        content.addView(TextView(this).apply {
            text = value
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
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

    private fun actionRow(
        bloodhound: () -> Unit,
        target: () -> Unit,
        note: () -> Unit,
        bloodhoundEnabled: Boolean,
        targetEnabled: Boolean,
        noteEnabled: Boolean,
        stopping: Boolean,
    ) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            val actionSize = dp(48)
            fun add(icon: Int, description: String, enabled: Boolean, action: () -> Unit) = addView(
                mapAction(icon, description, enabled, action),
                LinearLayout.LayoutParams(actionSize, actionSize).apply {
                    if (childCount > 0) marginStart = dp(4)
                })
            add(R.drawable.ic_note, getString(R.string.add_note), noteEnabled, note)
            add(if (stopping) R.drawable.ic_stop else R.drawable.bloodhound,
                getString(if (stopping) R.string.stop_bloodhound else R.string.bloodhound), bloodhoundEnabled, bloodhound)
            add(R.drawable.ic_target, getString(R.string.focus_incident), targetEnabled, target)
        })
    }

    private fun backArrow(action: () -> Unit) {
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            addView(mapAction(R.drawable.ic_back, getString(R.string.back), true, action),
                LinearLayout.LayoutParams(dp(48), dp(48)))
        })
    }

    private fun mapAction(icon: Int, description: String, enabled: Boolean, action: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = description
            isEnabled = enabled
            val states = arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf())
            imageTintList = ColorStateList(states, intArrayOf(Color.WHITE, 0x61FFFFFF))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    val color = if (icon == R.drawable.ic_stop) 0xFFB3261E.toInt() else Color.DKGRAY
                    setColor(ColorStateList(states, intArrayOf(color, 0xFF131313.toInt())))
                }, null)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener { action() }
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

    private companion object { const val SERVER_PAGE = 4 }
}
