// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.Instant

data class Position(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
    }
}

data class Route(val json: String, val seconds: Double, val steps: List<String>) {
    companion object {
        fun parse(json: String): Route {
            require(json.length <= CadProtocol.MAX_XML / 2) { "Route too large for host transport" }
            val root = JSONObject(json)
            val properties = root.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
            val seconds = properties.getJSONObject("summary").getDouble("duration")
            require(seconds.isFinite() && seconds >= 0) { "Invalid route duration" }
            val segments = properties.getJSONArray("segments")
            val steps = buildList {
                for (i in 0 until segments.length()) {
                    val items = segments.getJSONObject(i).getJSONArray("steps")
                    for (j in 0 until items.length()) {
                        add(items.getJSONObject(j).getString("instruction").also {
                            require(it.isNotBlank() && it.length <= 2048) { "Invalid route instruction" }
                        })
                    }
                }
            }
            require(steps.isNotEmpty() && steps.size <= 1024) { "Route has no usable instructions" }
            return Route(json, seconds, steps)
        }
    }
}

sealed interface CadEvent {
    data class Ask(
        val id: String, val title: String, val summary: String, val position: Position?,
        val canonical: CanonicalIncident? = null,
    ) : CadEvent
    data class Response(
        val incidentId: String,
        val uid: String,
        val callsign: String,
        val version: Int,
        val route: Route,
        val incident: Ask?,
    ) : CadEvent
}

object CadProtocol {
    const val MAX_XML = 160_000
    const val INCIDENT = "tak-cad-incident"
    const val RESPONSE = "tak-cad-response"
    const val UPDATE = "tak-cad-response-update"

    fun parse(xml: String): CadEvent? {
        require(xml.length <= MAX_XML) { "CoT exceeds transport limit" }
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "DTD not allowed" }
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var how: String? = null
        var uid: String? = null
        var detailDepth = -1
        val text = StringBuilder()
        val canonicalText = StringBuilder()
        var canonicalDepth = -1
        var canonicalSeen = false
        var seenRoot = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.depth == 1) {
                        require(!seenRoot && parser.name == "event") { "Expected CoT event" }
                        seenRoot = true
                        how = parser.getAttributeValue(null, "how")
                        uid = parser.getAttributeValue(null, "uid")
                    }
                    if (parser.name == "detail" && parser.depth == 2) detailDepth = parser.depth
                    if (parser.name == "__takCadIncidentDetails" && parser.depth == 3 && detailDepth == 2) {
                        require(!canonicalSeen) { "Duplicate canonical incident detail" }
                        canonicalSeen = true
                        canonicalDepth = parser.depth
                    }
                }
                XmlPullParser.TEXT -> {
                    if (detailDepth > 0) text.append(parser.text)
                    if (canonicalDepth > 0) canonicalText.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.depth == detailDepth) detailDepth = -1
                    if (parser.depth == canonicalDepth) canonicalDepth = -1
                }
            }
            parser.next()
        }
        require(seenRoot) { "Missing CoT event" }
        if (canonicalSeen) return CanonicalIncident.parse(canonicalText.toString()).ask()
        if (how !in setOf(INCIDENT, RESPONSE, UPDATE)) return null
        val metadata = JSONObject(text.toString())
        val id = metadata.requiredText("incident_id", 256)
        fun incident() = CadEvent.Ask(
            id, metadata.requiredText("title", 512), metadata.requiredText("summary", 8192, allowEmpty = true),
            Position(metadata.getString("latitude").toDouble(), metadata.getString("longitude").toDouble()),
        )
        return if (how == INCIDENT) incident() else {
            val version = metadata.getString("version").toInt()
            require(version >= 0) { "Invalid response version" }
            CadEvent.Response(
                id, requireNotNull(uid).also { require(it.isNotBlank() && it.length <= 256) { "Invalid responder UID" } },
                metadata.requiredText("callsign", 256), version,
                Route.parse(metadata.getString("directions")), if (how == RESPONSE) incident() else null,
            )
        }
    }

    fun response(
        incident: CadEvent.Ask, uid: String, callsign: String, version: Int, route: Route,
        now: Long, responderPosition: Position,
    ): String {
        require(incident.canonical == null) { "Canonical responses require authenticated CAD server access" }
        val destination = requireNotNull(incident.position) { "Incident location unavailable" }
        require(uid.isNotBlank() && uid.length <= 256 && callsign.isNotBlank() && callsign.length <= 256)
        require(version >= 0)
        val data = JSONObject().put("incident_id", incident.id).put("callsign", callsign)
            .put("version", version.toString()).put("directions", route.json)
        if (version == 0) {
            data.put("title", incident.title).put("summary", incident.summary)
                .put("latitude", destination.latitude.toString()).put("longitude", destination.longitude.toString())
        }
        val out = java.io.StringWriter()
        val serializer = Xml.newSerializer()
        serializer.setOutput(out)
        serializer.startTag(null, "event")
        for ((key, value) in mapOf(
            "version" to "2.0", "uid" to uid, "type" to "a-f-G-U-C",
            "how" to if (version == 0) RESPONSE else UPDATE,
            "time" to Instant.ofEpochMilli(now).toString(),
            "start" to Instant.ofEpochMilli(now).toString(),
            "stale" to Instant.ofEpochMilli(now + 60_000).toString(),
        )) serializer.attribute(null, key, value)
        serializer.startTag(null, "point")
        for ((key, value) in mapOf("lat" to responderPosition.latitude.toString(),
            "lon" to responderPosition.longitude.toString(), "hae" to "9999999", "ce" to "9999999", "le" to "9999999")) {
            serializer.attribute(null, key, value)
        }
        serializer.endTag(null, "point")
        // Upstream extracts JSON text inside the detail subtree, not named attributes.
        serializer.startTag(null, "detail").startTag(null, "detail").text(data.toString())
            .endTag(null, "detail").endTag(null, "detail").endTag(null, "event")
        serializer.flush()
        return out.toString().also { require(it.length <= MAX_XML) { "Response exceeds host transport limit" } }
    }
}

private fun JSONObject.requiredText(key: String, limit: Int, allowEmpty: Boolean = false): String =
    getString(key).also { require(it.length <= limit && (allowEmpty || it.isNotBlank())) { "Invalid $key" } }
