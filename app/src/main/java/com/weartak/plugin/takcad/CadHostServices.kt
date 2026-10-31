// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.os.Bundle
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import com.weartak.plugin.sdk.host.v1.IWearTakHostServices
import org.json.JSONException
import org.json.JSONObject

data class CadServer(val id: String, val name: String)
data class CadHostCapabilities(val servers: List<CadServer>, val mapActions: Set<String>, val takHttps: Boolean)
data class CadNoteDraft(val incidentUid: String, val timestamp: String, val creator: String, val info: String)

object CadStatus {
    const val ACTIVE = "ACTIVE"
    const val CANCELLED = "CANCELLED"
    const val COMPLETED = "COMPLETED"
    val VALUES = listOf(ACTIVE, CANCELLED, COMPLETED)
    const val MAX_NOTE_CHARS = 500

    fun label(status: String): String = status.lowercase().replaceFirstChar { it.uppercase() }
}

/** Synchronous adapter; callers run exclusively on the responder worker. */
class CadHostServices(private val services: IWearTakHostServices) {
    private companion object {
        const val SUBMIT = "/Marti/api/plugins/tak.server.plugins.TakCadServerPlugin/submit"
    }

    fun capabilities(): CadHostCapabilities {
        val result = checked(services.capabilities)
        check(result.getInt(C.KEY_VERSION) == C.VERSION) { "Unsupported host services version" }
        @Suppress("DEPRECATION")
        val servers = result.getParcelableArrayList<Bundle>(C.KEY_SERVERS).orEmpty().map {
            CadServer(requireNotNull(it.getString(C.KEY_SERVER_ID)) { "Host server ID missing" },
                requireNotNull(it.getString(C.KEY_SERVER_NAME)) { "Host server name missing" })
        }
        require(servers.size <= 100 && servers.all { it.id.isNotBlank() && it.id.length <= 256 && it.name.length <= 512 })
        require(servers.map { it.id }.distinct().size == servers.size) { "Duplicate host server ID" }
        return CadHostCapabilities(servers.sortedWith(compareBy<CadServer> { it.name }.thenBy { it.id }),
            result.getStringArrayList(C.KEY_MAP_ACTIONS).orEmpty().toSet(), result.getBoolean(C.KEY_TAK_HTTPS))
    }

    fun incidents(serverId: String): List<CanonicalIncident> =
        CanonicalIncident.parseList(request(serverId, "GET", SUBMIT, "fn=getIncidents"))

    /** Fresh single-incident read; returns the server's raw JSON so updates preserve every field. */
    fun incidentJson(serverId: String, uid: String): String {
        val body = request(serverId, "GET", SUBMIT, "fn=getIncident&uid=${uidParam(uid)}")
        check(CanonicalIncident.parse(body).uid == uid) { "CAD returned a different incident" }
        return body
    }

    fun insertNote(serverId: String, note: CadNoteDraft) {
        val body = JSONObject()
            .put("incidentUid", note.incidentUid)
            .put("timestamp", note.timestamp)
            .put("creator", note.creator)
            .put("info", note.info)
            .toString()
        val response = request(serverId, "PUT", "$SUBMIT/result", "fn=insertNote", body)
        val result = try { JSONObject(response) } catch (e: JSONException) {
            throw IllegalStateException("CAD note response unreadable; check notes before retrying", e)
        }
        check(result.optBoolean("success", false)) {
            val errors = result.optJSONArray("errors")
            "CAD rejected note${errors?.optString(0)?.takeIf { it.isNotBlank() }?.let { ": ${it.take(120)}" }.orEmpty()}"
        }
    }

    /** CAD updateIncident replaces the whole record; send the fresh record back with only status changed. */
    fun updateStatus(serverId: String, currentJson: String, status: String) {
        require(status in CadStatus.VALUES) { "Invalid incident status" }
        val record = JSONObject(currentJson)
        val uid = record.getString("uid")
        record.put("status", status)
        val body = record.toString()
        require(body.toByteArray(Charsets.UTF_8).size <= C.MAX_REQUEST_BYTES) { "Incident too large for status update" }
        request(serverId, "POST", SUBMIT, "fn=updateIncident&uid=${uidParam(uid)}", body)
    }

    private fun request(serverId: String, method: String, path: String, query: String, body: String? = null): String {
        val result = checked(services.requestTakServer(Bundle().apply {
            putString(C.KEY_SERVER_ID, serverId)
            putString(C.KEY_METHOD, method)
            putString(C.KEY_PATH, path)
            putString(C.KEY_QUERY, query)
            if (body != null) {
                putString(C.KEY_BODY, body)
                putString(C.KEY_CONTENT_TYPE, "application/json")
            }
        }))
        check(result.getString(C.KEY_SERVER_ID) == serverId) { "CAD server changed; refresh incidents" }
        check(result.containsKey(C.KEY_HTTP_STATUS)) { "CAD HTTP status missing" }
        val status = result.getInt(C.KEY_HTTP_STATUS)
        check(status == 200) { "CAD HTTP $status; verify selected server and CAD access" }
        val response = requireNotNull(result.getString(C.KEY_BODY)) { "CAD response body missing" }
        require(response.toByteArray(Charsets.UTF_8).size <= C.MAX_RESPONSE_BYTES) { "CAD response exceeds host limit" }
        return response
    }

    private fun uidParam(uid: String): String {
        require(uid.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid incident UID" }
        return uid
    }

    fun mapAction(action: String, incident: Incident) {
        require(action in setOf(C.ACTION_BLOODHOUND, C.ACTION_TARGET)) { "Unsupported map action" }
        val position = requireNotNull(incident.ask.position) { "Incident location unavailable" }
        checked(services.requestMapAction(Bundle().apply {
            putString(C.KEY_ACTION, action)
            putString(C.KEY_UID, incident.ask.id)
            putString(C.KEY_LABEL, incident.ask.title.take(256))
            putDouble(C.KEY_LATITUDE, position.latitude)
            putDouble(C.KEY_LONGITUDE, position.longitude)
        }))
    }

    private fun checked(result: Bundle?): Bundle {
        val response = requireNotNull(result) { "Host services response missing" }
        val status = response.getString(C.KEY_STATUS)
        check(status == C.OK) { "Host services ${status ?: "invalid response"}; reconnect or retry action" }
        return response
    }
}
