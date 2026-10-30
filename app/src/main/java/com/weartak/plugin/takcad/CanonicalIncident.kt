// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import org.json.JSONArray
import org.json.JSONObject

data class CadVehicleResponse(
    val uid: String,
    val callsign: String,
    val name: String,
    val status: String,
    val etaSeconds: Long?,
    val currentIncidentStatus: String?,
)

data class CadNote(val uid: String, val timestamp: String, val creator: String, val info: String)
data class CadPersonResponse(val uid: String, val arrivalTime: String)

data class CanonicalIncident(
    val json: String,
    val uid: String,
    val name: String,
    val status: String,
    val details: String,
    val position: Position?,
    val address: String,
    val type: String,
    val requestedCallsigns: List<String>,
    val requestedVehicleUids: List<String>,
    val responses: List<CadVehicleResponse>,
    val notes: List<CadNote>,
    val personnel: List<CadPersonResponse>,
) {
    fun involvement(callsign: String, vehicleUid: String): String? {
        fun matches(value: CadVehicleResponse) =
            (callsign.isNotBlank() && value.callsign == callsign) ||
                (vehicleUid.isNotBlank() && value.uid == vehicleUid)
        if (responses.any { matches(it) && it.currentIncidentStatus in setOf("IN_ROUTE", "ON_SCENE") }) return "Responding"
        if ((callsign.isNotBlank() && callsign in requestedCallsigns) ||
            (vehicleUid.isNotBlank() && vehicleUid in requestedVehicleUids)) return "Requested"
        return null
    }

    fun ask() = CadEvent.Ask(uid, name, details, position, this)

    companion object {
        fun parse(json: String): CanonicalIncident {
            require(json.length <= CadProtocol.MAX_XML / 2) { "Incident exceeds transport limit" }
            val root = JSONObject(json)
            val uid = root.text("uid", 256).also { require(it.isNotBlank()) { "Incident UID missing" } }
            val status = root.text("status", 32)
            require(status in setOf("ACTIVE", "CANCELLED", "COMPLETED")) { "Invalid incident status" }
            val coords = root.optJSONObject("location")?.optJSONObject("coords")
            val address = root.optJSONObject("location")?.optJSONObject("address")?.let { value ->
                listOf("streetName", "city", "state", "zipCode", "country").map { value.text(it, 512) }
                    .filter { it.isNotBlank() }.joinToString(", ")
            }.orEmpty()
            val position = coords?.takeIf { !it.isNull("latitudeDeg") && !it.isNull("longitudeDeg") }?.let {
                Position(it.getDouble("latitudeDeg"), it.getDouble("longitudeDeg"))
            }
            val responses = root.array("vehiclesResponding").objects().map { response ->
                val vehicle = response.getJSONObject("vehicle")
                val current = vehicle.array("currentVehicleResponses").objects()
                    .firstOrNull { it.text("incidentUid", 256) == uid }
                val eta = if (response.isNull("eta")) null else response.getLong("eta").also {
                    require(it >= 0) { "Invalid responder ETA" }
                }
                CadVehicleResponse(
                    vehicle.text("uid", 256), vehicle.text("callsign", 256), vehicle.text("name", 512),
                    current?.text("responseStatus", 32) ?: vehicle.text("status", 32), eta,
                    current?.text("responseStatus", 32),
                )
            }
            val notes = root.array("notes").objects().map {
                CadNote(it.text("uid", 256), it.text("timestamp", 128), it.text("creator", 256), it.text("info", 8192))
            }.sortedByDescending {
                if (it.timestamp.isBlank()) java.time.Instant.MIN else try {
                    java.time.OffsetDateTime.parse(it.timestamp).toInstant()
                } catch (e: java.time.format.DateTimeParseException) {
                    throw IllegalArgumentException("Invalid note timestamp", e)
                }
            }
            return CanonicalIncident(
                json, uid, root.text("incidentName", 512).ifBlank { uid }, status, root.text("details", 8192),
                position, address, root.optJSONObject("incidentType")?.text("name", 512).orEmpty(),
                root.array("requestedCallsigns").strings(), root.array("vehicleUidsRequested").strings(), responses, notes,
                root.array("personnelResponding").objects().map {
                    CadPersonResponse(it.text("uid", 256), it.text("arrivalTime", 128))
                },
            )
        }

        fun parseList(json: String): List<CanonicalIncident> {
            require(json.length <= 20_000_000) { "Incident list exceeds storage limit" }
            return JSONArray(json).objects().map { parse(it.toString()) }.also { list ->
                require(list.map { it.uid }.distinct().size == list.size) { "Duplicate incident UID" }
            }
        }
    }
}

fun Incident.detailText(): String = buildList {
    add(ask.title)
    val canonical = ask.canonical
    if (canonical != null) {
        add(canonical.status)
        if (canonical.type.isNotBlank()) add(canonical.type)
    }
    if (ask.summary.isNotBlank()) add(ask.summary)
    if (!canonical?.address.isNullOrBlank()) add(requireNotNull(canonical).address)
    ask.position?.let { add("${it.latitude}, ${it.longitude}") }
    if (canonical != null) {
        if (canonical.requestedCallsigns.isNotEmpty()) add("Requested: ${canonical.requestedCallsigns.joinToString()}")
        if (canonical.requestedVehicleUids.isNotEmpty()) add("Requested vehicles: ${canonical.requestedVehicleUids.joinToString()}")
        if (canonical.personnel.isNotEmpty()) add("Responding personnel")
        for (person in canonical.personnel) {
            add(listOf(person.uid, person.arrivalTime).filter { it.isNotBlank() }.joinToString(" | "))
        }
        if (canonical.responses.isNotEmpty()) add("Responding vehicles")
        for (response in canonical.responses) {
            add(listOf(response.callsign, response.name, response.uid, response.status,
                response.etaSeconds?.let { "${it / 60} min" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" | "))
        }
        if (canonical.notes.isNotEmpty()) add("Notes")
        for (note in canonical.notes) {
            add(listOf(note.timestamp, note.creator, note.info).filter { it.isNotBlank() }.joinToString("\n"))
        }
    }
}.joinToString("\n")

private fun JSONObject.text(key: String, limit: Int): String =
    (if (isNull(key)) "" else getString(key)).also { require(it.length <= limit) { "$key exceeds limit" } }

private fun JSONObject.array(key: String): JSONArray = if (isNull(key)) JSONArray() else getJSONArray(key)
private fun JSONArray.objects(): List<JSONObject> {
    require(length() <= 100) { "Too many incident records" }
    return (0 until length()).map { getJSONObject(it) }
}
private fun JSONArray.strings(): List<String> {
    require(length() <= 100) { "Too many requested responders" }
    return (0 until length()).map { getString(it).also { value -> require(value.length <= 256) } }
}
