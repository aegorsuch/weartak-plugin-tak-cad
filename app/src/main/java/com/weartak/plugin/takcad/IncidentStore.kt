// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class Participation { OBSERVED, ASKED, DECLINED, RESPONSE_QUEUED }
data class Responder(val uid: String, val callsign: String, val version: Int, val route: Route, val receivedAt: Long)
data class Incident(
    val ask: CadEvent.Ask,
    val participation: Participation = Participation.ASKED,
    val ownVersion: Int = -1,
    val ownRoute: Route? = null,
    val routeTime: Long = 0,
    val responders: Map<String, Responder> = emptyMap(),
    val serverId: String? = null,
)

class IncidentStore(private val file: File) {
    private val incidents = linkedMapOf<String, Incident>()

    @Synchronized fun snapshot(): List<Incident> = incidents.values.toList()

    @Synchronized fun replaceCanonical(records: List<CanonicalIncident>, serverId: String) {
        require(serverId.isNotBlank()) { "CAD server ID missing" }
        val next = LinkedHashMap(incidents.filterValues { it.ask.canonical == null })
        require(records.map { it.uid }.distinct().size == records.size) { "Duplicate incident UID" }
        for (record in records) {
            require(!next.containsKey(record.uid)) { "Incident UID belongs to a different CAD protocol" }
            next[record.uid] = Incident(record.ask(), Participation.OBSERVED, serverId = serverId)
        }
        require(next.size <= 100) { "Incident storage full" }
        if (next != incidents) commit(next)
    }

    @Synchronized fun receive(event: CadEvent, now: Long): Boolean {
        val next = LinkedHashMap(incidents)
        val changed = when (event) {
            is CadEvent.Ask -> {
                val old = next[event.id]
                require(old != null || next.size < 100) { "Incident storage full; operator maintenance required" }
                require(old == null || (old.ask.canonical == null) == (event.canonical == null)) {
                    "Incident UID belongs to a different CAD protocol"
                }
                require(event.canonical == null || old?.serverId == null) {
                    "Unscoped CoT cannot replace a server-scoped incident; refresh CAD server"
                }
                next[event.id] = if (event.canonical != null) Incident(event, Participation.OBSERVED) else old?.copy(ask = event, participation =
                    if (old.participation == Participation.OBSERVED) Participation.ASKED else old.participation,
                    ownRoute = old.ownRoute.takeIf { old.ask.position == event.position },
                    routeTime = if (old.ask.position == event.position) old.routeTime else 0,
                ) ?: Incident(event)
                next[event.id] != old
            }
            is CadEvent.Response -> {
                val old = next[event.incidentId] ?: event.incident?.let { Incident(it, Participation.OBSERVED) }
                    ?: return false
                require(old.ask.canonical == null) { "Legacy response cannot update a canonical incident" }
                require(next.containsKey(event.incidentId) || next.size < 100) { "Incident storage full" }
                val previous = old.responders[event.uid]
                if (previous != null && previous.version >= event.version) return false
                require(previous != null || old.responders.size < 100) { "Responder storage full" }
                val responder = Responder(event.uid, event.callsign, event.version, event.route, now)
                next[event.incidentId] = old.copy(responders = old.responders + (event.uid to responder))
                true
            }
        }
        if (changed) commit(next)
        return changed
    }

    @Synchronized fun decline(id: String) {
        val old = requireNotNull(incidents[id]) { "Incident not found" }
        require(old.participation == Participation.ASKED) { "Only pending asks can be declined" }
        commit(LinkedHashMap(incidents).apply { put(id, old.copy(participation = Participation.DECLINED)) })
    }

    @Synchronized fun recordDispatch(id: String, version: Int, route: Route, now: Long) {
        val old = requireNotNull(incidents[id]) { "Incident not found" }
        require(old.participation in setOf(Participation.ASKED, Participation.RESPONSE_QUEUED)
            && version == old.ownVersion + 1) { "Response state changed" }
        commit(LinkedHashMap(incidents).apply {
            put(id, old.copy(participation = Participation.RESPONSE_QUEUED, ownVersion = version, ownRoute = route, routeTime = now))
        })
    }

    @Synchronized fun load() {
        if (!file.exists()) return
        require(file.length() <= 20_000_000) { "Incident storage exceeds limit" }
        val bytes = file.readBytes()
        val array = JSONArray(bytes.toString(Charsets.UTF_8))
        require(array.length() <= 100) { "Too many stored incidents" }
        val restored = linkedMapOf<String, Incident>()
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            val ask = CadProtocol.parse(row.getString("ask")) as? CadEvent.Ask ?: error("Invalid stored incident")
            val responders = row.getJSONArray("responders")
            require(responders.length() <= 100)
            val mapped = buildMap {
                for (j in 0 until responders.length()) {
                    val value = responders.getJSONObject(j)
                    val uid = value.getString("uid")
                    put(uid, Responder(uid, value.getString("callsign"), value.getInt("version"),
                        Route.parse(value.getString("route")), value.getLong("time")))
                }
            }
            restored[ask.id] = Incident(ask, Participation.valueOf(row.getString("state")), row.getInt("version"),
                row.optString("route").takeIf { it.isNotBlank() }?.let(Route::parse), row.getLong("time"), mapped,
                row.optString("serverId").takeIf { it.isNotBlank() })
        }
        incidents.clear()
        incidents.putAll(restored)
    }

    private fun commit(next: LinkedHashMap<String, Incident>) {
        val array = JSONArray()
        for (incident in next.values) {
            val ask = incident.ask
            val metadata = ask.canonical?.json ?: JSONObject().put("incident_id", ask.id)
                .put("title", ask.title).put("summary", ask.summary)
                .put("latitude", requireNotNull(ask.position).latitude.toString())
                .put("longitude", ask.position.longitude.toString()).toString()
            val escaped = android.text.TextUtils.htmlEncode(metadata)
            val storedAsk = if (ask.canonical != null) {
                "<event><detail><__takCadIncidentDetails>$escaped</__takCadIncidentDetails></detail></event>"
            } else "<event how=\"${CadProtocol.INCIDENT}\"><detail>$escaped</detail></event>"
            val responders = JSONArray()
            incident.responders.values.forEach {
                responders.put(JSONObject().put("uid", it.uid).put("callsign", it.callsign).put("version", it.version)
                    .put("route", it.route.json).put("time", it.receivedAt))
            }
            array.put(JSONObject().put("ask", storedAsk)
                .put("state", incident.participation.name).put("version", incident.ownVersion)
                .put("route", incident.ownRoute?.json ?: "").put("time", incident.routeTime).put("responders", responders))
            incident.serverId?.let { array.getJSONObject(array.length() - 1).put("serverId", it) }
        }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 20_000_000) { "Incident storage full" }
        val temporary = File("${file.path}.pending")
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        incidents.clear()
        incidents.putAll(next)
    }
}
