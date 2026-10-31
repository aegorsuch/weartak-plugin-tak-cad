// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.os.Bundle
import com.weartak.plugin.sdk.MainUiActionContract
import com.weartak.plugin.sdk.model.MainUiCardParcel
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import kotlin.math.ceil
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C

class HostedResponderUi(
    private val runtime: CadRuntime,
    private val noteLauncher: ((String) -> Unit)? = null,
) {
    private companion object {
        const val SERVER_PAGE = 4
        val STATUS_ACTIONS = CadStatus.VALUES.associateBy { "takcad.status.${it.lowercase()}" }
    }

    private val navigation = ResponderNavigation(runtime.store, runtime::visibleIncidents)
    private var routeTextPage = 0
    private var displayedRoute: String? = null
    private var displayedStep = -1
    private var serverPicker = false
    private var serverOffset = 0
    private var statusPickerFor: String? = null
    private var pendingStatus: String? = null

    @Synchronized fun schema(now: Long = System.currentTimeMillis()): PluginMainUiSchemaParcel {
        val view = navigation.snapshot()
        val cards = mutableListOf<MainUiCardParcel>()
        fun status(id: String, title: String, text: String) {
            cards += MainUiCardParcel(MainUiCardTypes.STATUS, id, boundedText(title, 256), boundedText(text, 256))
        }
        fun action(id: String, label: String, enabled: Boolean = true, incidentId: String? = null, cardId: String = id,
                   serverId: String? = null, cardTitle: String = "\u2022") {
            val args = when {
                incidentId != null -> mapOf("incidentId" to incidentId)
                serverId != null -> mapOf("serverId" to serverId)
                else -> emptyMap()
            }
            val boundedLabel = boundedText(label, 256)
            cards += MainUiCardParcel(MainUiCardTypes.ACTION, cardId, boundedText(cardTitle, 256),
                content = MainUiActionContract.createContent(id, boundedLabel, enabled, args))
        }
        val incident = view.incident
        if (incident?.ask?.id != statusPickerFor) {
            statusPickerFor = null
            pendingStatus = null
        }
        val canonical = incident?.ask?.canonical
        if (incident != null && canonical != null && statusPickerFor != null) {
            val id = incident.ask.id
            val target = pendingStatus
            writeResult()?.let { status("takcad.write", "CAD update", it) }
            if (target == null) {
                status("takcad.status.current", "Change status", "Current: ${CadStatus.label(canonical.status)}")
                STATUS_ACTIONS.forEach { (actionId, value) ->
                    val current = value == canonical.status
                    action(actionId, CadStatus.label(value), runtime.canWrite() && !current, id,
                        cardTitle = if (current) "\u2713" else "\u2022")
                }
            } else {
                status("takcad.status.confirm.prompt", "Confirm status",
                    "Set ${utf8Prefix(incident.ask.title, 160)} to ${CadStatus.label(target)}?")
                action("takcad.status.confirm", "Set ${CadStatus.label(target)}", runtime.canWrite(), id,
                    cardTitle = "\u2713")
            }
            action("takcad.status.cancel", "Cancel", true, id, cardTitle = "\u00D7")
        } else if (serverPicker && incident == null) {
            status("server.header", "Active Server", "")
            val servers = runtime.servers()
            val selected = runtime.selectedServer()
            serverOffset = serverOffset.coerceIn(0, ((servers.size - 1) / SERVER_PAGE).coerceAtLeast(0) * SERVER_PAGE)
            servers.drop(serverOffset).take(SERVER_PAGE).forEachIndexed { index, server ->
                val mark = if (server.id == selected) "\u2713 " else ""
                action("takcad.server.select", "$mark${boundedText(server.name, 220)}", runtime.isRunning(),
                    cardId = if (index == 0) "takcad.server.select" else "takcad.server.select.$index", serverId = server.id)
            }
            if (servers.size > SERVER_PAGE) {
                action("takcad.servers.previous", "Previous", serverOffset > 0, cardTitle = "\u2039")
                action("takcad.servers.next", "Next", serverOffset + SERVER_PAGE < servers.size, cardTitle = "\u203A")
            }
            action("takcad.server.cancel", "Back", cardTitle = "Navigation")
        } else if (incident == null) {
            writeResult()?.let { status("takcad.write", "CAD update", it) }
            action("takcad.server.choose", "${boundedText(runtime.selectedServerName() ?: "Select", 200)} \u25BE",
                runtime.canSelectServer(), cardTitle = "Active Server")
            status("incidents.header", "Active Incident", "")
            view.incidents.drop(view.incidentOffset).take(6).forEachIndexed { index, item ->
                val state = if (item.ask.canonical == null) participation(item.participation) else
                    listOfNotNull(runtime.involvement(item), item.ask.canonical.status).distinct().joinToString(" | ")
                action("takcad.incident.open", item.ask.title,
                    incidentId = item.ask.id,
                    cardId = if (index == 0) "takcad.incident.open" else "takcad.incident.open.$index",
                    cardTitle = state)
            }
            if (view.incidents.size > 6) {
                action("takcad.incidents.previous", "Previous", view.incidentOffset > 0, cardTitle = "\u2039")
                action("takcad.incidents.next", "Next", view.incidentOffset + 6 < view.incidents.size, cardTitle = "\u203A")
            }
        } else {
            val id = incident.ask.id
            if (incident.ask.canonical != null && incident.ask.position != null) {
                action("takcad.bloodhound", "Bloodhound", runtime.canMapAction(C.ACTION_BLOODHOUND), id,
                    cardTitle = "\uD83D\uDC3E")
                action("takcad.target", "Target", runtime.canMapAction(C.ACTION_TARGET), id, cardTitle = "\u2316")
            }
            if (canonical != null) {
                action("takcad.note.add", "Add note", runtime.canWrite() && noteLauncher != null, id,
                    cardTitle = "\u270E")
            }
            incident.detailSections(if (incident.ask.canonical == null) participation(incident.participation) else null)
                .forEachIndexed { index, (title, text) ->
                    status("incident.detail.$index", title, boundedText(text, 256))
                }
            if (incident.ask.canonical == null) when (incident.participation) {
                Participation.ASKED -> {
                    action("takcad.incident.accept", "Accept", runtime.isRunning(), id, cardTitle = "\u2713")
                    action("takcad.incident.decline", "Decline", true, id, cardTitle = "\u00D7")
                }
                Participation.RESPONSE_QUEUED -> action("takcad.refresh", "Refresh route", runtime.isRunning(), id,
                    cardTitle = "Route")
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
                status("route", "Guidance",
                    "${eta("You", route, incident.routeTime, now)}\n${instruction[routeTextPage]}")
                action("takcad.route.previous", "Previous instruction", view.instructionIndex > 0, id, cardTitle = "\u2039")
                action("takcad.route.next", "Next instruction", view.instructionIndex < route.steps.lastIndex, id, cardTitle = "\u203A")
                if (instruction.size > 1) {
                    action("takcad.route.text.previous", "Previous text", routeTextPage > 0, id, cardTitle = "\u2039")
                    action("takcad.route.text.next", "Next text", routeTextPage < instruction.lastIndex, id, cardTitle = "\u203A")
                }
                status("attribution", "Map source", "openrouteservice.org · OpenStreetMap contributors")
            }
            val responders = incident.responders.values.sortedWith(compareBy<Responder> { it.route.seconds }.thenBy { it.uid })
            if (incident.ask.canonical == null) {
                val etas = responders.map { eta(it.callsign, it.route, it.receivedAt, now) }
                if (etas.isNotEmpty()) status("responders", "Responder ETAs", boundedText(etas.distinct().joinToString("\n"), 256))
            }
            if (canonical != null) {
                // Keep one slot for Back; drop the optional result card first when the detail page is full.
                val room = 15 - cards.size
                val result = writeResult()
                if (result != null && room >= 3) status("takcad.write", "CAD update", result)
                if (room >= 1) action("takcad.status.choose", "${CadStatus.label(canonical.status)} \u25BE",
                    runtime.canWrite(), id, cardTitle = "Status")
            }
            action("takcad.back", "All incidents", cardTitle = "Navigation")
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
        val allowedArg = if (id == "takcad.server.select") "serverId" else "incidentId"
        require(args == null || args.keySet().all { it == allowedArg }) { "Unexpected hosted action arguments" }
        fun selectedId(): String {
            val selected = requireNotNull(navigation.snapshot().incident) { "Select incident first" }.ask.id
            require(args?.getString("incidentId") == selected) { "Action does not match selected incident" }
            return selected
        }
        when (id) {
            "takcad.incident.open" -> {
                navigation.select(requireNotNull(args?.getString("incidentId")) { "Missing incidentId" })
                runtime.clearWriteResult()
                routeTextPage = 0
            }
            "takcad.back" -> {
                navigation.back()
                runtime.clearWriteResult()
            }
            "takcad.note.add" -> {
                val incidentId = selectedId()
                requireNotNull(navigation.snapshot().incident?.ask?.canonical) { "Notes require a CAD incident" }
                check(runtime.canWrite()) { "CAD update unavailable" }
                requireNotNull(noteLauncher) { "Note entry unavailable" }(incidentId)
            }
            "takcad.status.choose" -> {
                val incidentId = selectedId()
                requireNotNull(navigation.snapshot().incident?.ask?.canonical) { "Status requires a CAD incident" }
                statusPickerFor = incidentId
                pendingStatus = null
            }
            in STATUS_ACTIONS.keys -> {
                require(statusPickerFor == selectedId()) { "Open status list first" }
                pendingStatus = STATUS_ACTIONS.getValue(id)
            }
            "takcad.status.confirm" -> {
                val incidentId = selectedId()
                require(statusPickerFor == incidentId) { "Open status list first" }
                val target = requireNotNull(pendingStatus) { "Choose a status first" }
                statusPickerFor = null
                pendingStatus = null
                runtime.setStatus(incidentId, target)
            }
            "takcad.status.cancel" -> {
                selectedId()
                if (pendingStatus != null) pendingStatus = null else statusPickerFor = null
            }
            "takcad.incidents.previous" -> navigation.pageIncidents(-6)
            "takcad.incidents.next" -> navigation.pageIncidents(6)
            "takcad.server.choose" -> {
                require(navigation.snapshot().incident == null) { "Return to incidents first" }
                serverPicker = true
                serverOffset = 0
                runtime.refreshServers()
            }
            "takcad.server.select" -> {
                require(serverPicker) { "Open CAD server list first" }
                val serverId = requireNotNull(args?.getString("serverId")) { "Missing serverId" }
                require(runtime.servers().any { it.id == serverId }) { "CAD server no longer listed" }
                serverPicker = false
                runtime.selectServer(serverId)
            }
            "takcad.server.cancel" -> serverPicker = false
            "takcad.servers.previous", "takcad.servers.next" -> {
                require(serverPicker) { "Open CAD server list first" }
                val next = serverOffset + if (id.endsWith(".next")) SERVER_PAGE else -SERVER_PAGE
                require(next >= 0 && next < runtime.servers().size) { "Server page unavailable" }
                serverOffset = next
            }
            "takcad.bloodhound" -> runtime.mapAction(selectedId(), C.ACTION_BLOODHOUND)
            "takcad.target" -> runtime.mapAction(selectedId(), C.ACTION_TARGET)
            "takcad.incident.accept" -> runtime.accept(selectedId())
            "takcad.incident.decline" -> runtime.decline(selectedId())
            "takcad.refresh" -> runtime.refresh(selectedId())
            "takcad.route.previous", "takcad.route.next" -> {
                selectedId()
                navigation.step(if (id.endsWith(".next")) 1 else -1)
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

    private fun writeResult(): String? =
        if (runtime.isWriting()) "Sending to CAD\u2026" else runtime.writeError ?: runtime.writeNotice

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
    private fun boundedText(text: String, limit: Int): String {
        val prefix = utf8Prefix(text, limit)
        return if (prefix.length == text.length) prefix else "${utf8Prefix(text, limit - 3)}…"
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
