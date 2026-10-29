// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal const val ROUTE = """{"features":[{"properties":{"summary":{"duration":125.5},"segments":[{"steps":[{"instruction":"Turn right"},{"instruction":"Arrive"}]}]}}]}"""
internal val ASK = CadEvent.Ask("incident-1", "Medical & rescue", "Entrance <north>", Position(41.8, -87.6))
internal fun askXml(ask: CadEvent.Ask = ASK): String {
    val json = JSONObject().put("incident_id", ask.id).put("title", ask.title).put("summary", ask.summary)
        .put("latitude", ask.position.latitude.toString()).put("longitude", ask.position.longitude.toString())
    return """<event how="tak-cad-incident" uid="dispatch"><detail><detail>${android.text.TextUtils.htmlEncode(json.toString())}</detail></detail></event>"""
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class CadProtocolTest {
    @Test fun parsesUpstreamAskWithEscapedJson() {
        assertEquals(ASK, CadProtocol.parse(askXml()))
    }

    @Test fun responseRoundTripsWithVersionZeroMetadata() {
        val xml = CadProtocol.response(ASK, "watch&1", "Unit <1>", 0, Route.parse(ROUTE), 1_000, Position(40.0, -88.0))
        val response = CadProtocol.parse(xml) as CadEvent.Response
        assertEquals("watch&1", response.uid)
        assertEquals("Unit <1>", response.callsign)
        assertEquals(ASK, response.incident)
        assertEquals(125.5, response.route.seconds, 0.01)
        assertTrue(xml.contains("1970-01-01T00:01:01Z"))
        assertTrue(xml.contains("lat=\"40.0\""))
        assertTrue(xml.contains("lon=\"-88.0\""))
    }

    @Test fun routeUpdateHasNoIncidentReplacementMetadata() {
        val response = CadProtocol.parse(CadProtocol.response(ASK, "watch", "Unit", 2, Route.parse(ROUTE), 1_000, ASK.position)) as CadEvent.Response
        assertNull(response.incident)
        assertEquals(2, response.version)
    }

    @Test fun unrelatedCotIgnored() {
        assertNull(CadProtocol.parse("""<event how="m-g"><detail><contact callsign="Unit"/></detail></event>"""))
    }

    @Test fun rejectsOversizeDtdCoordinatesAndInvalidRoutes() {
        assertThrows(IllegalArgumentException::class.java) { CadProtocol.parse(" ".repeat(CadProtocol.MAX_XML + 1)) }
        assertThrows(IllegalArgumentException::class.java) { CadProtocol.parse("""<!DOCTYPE event [<!ENTITY x SYSTEM "file:///test">]><event/>""") }
        assertThrows(IllegalArgumentException::class.java) { Position(Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Position(91.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Position(0.0, 181.0) }
        assertThrows(org.json.JSONException::class.java) { Route.parse("""{"features":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { Route.parse(ROUTE.replace("125.5", "-1")) }
        assertThrows(IllegalArgumentException::class.java) { Route.parse(ROUTE.replace("""{"instruction":"Turn right"},{"instruction":"Arrive"}""", "")) }
    }
}
