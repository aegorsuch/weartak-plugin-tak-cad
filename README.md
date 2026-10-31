# WearTAK TAK-CAD responder

Native Wear OS responder plugin. Dispatcher authoring stays in WinTAK.
GPL-3.0-or-later; complete license in `LICENSE`, upstream attribution in `NOTICE`.
Source only: no vendor SDK, wrapper JAR, APK, certificate, or signing material.

Versioning matches WearTAK/MOPP/LACE: local version `5.8.4` / code `584`;
Forge pipeline N uses `5.8.4-pN+<CI_COMMIT_SHORT_SHA>` / code `5840000 + N`.
Pipeline numbers outside 0-9999 or malformed pipeline values fail configuration
rather than producing an invalid or silently downgraded CI version.

## Implemented

- Main screen lists incidents, with this responder's requested or responding
  incidents first, then other active incidents. Canonical matching uses the
  host callsign or the configured vehicle UID, not the CoT UID. Requested
  callsigns/vehicle IDs do not distinguish an ask from a mandatory task; the
  UI labels both **Requested**. Current vehicle responses in `IN_ROUTE` or
  `ON_SCENE` count as responding. Completed/cancelled canonical incidents are
  retained locally but excluded from the active list.
- Tapping a row opens canonical details grouped by field: name/type,
  narrative, location, requested callsigns/vehicle IDs, responding
  vehicles/callsigns/status/ETAs, responding personnel UIDs/arrival times, and notes newest first. Missing coordinates
  remain missing, never synthesized as `(0,0)`. Oversized fields end with an
  ellipsis instead of repeating labels across pages.
- Canonical incident JSON is parsed from `__takCadIncidentDetails` inside the
  CoT detail subtree without assuming an event `type` or `how`. `parseList`
  accepts the bare incident array expected by the ATAK `getIncidents` client.
- Optional Host Services SDK 1.0.0/protocol 1 enables **read-only CAD refresh**
  through the connected host's authenticated HTTPS bridge. Fetch on start,
  every five seconds while running, or after server selection. One connected
  server is selected automatically; multiple require an explicit **Server**
  selection. Disconnected/other-server snapshots and unscoped canonical CoT
  records are hidden when using this extension, rather than mixed across servers. A complete valid
  response atomically replaces canonical records, preserving legacy records;
  failed HTTP/transport/parse/storage operations retain the cache and show errors.
  Returned HTTP status is checked even when the bridge reports `ok`.
- Expanded canonical detail has **Bloodhound** and **Target** actions when
  coordinates exist. The plugin sends the actual coordinates and incident
  UID/label to supported host actions on its worker thread. Bloodhound success
  means activation requested, not arrival or route completion. Older hosts without
  map capabilities disable these controls and show an update-required status.
- Hosted and standalone incident detail have three icon-only actions in order:
  **Add note**, **Bloodhound**, **Focus incident on map**. It matches WearTAK map
  contact controls: 48dp dark-gray circular touch targets, 22dp white artwork,
  4dp spacing, accessible names, and disabled states. Bloodhound uses WearTAK's
  existing dog-head resource, retained byte-for-byte (provenance in NOTICE).
  Missing coordinates disable, rather than hide, both map actions; note entry
  still depends on write availability. Existing notes remain visible.
  A top-right accessible back arrow returns to incidents or exits the
  server picker; no text Back button appears.
  Hosted rendering requires the matching additive WearTAK icon-action renderer.
  Server choices hide their semantic card titles, removing bullet separators
  without altering server names. Incident lists omit the redundant ACTIVE status,
  retaining Requested/Responding involvement. The list pins the incident whose
  UID is reported as the plugin-owned Bloodhound target under **Active Incident**;
  all remaining incidents appear once under **Other Incidents**. CAD ACTIVE status
  and current detail selection do not determine this grouping. Incident detail
  omits the identity/title block without changing stored content or list filtering.
  Address, Notes, Responders, and Summary use bold headings, normal-weight values,
  and alphabetical order. Other fields remain present
  in alphabetical order; missing fields are not synthesized.
  Older text-only hosts reject presentation metadata; matching-host support must
  be advertised before sending it. Update WearTAK for this hosted UX.
  Initialization advertises integer `com.weartak.hostedUi.actionPresentationVersion`
  at least 1 in `PluginInitParcel.hostExtras`. Without it, ACTION content keeps
  exactly the original four keys and text-only rendering; disconnect/shutdown
  clears the presentation grant.
  With host map capability `bloodhound.stop`, `activeBloodhoundUid` identifies
  only the active target owned by this plugin. Its Bloodhound button becomes a
  red stop button with an accessible Stop Bloodhound name. Stop sends only
  `{action: "bloodhound.stop", uid: incidentId}`, needs no coordinates, and never
  records another CAD response. Host rejects stale/nonmatching stops without
  affecting other tracking. Live capabilities refresh on hosted schema requests
  and standalone resume. Successful starts return to WearTAK's main screen via
  the matching host while tracking and the responding write remain active;
  note and target behavior are unchanged.
- Canonical detail supports **Add note** with Wear keyboard/voice input.
  Notes use the host-reported watch callsign and preserve drafts on failure.
  Note writes refresh the incident. Manual status controls and CAD update,
  Status, and Navigation sections are removed from incident detail; the
  automatic verified responding write on Bloodhound start remains intact.
  Runtime status-write support remains independently tested but is not exposed
  as a manual hosted or standalone action.
  The host HTTP bridge must support PUT/POST JSON bodies with the exact
  `application/json` content type. Hosted note entry additionally requires
  the host to bind plugins with `BIND_ALLOW_ACTIVITY_STARTS`; without it,
  Android can block opening the note activity.
  The server's `updateIncident` currently clears `personnelResponding` and
  `firstResponderArrivalTime` even when the plugin sends them unchanged.
  Canonical response writes remain unsupported.
  Canonical incidents never emit legacy response CoT. Existing host forwarding
  must explicitly include the canonical detail element; legacy `how` filtering
  alone will not deliver it. No operational compatibility is claimed.
- Official launcher/toolbar logo comes from the public ATAK plugin at
  `9f60ae05`; source path, immutable revision, SHA-256, and user-stated reuse
  permission are in `NOTICE`. Its black artwork sits on white for contrast
  against WearTAK's dark menu. Five density variants provide a 108dp adaptive
  foreground with a centered 76dp source image, plus 48dp legacy icons.
  Launcher, round launcher, and host Plugins menu all resolve the package's
  adaptive application icon. No host toggle behavior changes are included.
- Explanatory integration/unsupported-feature banners, manual-guidance advice,
  routing-setup disclosure prose and blank-key hint have been removed from UI.
  Labels, incident data, actionable errors/status, delivery uncertainty, and
  routing/data attribution remain. Routing transfer/privacy and setup behavior
  are documented below.

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
  note entry opens NoteActivity and supported Bloodhound start returns to the
  host main screen. Route/list paging stays in the hosted page. Long instructions are paged within SDK
  text/card bounds. The launcher remains a development fallback for routing
  setup until a secure hosted credential-input contract exists.

## Build and verification

Requires JDK 17, Android SDK platform 34, Gradle 8.10.2, and the WearTAK plugin SDK
matching the host, including `MainUiActionContract` and `MainUiCardTypes.ACTION`.
Also requires the additive Host Services SDK 1.0.0/protocol 1 AAR, package
`com.weartak.plugin.sdk.host.v1`. The lifecycle SDK/API version is unchanged.
For local development, both SDKs are supplied as external AARs, never checked in.
Example PowerShell, with machine-specific paths supplied by operator:

```powershell
$env:ANDROID_HOME = '<Android SDK directory>'
$env:WEARTAK_PLUGIN_SDK_AAR = '<absolute SDK AAR path>'
$env:WEARTAK_HOST_SERVICES_SDK_AAR = '<absolute Host Services SDK AAR path>'
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Alternatively pass `-PweartakSdkAar=<absolute path>` and
`-PweartakHostServicesAar=<absolute path>`. Use the release SDK artifacts
for production builds and separately configured authorized signing. No template
signing scripts or keys are used. Android's generated debug signing key is not
sufficient for a host signed with a different certificate: service requires
`com.weartak.plugin.permission.BIND_PLUGIN` at signature protection level.

Tests cover CoT encoding/parsing, input bounds, invalid coordinates/routes,
version ordering, persistent state, local decline, acceptance/refresh, offline
and stale GPS, dispatch failure, stop-during-routing, launcher/setup, and binder
initialization/host validation, hosted schema/actions, Unicode paging, navigation,
and callback refresh. Tests use synthetic incident data and fake host control,
with no network or routing account. Host-services tests cover protocol discovery,
worker-only bridge calls, exact GET path/query, multi-server selection, disconnect,
stop/rebind during fetch, source isolation, HTTP failures, malformed/oversize
responses, atomic snapshots, and map-action failures without mutation replay.

## GitHub CI and release automation

`.github/workflows/android.yml` runs for PRs, branch/tag pushes, and manual dispatch.
It installs JDK 17 / Android 34 / Gradle 8.10.2, checks out an immutable SDK source
revision, tests/builds both SDK variants, then runs this plugin's unit tests,
`assembleDebug`, and `lintDebug`. Reports are uploaded even on failure. No machine
path or unverified binary fallback exists in CI.

CI builds the public SDK source mirror:
`https://github.com/aegorsuch/weartak-plugin-sdk.git`.
Default SDK revision is exact published GitHub commit
[`e2ee52402cf5738e86e43c3f6f1ce1594bd6e5ec`](https://github.com/aegorsuch/weartak-plugin-sdk/commit/e2ee52402cf5738e86e43c3f6f1ce1594bd6e5ec),
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

The additive Host Services SDK is provisioned separately by `ci/fetch_host_sdk.py`
from the approved personal public source mirror
`https://github.com/aegorsuch/weartak-wearos.git`, at a full reviewed immutable
commit `782d8a7e791dd70750d567e22115b739332c04ba` (Host Services SDK 1.0.0).
`WEARTAK_HOST_SDK_REVISION` can override that pin with another full reviewed
40-character commit. No Forge credential or company-mirror access is required.
CI installs Android platform 35 for this standalone library, then runs the pinned
host repository's Gradle wrapper with
`--no-daemon -p weartak-plugin-host-services-sdk assembleRelease`.
Only the standalone SDK module is built, not the host application.
The source checkout verifies HEAD and disables redirects; no moving branch,
machine-local AAR, committed vendor binary, or credential fallback exists.

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

## TAK.gov Forge CI signing and publication

Canonical source is `weartak-plugins/weartak-plugin-tak-cad` on
`git.tak.gov`, default branch `main`. `.gitlab-ci.yml` verifies merge requests,
branch pushes, tags, and manual pipelines; push pipelines are suppressed when an
open merge request already provides coverage. Requires a Linux Docker-compatible
runner with outbound access to Google Android downloads/Maven, Gradle/Maven
repositories, Docker Hub, and the public personal SDK mirror on GitHub.
SDK source checkout remains public. Forge includes the shared TAK Artifactory
token-service template and exchanges job identity for an ephemeral token before
every Gradle build, including the separate SDK build. The root Gradle image uses
`.artifactory_service_create_token_bash`; no bundled repository init script is
installed. Token values must never be logged. If exchange requires account
registration, sign in at `https://artifacts.tak.gov` before retrying.
Existing approved read-only consumer aliases remain supported. User selection uses the
first nonblank `ARTIFACTORY_CONSUME_USER`, `ARTIFACTORY_USER`, or `TAK_USERNAME`;
password selection independently uses `ARTIFACTORY_CONSUME_PASSWORD`,
`ARTIFACTORY_PASSWORD`, or `TAK_PASSWORD`. Both are required. The runner preflight
reports availability booleans only and fails explicitly when the pair is missing.
No variable values are retrieved through administration APIs.

Plugin Gradle plugin and dependency repositories put
`https://artifacts.tak.gov/artifactory/maven-release` first when credentials are
available, preserving Google, Maven Central, and Gradle Plugin Portal fallbacks.
Robolectric's independent test-JVM downloads use the same authenticated repository.
The separately checked-out SDK must also contain this supported repository
configuration at its pinned revision; plugin settings do not affect SDK builds.
Local builds without the pair retain public repositories.

Forge uses Gradle 8.10.2 / JDK 17 and SHA-256-verified Android command-line tools
12.0 to install platform/build-tools 34.0.0. SDK licenses are accepted on this
ephemeral CI runner. The same pinned SDK provisioning helper and Python/Node CI
tests run on both platforms; checklist tests use a separate Node 22 job rather
than the Gradle image's outdated distribution Node. Runner images are pinned by
digest. Forge tests/builds both SDK variants, then tests,
assembles, and lints both plugin variants. Reports and JUnit results are retained
for 30 days, including on failure.

`gradle/weartak-sideload-production.gradle` uses the same signing configuration
as Sextant and LACE. An ignored root `signing.properties` may configure local
release signing, or `WEARTAK_SIGNING_PROPERTIES` may point to a shared properties
file. Relative keystore paths resolve beside that file. Never commit signing
properties or keystores. Local debug builds remain debug-signed.

On a **protected default-branch push only**, Forge requires inherited group
variables `WEARTAK_RELEASE_KEYSTORE_B64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`. The existing `weartak-plugins`
group config documents these as protected/masked; this job consumes them without
changing group/project variables. It writes the keystore and ignored signing
properties with restrictive permissions, validates the alias, signs the release
APK, verifies Android APK signature and certificate SHA-256
`1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d`, then
removes signing files. This is the SHA-256 pinned by both Sextant and LACE
production-release pipelines for WearTAK's signature-protected plugin binding.
Merge-request builds do not receive protected signing credentials and continue
to validate unsigned release assembly. The signed artifact has **not** been
verified until a protected default-branch pipeline passes its certificate check.

After successful signature verification, the protected-main Forge job retains
only `dist/WearTAK-Plugin-TAK-CAD-<version>-p<pipeline>.apk` and its
`dist/SHA256SUMS` checksum as a downloadable CI artifact for 30 days, matching
the WearTAK plugin release naming convention and Sextant/LACE main-build artifact
retention. APK version and embedded pipeline IID come from the manifest, and
embedded IID must match `CI_PIPELINE_IID`. The APK is copied into that artifact
directory only after exact certificate verification succeeds.
Merge requests, unprotected branches, failed signatures, and debug builds cannot
create this production artifact. Signing keystore/properties are removed before
artifact upload; no APK or signing material is committed.

**No automatic Forge GitHub publisher or persistent remote mirror is enabled.**
The user authorized scoped manual source mirroring and production APK publication
to personal `aegorsuch/weartak-plugin-tak-cad`, never a company repository.
Publication must wait for the protected-main Forge signature check, recheck that
personal main is an ancestor of the exact canonical source SHA, and use only a
normal fast-forward push. Divergence stops publication; no force overwrite.
Publish the verified production APK as
`WearTAK-Plugin-TAK-CAD-<version>-p<pipeline>.apk` and matching SHA256SUMS with
that source SHA in release notes. Do not substitute a locally debug-signed APK.
Existing authenticated operator tools are sufficient for this one-time flow;
no new token, persistent access, GitLab variable, or mirror configuration is
required. Ongoing automatic publication remains separate, unconfigured work.
Existing GitHub debug-prerelease/checklist automation above remains unchanged.

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
Additive presentation fields are `actionPresentation` (`ICON` for icon buttons),
`actionIcon` (`NOTE`, `BLOODHOUND`, `STOP`, `TARGET`, `BACK`), `actionGroup` (body icon row),
`actionPlacement` (`HEADER_END` for Back), and `showTitle` (`false` for icon actions
and server choices). Card titles stay nonblank semantic names; action labels
become accessible names on icon controls, not visible text. Other actions retain
default text presentation. No drawable resource IDs or image payloads cross Binder.
Host invokes `invokeMainUiAction` only on user tap, passing decoded string args;
it re-fetches schema after page entry/actions and main-UI schema/state callbacks.
No private content-binding keys, route payloads, or credentials are invented.

| Action | Arguments |
|---|---|
| `takcad.incident.open` | `incidentId` |
| `takcad.incidents.previous`, `takcad.incidents.next`, `takcad.back` | none |
| `takcad.server.choose`, `takcad.server.cancel` | none |
| `takcad.servers.previous`, `takcad.servers.next` | none (open server list) |
| `takcad.server.select` | `serverId` from listed host servers |
| `takcad.bloodhound`, `takcad.target` | selected `incidentId` |
| `takcad.incident.accept`, `takcad.incident.decline`, `takcad.refresh` | selected `incidentId` |
| `takcad.route.previous`, `takcad.route.next` | selected `incidentId` |
| `takcad.route.text.previous`, `takcad.route.text.next` | selected `incidentId` |

Unknown arguments and stale/mismatched selected IDs are rejected; runtime still
enforces acceptance, connectivity, GPS freshness, and lifecycle gates. Receive
bridge `takcad.receive` remains host-transport-only, never rendered as an action.
Schema/state contain no ORS key; hosted secure token/UID setup is **unavailable**
until SDK/host supply a reviewed secret-input/provisioning contract. Development
fallback is not claimed to satisfy that missing in-menu configuration feature.

### Optional Host Services extension

Discover `HostServicesContract.fromHostExtras(init.hostExtras)` during validated
initialization; `null` means an older host. All synchronous extension methods run
on the responder worker. Calls are scoped to that initialization; stop/rebind
generation checks discard stale responses before persistence or success reporting.
Refresh capabilities before server fetches/map actions. No CAD vehicle identity
is supplied by the host: use the configured vehicle UID or exact callsign match
for involvement; never infer a vehicle UID from the watch UID/callsign.

Incident list starts with **Active Server**, then compact server selector, then
**Active Incident** and **Other Incidents** sections. Only the current plugin-owned
Bloodhound target appears in the first section; every other incident appears once
in the second. With no active target, the first section is empty. No helper or
refresh text is shown. Tapping
the selector lists up to four host REST-capable TAK servers at a time with a
checkmark on selection. Host Services v1 reports only server ID/name, so per-server
connection status is not shown. Picking a server saves it (`cadServerId`) and
refreshes once; saved choice is restored when that server is listed again. One
listed server is auto-selected. Automatic incident polling runs every 5 s;
consecutive failures back off 5 s, 10 s, 20 s ... up to 5 min. Success or server
selection resets backoff. Detail actions appear first, followed by each field once.

Read endpoint is exactly
`GET /Marti/api/plugins/tak.server.plugins.TakCadServerPlugin/submit?fn=getIncidents`.
TAK Server `PluginDataApi` requires a non-empty `Accept` request header (query
`accept` does not satisfy it); without it the server returns HTTP 500. Host
Services v1 carries no headers, so the host must send `Accept: application/json`.
The host selects connected-server HTTPS port 8443, retains its client identity
and trust, validates hostnames strictly, refuses redirects/transport retries, and
bounds decompressed responses to 64 KiB. No server API is contacted by tests.
Credentials/certificates are never returned to or stored by the plugin.
Map actions require actual incident coordinates rather than assuming an incident
UID already exists in the host map. No endpoint, CoT producer type/how, tasking
operation, or vehicle identity is invented.

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

Standalone plugin activity permits screenshots, including routing setup; API-key
input remains masked. Screenshots may expose incident details and coordinates.
Hosted TAK-CAD pages use the WearTAK host window's screenshot policy. This plugin
does not override host, work-profile, or device-management capture restrictions.

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
That older public prototype does not expose targeted task assignments,
structured incident types, notes APIs, or location-history queries. Its summary
is not treated as structured canonical data.

The newer canonical ATAK client is separately pinned to
[`Raytheon/tak-cad-atak@9a2a7ca5`](https://git.tak.gov/Raytheon/tak-cad-atak/-/tree/9a2a7ca53128de56a056a0e891dae05f09f78113).
Evidence: `cot/IncidentDetailHandler.java`, `dto/Incident.java`,
`dto/Vehicle.java`, `dto/VehicleResponse.java`, `dto/Note.java`,
`rest/TakCadPluginService.java`, and `scheduling/CadTaskManager.java`.
It consumes canonical incident JSON, uses selected-server mutual TLS on port
8443 by default, reads `getIncidents`/`getIncident`, writes vehicle responses and
notes through `Marti/api/plugins/tak.server.plugins.TakCadServerPlugin/submit`,
and polls incident data every five seconds. The optional host bridge now wires
`getIncidents` reads and host map actions only. It does not import server
private keys or disable TLS hostname verification.

The server repository is inaccessible with current source-read authorization.
Deployed CAD server version, full producer CoT envelope and recipient routing,
mandatory-task semantics, location-history retrieval, and live payload fixtures
remain unverified. Tests use synthetic client-shaped data, not captured traffic.

To complete requested workflow, host/SDK and WinTAK/backend owners must provide authenticated
recipient/task semantics, task acceptance/mandatory assignment behavior, membership
and closure acknowledgements, stable note IDs/order/conflict policy, real-time note
events and authorized location-history retrieval. Incoming
CoT must be authenticated and appropriately routed by host: legacy payloads have
no per-responder recipient or authorization signature. This app cannot prove who
authored a received event beyond its trusted host transport.

Not field-validated with a watch, real WearTAK host, dispatcher, or live routing key.
Do not treat passing source tests/build as an operational deployment approval.
