# WearTAK TAK-CAD responder

Native Wear OS responder plugin. Dispatcher authoring stays in WinTAK.
GPL-3.0-or-later; complete license in `LICENSE`, upstream attribution in `NOTICE`.
Source only: no vendor SDK, wrapper JAR, APK, certificate, or signing material.

## Implemented

- Parse upstream incident asks; require explicit acceptance. Local decline does
  not invent a network rejection message.
- Persist incident title, summary, coordinates, participation, own route/version,
  and other responder routes. Atomic replacement; storage errors remain visible.
- On acceptance, request a driving route and queue upstream-compatible response
  CoT through WearTAK. Queue success is **not dispatcher delivery confirmation**:
  upstream and host API expose no acknowledgement.
- Show route instructions, estimated travel time, and other responders' estimated
  travel times with age and a stale label after 90 seconds. Route instructions
  advance manually, not through automatic maneuver detection. Refresh resets
  instructions to the newly computed route.
- Refresh accepted routes every 10 seconds while host starts plugin; pause when
  stopped/disconnected. Require an available GPS snapshot no older than 60 seconds.
- Deduplicate response versions by responder CoT UID (not callsign); ignore
  duplicate/older versions. Responders seen without an ask do not become assignments.
- Encrypt runtime routing credentials using Android Keystore AES-GCM; never expose
  key via host settings, backups, logs, or repository configuration.
- Serve responder list/details, accept/local-decline, refresh, instructions, and
  team ETAs through the WearTAK hosted TAK-CAD page. Plugin owns selection/paging;
  no hosted action launches an activity. Long details/instructions are paged within
  SDK text/card bounds. The launcher remains a development fallback for routing
  setup until a secure hosted credential-input contract exists.

## Build and verification

Requires JDK 17, Android SDK platform 34, Gradle 8.10.2, and the WearTAK plugin SDK
matching the host, including `MainUiActionContract` and `MainUiCardTypes.ACTION`.
For local development, SDK is supplied as an external AAR, never checked in.
Example PowerShell, with machine-specific paths supplied by operator:

```powershell
$env:ANDROID_HOME = '<Android SDK directory>'
$env:WEARTAK_PLUGIN_SDK_AAR = '<absolute SDK AAR path>'
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Alternatively pass `-PweartakSdkAar=<absolute path>`. Use the release SDK artifact
for production builds and separately configured authorized signing. No template
signing scripts or keys are used. Android's generated debug signing key is not
sufficient for a host signed with a different certificate: service requires
`com.weartak.plugin.permission.BIND_PLUGIN` at signature protection level.

Tests cover CoT encoding/parsing, input bounds, invalid coordinates/routes,
version ordering, persistent state, local decline, acceptance/refresh, offline
and stale GPS, dispatch failure, stop-during-routing, launcher/setup, and binder
initialization/host validation, hosted schema/actions, Unicode paging, navigation,
and callback refresh. Tests use synthetic incident data and fake host control,
with no network or routing account.

## GitHub CI and release automation

`.github/workflows/android.yml` runs for PRs, branch/tag pushes, and manual dispatch.
It installs JDK 17 / Android 34 / Gradle 8.10.2, checks out an immutable SDK source
revision, tests/builds both SDK variants, then runs this plugin's unit tests,
`assembleDebug`, and `lintDebug`. Reports are uploaded even on failure. No machine
path or unverified binary fallback exists in CI.

CI builds the public SDK source mirror:
`https://github.com/aegorsuch/weartak-plugin-sdk.git`.
Default SDK revision is exact published GitHub commit
[`a4f784338efb1d8eb048591a4a3f57b3f7e8ffa3`](https://github.com/aegorsuch/weartak-plugin-sdk/commit/a4f784338efb1d8eb048591a4a3f57b3f7e8ffa3),
including compatible v3 and ACTION APIs, matching canonical
[SDK merge request 2](https://git.tak.gov/weartak-plugins/weartak-plugin-sdk/-/merge_requests/2).
Repository variable
**`WEARTAK_SDK_REVISION`** can override it with another full reviewed
40-character commit available in this mirror. Public checkout needs no credential.
Optional secret **`WEARTAK_SDK_TOKEN`** accepts a read-only GitHub token if repository
visibility later requires authentication. Credential is transient git HTTP auth, never written
to git config/artifacts; redirects are refused and checked-out HEAD must match.
Fork PRs use the public pinned source without secrets. If SDK visibility becomes
private, fork PRs cannot access repository secrets and checks fail explicitly.
Do not use `pull_request_target` to run
untrusted PR code with credentials.

SDK checkout verifies the full commit hash, with no machine-local fallback.
SDK owner recovered host v3
APIs and verified compatibility before merging the pinned revision; plugin v3
initialization delegates to the same validated v2 initializer, without advertising
unsupported audio capabilities. Local validation uses the owner's compatible AAR
outside this repository. No host/template SDK artifact is overwritten here.

Pushing a `v*` tag runs the same full checks before the release job can publish
`TAK-CAD-debug-not-production-signed.apk` and `SHA256SUMS` as a **debug prerelease**.
No production-signing secret is assumed. Generated debug certificate changes on
fresh runners and normally cannot bind a differently signed WearTAK host.
Production distribution requires separate authorized matching signing configuration
and SDK/dependency license review; automation does not pretend debug is production.
Publishing happens only after a push, not in this local session.

Release automation creates an idempotent testing-checklist issue, optionally
assigned using repository variable **`RELEASE_TESTER`**. The separate
`.github/workflows/release-testing.yml` handles manually published releases and
manual rechecks by tag. CI-created releases call the same checklist job directly
because `GITHUB_TOKEN` events do not trigger another workflow. Workflow must be
present on the GitHub default branch for manual/external-release triggers.
GitHub Actions/Issues must be enabled and token permissions allowed. The canonical
GitLab remote is not automatically mirrored/pushed by these files.

CI helper checks: `python -m unittest discover -s ci -p "test_*.py" -v` and
`node --test ci/release-testing/create_checklist.test.cjs`.

## Required host integration

**Current template SDK has no incoming CoT subscription API.** Its
`IWearTakHostCallback.onCotReadyEvent` is plugin-to-host, not incoming transport.
No automatic watch reception is claimed. Host must forward inbound TAK-CAD CoT
on its already authorized plugin binding after initialization/start:

```kotlin
plugin.invokeMainUiAction(
    "takcad.receive",
    Bundle().apply { putString("xml", receivedCotXml) },
)
```

This is this plugin's explicit adapter contract, **not an existing upstream or
WearTAK SDK subscription**. Forward `tak-cad-incident`, `tak-cad-response`, and
`tak-cad-response-update` events. Maximum XML is 160,000 characters; receive is
asynchronous and validation errors surface in status/watch UI/host error callback.
Host must expose the plugin-owned schema in the TAK-CAD menu page, not launch the
standalone development activity.
No exported broadcast receiver, public import intent, or unauthenticated bridge
is provided.

### Hosted page contract

`pluginId` and `packageName` are `com.weartak.plugin.takcad`. Capabilities advertise
`hostedMainUi=true`, `externalMainActivity=false`, `hostedSettingsUi=false`.
Host menu registration/rendering lives in the separate WearTAK host change; this
repository does not modify the host.

Schema uses SDK STATUS title/subtitle for bounded static text and SDK ACTION cards
created through `MainUiActionContract.createContent`. Content keys are `actionId`,
`actionLabel`, `enabled` (Boolean), and `actionArgsJson` (JSON object of strings).
Host invokes `invokeMainUiAction` only on user tap, passing decoded string args;
it re-fetches schema after page entry/actions and main-UI schema/state callbacks.
No private content-binding keys, route payloads, or credentials are invented.

| Action | Arguments |
|---|---|
| `takcad.incident.open` | `incidentId` |
| `takcad.incidents.previous`, `takcad.incidents.next`, `takcad.back` | none |
| `takcad.incident.accept`, `takcad.incident.decline`, `takcad.refresh` | selected `incidentId` |
| `takcad.details.previous`, `takcad.details.next` | selected `incidentId` |
| `takcad.route.previous`, `takcad.route.next` | selected `incidentId` |
| `takcad.route.text.previous`, `takcad.route.text.next` | selected `incidentId` |
| `takcad.responders.previous`, `takcad.responders.next` | selected `incidentId` |

Unknown arguments and stale/mismatched selected IDs are rejected; runtime still
enforces acceptance, connectivity, GPS freshness, and lifecycle gates. Receive
bridge `takcad.receive` remains host-transport-only, never rendered as an action.
Schema/state contain no ORS key; hosted secure token/UID setup is **unavailable**
until SDK/host supply a reviewed secret-input/provisioning contract. Development
fallback is not claimed to satisfy that missing in-menu configuration feature.

Host also must implement `requestCotDispatch(CotRequestParcel("cot_xml", ...))`,
`isCotConnected`, `getDeviceStatus`, and normal plugin lifecycle. Device telemetry
uses SDK `DeviceStatusContract` keys: location availability, latitude, longitude,
callsign, and snapshot time. SDK does not expose host CoT UID; operator must enter
the **actual WearTAK self UID** in Routing setup. Changing UID with active responses
is rejected. The plugin never synthesizes an identity and calls it the host UID.

Receive queues and protocol transport are not end-to-end acknowledged. A crash
between host dispatch and local persistence may leave delivery uncertain; this
plugin cannot guarantee exactly-once participation. There is no upstream closure
or withdrawal message, so stored responses do not silently become closed incidents.
Local state is bounded to 100 incidents, 100 responders per incident, and 20 MB;
capacity errors are explicit, not silent eviction. Operational retention and
authenticated incident closure require a host/dispatcher contract.

## Routing setup and privacy

For development only, open standalone TAK-CAD launcher > Routing setup.
Host never launches it as part of the TAK-CAD menu flow. Enter actual WearTAK UID and an
OpenRouteService API key. Saving a key enables route requests for accepted asks.
Blank key preserves existing key; Remove stored API key pauses route computation.
Key is not shipped with app. Clear application data to remove local incident data
and configuration; no backup/export is enabled.

Routing sends current responder coordinates and incident destination to
`https://api.openrouteservice.org/v2/directions/driving-car`.
Upstream documents this provider; deployment must authorize that data transfer,
account, quota, network access, and operational use. Authorization header carries
the key; redirects are refused and cleartext HTTP is disabled. Requests time out;
HTTP/key/quota/route errors remain visible and do not mark a pending ask accepted.
No external route service is contacted by tests. Routes are advisory: observe
road conditions and agency procedures.

## Proven upstream protocol and remaining gaps

Evidence pinned to
[`raytheonbbn/tak-cad@9f60ae0`](https://github.com/raytheonbbn/tak-cad/tree/9f60ae056057b7496c65688e3f4acd86a398438f).

| Wire behavior | Source evidence |
|---|---|
| CoT `how=tak-cad-incident`; string JSON in nested detail; `incident_id`, `title`, `summary`, `latitude`, `longitude` | `ReportIncidentDropDownReceiver.java` |
| Explicit responder prompt; acceptance sends `how=tak-cad-response`, responder UID, callsign, incident metadata, `version="0"`, JSON-string `directions` | `TakCadMapComponent.java` |
| `how=tak-cad-response-update`, callsign, incident ID, increasing version, directions; 10-second route refresh | `IncidentResponderManager.java` |
| GeoJSON `features[].properties.summary.duration` seconds and `segments[].steps[].instruction` | `routing/DirectionsResponsePojos.java` |
| OpenRouteService driving-car request, start/end longitude then latitude | `routing/OpenRouteFunctions.java` and upstream README |

These are source files beneath
`tak-cad-atak/app/src/main/java/com/atakmap/android/takcad/`.
No upstream source exposes targeted **task assignments**, incident-type enum,
shared-note create/update stream, or location-specific historical query. Those
features are **not implemented** rather than assigned invented CoT types or REST
endpoints. Summary can contain dispatcher narrative, but is not presented as
structured incident type, shared notes, or verified location history.

To complete requested workflow, WinTAK/backend owners must provide authenticated
recipient/task semantics, task acceptance/mandatory assignment behavior, membership
and closure acknowledgements, stable note IDs/order/conflict policy, real-time note
events, incident type schema, and authorized location-history retrieval. Incoming
CoT must be authenticated and appropriately routed by host: legacy payloads have
no per-responder recipient or authorization signature. This app cannot prove who
authored a received event beyond its trusted host transport.

Not field-validated with a watch, real WearTAK host, dispatcher, or live routing key.
Do not treat passing source tests/build as an operational deployment approval.
