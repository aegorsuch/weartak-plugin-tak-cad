// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

/** Navigation stays plugin-owned; host supplies rendering and authenticated action delivery. */
class ResponderNavigation(
    private val store: IncidentStore,
    private val list: () -> List<Incident> = store::snapshot,
    private val pageableIncidents: (List<Incident>) -> List<Incident> = { it },
    private val incidentPageSize: Int = 1,
) {
    init { require(incidentPageSize > 0) { "Incident page size must be positive" } }

    private var selectedId: String? = null
    private var incidentOffset = 0
    private var responderOffset = 0
    private var instructionIndex = 0
    private var routeJson: String? = null

    data class Snapshot(
        val incident: Incident?,
        val incidentOffset: Int,
        val responderOffset: Int,
        val instructionIndex: Int,
        val incidents: List<Incident>,
    )

    @Synchronized fun snapshot(): Snapshot {
        val incidents = list()
        val incident = incidents.firstOrNull { it.ask.id == selectedId }
        if (incident == null) selectedId = null
        val pageCount = pageableIncidents(incidents).size
        val lastPageOffset = if (pageCount == 0) 0 else ((pageCount - 1) / incidentPageSize) * incidentPageSize
        incidentOffset = incidentOffset.coerceIn(0, lastPageOffset)
        responderOffset = responderOffset.coerceIn(0, ((incident?.responders?.size ?: 0) - 1).coerceAtLeast(0))
        if (routeJson != incident?.ownRoute?.json) {
            routeJson = incident?.ownRoute?.json
            instructionIndex = 0
        }
        instructionIndex = instructionIndex.coerceIn(0, ((incident?.ownRoute?.steps?.size ?: 0) - 1).coerceAtLeast(0))
        return Snapshot(incident, incidentOffset, responderOffset, instructionIndex, incidents)
    }

    @Synchronized fun select(id: String) {
        require(list().any { it.ask.id == id }) { "Incident not found" }
        selectedId = id
        responderOffset = 0
        instructionIndex = 0
        routeJson = null
    }

    @Synchronized fun back() {
        selectedId = null
        responderOffset = 0
        instructionIndex = 0
        routeJson = null
    }

    @Synchronized fun pageIncidents(delta: Int) {
        val current = snapshot()
        val next = current.incidentOffset.toLong() + delta
        require(next in 0 until pageableIncidents(current.incidents).size.toLong()) { "Incident page unavailable" }
        incidentOffset = next.toInt()
    }

    @Synchronized fun pageResponders(delta: Int) {
        val current = snapshot()
        val incident = requireNotNull(current.incident) { "Select incident first" }
        val next = current.responderOffset.toLong() + delta
        require(next in 0 until incident.responders.size.toLong()) { "Responder page unavailable" }
        responderOffset = next.toInt()
    }

    @Synchronized fun step(delta: Int) {
        val current = snapshot()
        val route = requireNotNull(current.incident?.ownRoute) { "No route instructions available" }
        val next = current.instructionIndex.toLong() + delta
        require(next in 0 until route.steps.size.toLong()) { "Instruction unavailable" }
        instructionIndex = next.toInt()
    }
}
