// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.os.Bundle
import com.weartak.plugin.sdk.MainUiActionContract
import com.weartak.plugin.sdk.model.MainUiCardParcel
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import kotlin.math.ceil
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C

class HostedResponderUi(private val runtime: CadRuntime) {
    private val navigation = ResponderNavigation(runtime.store, runtime::visibleIncidents)
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
        fun action(id: String, label: String, enabled: Boolean = true, incidentId: String? = null, cardId: String = id) {
            val args = if (incidentId != null) mapOf("incidentId" to incidentId) else emptyMap()
            val boundedLabel = utf8Prefix(label, 256)
            cards += MainUiCardParcel(MainUiCardTypes.ACTION, cardId, boundedLabel,
                content = MainUiActionContract.createContent(id, boundedLabel, enabled, args))
        }
        status("status", "TAK CAD", runtime.lastError ?: runtime.message)
        val incident = view.incident
        if (incident == null) {
            status("server", runtime.selectedServerName() ?: "CAD server", runtime.lastError ?: runtime.cadStatus)
            action("takcad.incidents.refresh", "Refresh incidents", runtime.isRunning() && runtime.supportsCad())
            runtime.nextServerName()?.let {
                action("takcad.server.next", "Server: ${utf8Prefix(it, 220)}", runtime.canSelectServer())
            }
            if (view.incidents.isEmpty()) {
                status("empty", "No incidents", "")
            } else {
                view.incidents.drop(view.incidentOffset).take(6).forEachIndexed { index, item ->
                    val state = listOfNotNull(runtime.involvement(item), item.ask.canonical?.status
                        ?: participation(item.participation)).distinct().joinToString(" | ")
                    action("takcad.incident.open", "${utf8Prefix(item.ask.title, 180)}\n$state", incidentId = item.ask.id,
                        cardId = if (index == 0) "takcad.incident.open" else "takcad.incident.open.$index")
                }
                action("takcad.incidents.previous", "Previous incident", view.incidentOffset > 0)
                action("takcad.incidents.next", "Next incidents", view.incidentOffset + 6 < view.incidents.size)
            }
        } else {
            val id = incident.ask.id
            val details = pages(incident.detailText())
            detailPage = detailPage.coerceIn(0, details.lastIndex)
            status("details", "Incident details ${detailPage + 1}/${details.size}", details[detailPage])
            action("takcad.details.previous", "Previous details", detailPage > 0, id)
            action("takcad.details.next", "More details", detailPage < details.lastIndex, id)
            if (incident.ask.canonical != null && incident.ask.position != null) {
                action("takcad.bloodhound", "Bloodhound", runtime.canMapAction(C.ACTION_BLOODHOUND), id)
                action("takcad.target", "Target", runtime.canMapAction(C.ACTION_TARGET), id)
            }
            if (incident.ask.canonical == null) when (incident.participation) {
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
                status("attribution", "Routing: openrouteservice.org by HeiGIT", "Map data: OpenStreetMap contributors")
            }
            val responders = incident.responders.values.sortedWith(compareBy<Responder> { it.route.seconds }.thenBy { it.uid })
            if (incident.ask.canonical == null) {
                if (responders.isEmpty()) {
                    status("responders", "Responder ETAs", "No responder routes received.")
                } else {
                    val responder = responders[view.responderOffset]
                    status("responders", "Responder ${view.responderOffset + 1}/${responders.size}",
                        eta(utf8Prefix(responder.callsign, 96), responder.route, responder.receivedAt, now))
                    action("takcad.responders.previous", "Previous responder", view.responderOffset > 0, id)
                    action("takcad.responders.next", "Next responder", view.responderOffset < responders.lastIndex, id)
                }
            }
            action("takcad.back", "All incidents")
        }
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
            "takcad.incidents.previous" -> navigation.pageIncidents(-6)
            "takcad.incidents.next" -> navigation.pageIncidents(6)
            "takcad.incidents.refresh" -> runtime.refreshIncidents()
            "takcad.server.next" -> runtime.nextServer()
            "takcad.bloodhound" -> runtime.mapAction(selectedId(), C.ACTION_BLOODHOUND)
            "takcad.target" -> runtime.mapAction(selectedId(), C.ACTION_TARGET)
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
                val details = pages(incident.detailText())
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
