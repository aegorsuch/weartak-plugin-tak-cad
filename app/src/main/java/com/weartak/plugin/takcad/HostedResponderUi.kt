// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.os.Bundle
import com.weartak.plugin.sdk.MainUiActionContract
import com.weartak.plugin.sdk.model.MainUiCardParcel
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import kotlin.math.ceil

class HostedResponderUi(private val runtime: CadRuntime) {
    private val navigation = ResponderNavigation(runtime.store)
    private var detailPage = 0
    private var routeTextPage = 0
    private var displayedRoute: String? = null
    private var displayedStep = -1

    @Synchronized fun schema(now: Long = System.currentTimeMillis()): PluginMainUiSchemaParcel {
        val view = navigation.snapshot()
        val cards = mutableListOf<MainUiCardParcel>()
        fun status(id: String, title: String, text: String) {
            cards += MainUiCardParcel(MainUiCardTypes.STATUS, id, utf8Prefix(title, 256), utf8Prefix(text, 256))
        }
        fun action(id: String, label: String, enabled: Boolean = true, incidentId: String? = null) {
            val args = if (incidentId != null) mapOf("incidentId" to incidentId) else emptyMap()
            cards += MainUiCardParcel(MainUiCardTypes.ACTION, id, label,
                content = MainUiActionContract.createContent(id, label, enabled, args))
        }
        status("status", "TAK-CAD", runtime.lastError ?: runtime.message)
        val incident = view.incident
        if (incident == null) {
            if (view.incidents.isEmpty()) {
                status("empty", "No incident asks", "Incoming CoT forwarding requires WearTAK host integration.")
            } else {
                val item = view.incidents[view.incidentOffset]
                status("incident", "Incident ${view.incidentOffset + 1}/${view.incidents.size}",
                    "${item.ask.title}\n${participation(item.participation)}")
                action("takcad.incident.open", "Open incident", incidentId = item.ask.id)
                action("takcad.incidents.previous", "Previous incident", view.incidentOffset > 0)
                action("takcad.incidents.next", "Next incident", view.incidentOffset < view.incidents.lastIndex)
            }
        } else {
            val id = incident.ask.id
            val details = pages("${incident.ask.title}\n${participation(incident.participation)}\n" +
                "${incident.ask.position.latitude}, ${incident.ask.position.longitude}\n${incident.ask.summary}")
            detailPage = detailPage.coerceIn(0, details.lastIndex)
            status("details", "Incident details ${detailPage + 1}/${details.size}", details[detailPage])
            action("takcad.details.previous", "Previous details", detailPage > 0, id)
            action("takcad.details.next", "More details", detailPage < details.lastIndex, id)
            when (incident.participation) {
                Participation.ASKED -> {
                    action("takcad.incident.accept", "Accept ask", runtime.isRunning(), id)
                    action("takcad.incident.decline", "Decline locally", true, id)
                }
                Participation.RESPONSE_QUEUED -> action("takcad.refresh", "Refresh route", runtime.isRunning(), id)
                else -> Unit
            }
            incident.ownRoute?.let { route ->
                if (displayedRoute != route.json || displayedStep != view.instructionIndex) {
                    routeTextPage = 0
                    displayedRoute = route.json
                    displayedStep = view.instructionIndex
                }
                val instruction = pages(route.steps[view.instructionIndex])
                routeTextPage = routeTextPage.coerceIn(0, instruction.lastIndex)
                status("route", "Step ${view.instructionIndex + 1}/${route.steps.size}, text ${routeTextPage + 1}/${instruction.size}",
                    "${eta("You", route, incident.routeTime, now)}\n${instruction[routeTextPage]}")
                action("takcad.route.previous", "Previous instruction", view.instructionIndex > 0, id)
                action("takcad.route.next", "Next instruction", view.instructionIndex < route.steps.lastIndex, id)
                if (instruction.size > 1) {
                    action("takcad.route.text.previous", "Previous instruction text", routeTextPage > 0, id)
                    action("takcad.route.text.next", "More instruction text", routeTextPage < instruction.lastIndex, id)
                }
                status("attribution", "Advisory route guidance",
                    "Follow signs and local conditions. Routing: openrouteservice.org by HeiGIT. Map data: OpenStreetMap contributors.")
            }
            val responders = incident.responders.values.sortedWith(compareBy<Responder> { it.route.seconds }.thenBy { it.uid })
            if (responders.isEmpty()) {
                status("responders", "Responder ETAs", "No responder routes received.")
            } else {
                val responder = responders[view.responderOffset]
                status("responders", "Responder ${view.responderOffset + 1}/${responders.size}",
                    eta(utf8Prefix(responder.callsign, 96), responder.route, responder.receivedAt, now))
                action("takcad.responders.previous", "Previous responder", view.responderOffset > 0, id)
                action("takcad.responders.next", "Next responder", view.responderOffset < responders.lastIndex, id)
            }
            action("takcad.back", "All incidents")
        }
        status("limits", "Routing and protocol limits",
            "Manual guidance; delivery unconfirmed. Tasks/notes/history unavailable. " +
                "Routing setup requires development fallback until secure hosted input exists. " +
                if (runtime.keyConfigured()) "Routing key configured." else "Routing key not configured.")
        check(cards.size <= 16) { "Hosted UI exceeds SDK card limit" }
        return PluginMainUiSchemaParcel(cards = cards)
    }

    fun invoke(actionId: String?, args: Bundle?) {
        invokeLocked(actionId, args)
        runtime.notifyHostedNavigation()
    }

    @Synchronized private fun invokeLocked(actionId: String?, args: Bundle?) {
        val id = requireNotNull(actionId) { "Missing hosted action" }
        require(args == null || args.keySet().all { it == "incidentId" }) { "Unexpected hosted action arguments" }
        fun selectedId(): String {
            val selected = requireNotNull(navigation.snapshot().incident) { "Select incident first" }.ask.id
            require(args?.getString("incidentId") == selected) { "Action does not match selected incident" }
            return selected
        }
        when (id) {
            "takcad.incident.open" -> {
                navigation.select(requireNotNull(args?.getString("incidentId")) { "Missing incidentId" })
                detailPage = 0
                routeTextPage = 0
            }
            "takcad.back" -> navigation.back()
            "takcad.incidents.previous" -> navigation.pageIncidents(-1)
            "takcad.incidents.next" -> navigation.pageIncidents(1)
            "takcad.incident.accept" -> runtime.accept(selectedId())
            "takcad.incident.decline" -> runtime.decline(selectedId())
            "takcad.refresh" -> runtime.refresh(selectedId())
            "takcad.route.previous", "takcad.route.next" -> {
                selectedId()
                navigation.step(if (id.endsWith(".next")) 1 else -1)
            }
            "takcad.responders.previous", "takcad.responders.next" -> {
                selectedId()
                navigation.pageResponders(if (id.endsWith(".next")) 1 else -1)
            }
            "takcad.details.previous", "takcad.details.next" -> {
                selectedId()
                val incident = requireNotNull(navigation.snapshot().incident)
                val details = pages("${incident.ask.title}\n${participation(incident.participation)}\n" +
                    "${incident.ask.position.latitude}, ${incident.ask.position.longitude}\n${incident.ask.summary}")
                val next = detailPage + if (id.endsWith(".next")) 1 else -1
                require(next in details.indices) { "Details page unavailable" }
                detailPage = next
            }
            "takcad.route.text.previous", "takcad.route.text.next" -> {
                selectedId()
                val view = navigation.snapshot()
                val route = requireNotNull(view.incident?.ownRoute) { "No route instructions available" }
                require(displayedRoute == route.json && displayedStep == view.instructionIndex) { "Route changed; reload page" }
                val next = routeTextPage + if (id.endsWith(".next")) 1 else -1
                require(next in pages(route.steps[view.instructionIndex]).indices) { "Instruction text page unavailable" }
                routeTextPage = next
            }
            else -> throw IllegalArgumentException("Unsupported TAK-CAD action: $id")
        }
    }

    private fun pages(text: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val piece = utf8Prefix(text.substring(index), 140)
            result += piece
            index += piece.length
        }
        return result.ifEmpty { listOf("") }
    }

    private fun utf8Prefix(text: String, limit: Int): String {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val count = Character.charCount(text.codePointAt(index))
            val next = text.substring(index, index + count).toByteArray(Charsets.UTF_8).size
            if (bytes + next > limit) break
            bytes += next
            index += count
        }
        return text.substring(0, index)
    }
    private fun eta(name: String, route: Route, time: Long, now: Long): String {
        val age = ((now - time).coerceAtLeast(0) / 1000)
        return "$name: ${ceil(route.seconds / 60).toLong()} min estimate, ${age}s old${if (age > 90) " - STALE" else ""}"
    }
    private fun participation(value: Participation): String = when (value) {
        Participation.OBSERVED -> "Observed - no ask"
        Participation.ASKED -> "Asked - acceptance required"
        Participation.DECLINED -> "Declined locally"
        Participation.RESPONSE_QUEUED -> "Responding - delivery unconfirmed"
    }
}
