# Automations tab — design

**Date:** 2026-10-27
**Status:** Design, pending plan
**Owner:** Gekkotron

## Intent & why

Oikos-OS exposes a mature **Flows** subsystem — user-authored
trigger/action automations with first-class `enabled` and `area`
fields. Today the only way for the user to toggle a flow is to open the
Oikos-OS web canvas editor. On a phone that is slow and overkill when
all they want is "turn the morning routine off while we're on
holiday".

The app needs a lightweight home for that single interaction: see every
flow, filter by room, flip a toggle. Nothing else.

## Goals

- A new bottom-tab screen, **Automatisations**, placed last after
  Accueil / Énergie / Sécurité.
- Lists every flow published by Oikos-OS, grouped under its room.
- A horizontal room filter chip row at the top (same `RoomChips`
  composable as Accueil), including an "All" chip and an
  "Unassigned" bucket for flows with no `area`.
- A toggle per row that flips the flow's `enabled` bool,
  persisted on the hub, surviving restart.
- Live updates: when a flow changes elsewhere (web canvas, another
  app instance, hub restart), the row reflects it without the user
  pulling to refresh.
- Works both on LAN (direct) and remote (via the Plan 2 gateway),
  the same way MQTT does today.

## Non-goals

- Creating, editing, deleting, or inspecting a flow's logic (nodes,
  edges, triggers, actions). The web canvas stays the author's
  tool.
- Moving a flow to a different room (`POST /api/flows/{id}/area`
  endpoint exists but is out of scope — matches "I just can enable or
  disable them").
- Manual run (`POST /api/flows/{id}/run`).
- Showing a flow's description, last-run time, next-trigger time, or
  "invalid" error badge. Keep the row scannable and minimal.
- Any Home-screen integration (chip, favourite, widget). Flows live on
  their own tab, nowhere else.

## Anti-goals

- Becoming a developer tool. If the row grows a status pill, a chip
  for mode, a validity badge, and a run button, we've lost. The row
  stays "label, room, switch".
- Rendering flows alongside devices. Automations are not entities and
  should never show up on Accueil or in a room's device grid.
- Any dependency on WebSocket. The gateway doesn't tunnel WS and we
  are not extending it for this feature.

## Constraints

- **Transport parity with MQTT.** The screen must work on both LAN
  Wi-Fi and remote-via-gateway, using the same selection logic MQTT
  uses today. Reuses `LanOrGatewayGet` in `core:transport`.
- **No new networking abstractions.** No WebSocket client in the
  app. No new HTTP library. Reuse what `LanOrGatewayGet` provides
  (GET + JSON POST).
- **Gateway allow-rules already cover the hub HTTP port.** No
  changes to the gateway key / rules / setup QR / onboarding. The
  toggle POST goes out via the gateway unchanged.
- **The Automations tab is hidden under the Demo profile.** Tab bar
  is conditional on `profile != Demo`. No fake flow data to maintain.
- **KMP-forward.** The non-UI parts (DTOs, client interface, state
  flow) live in a new `commonMain` module (`core:flows`,
  `oikos.kmp.jvm.library`) so a future CMP screen can reuse them.
  The screen itself (`feature:automations`) stays Android for now —
  it uses `RoomChips` from `core:designsystem` which is
  Android-only.
- **Minimum of ten lines of app code touched on the Oikos-OS side.**
  The subsystem already carries everything we need. If a design
  iteration drifts toward "we also need Oikos-OS to publish X", stop
  and ask.

## How it gets made — decided

### Data flow

- **State in → MQTT.** The app subscribes to `oikos/automation/#`
  on its existing MQTT connection. Each retained message
  `oikos/automation/<id>` carries a full `Flow` JSON (per
  `crates/oikos-core/src/flow/model.rs`). The subscription IS the list
  and the live-update channel. No polling, no WebSocket, no new
  socket.
- **State parse.** Decode each payload into a Kotlin `Flow` DTO.
  Keep only the fields the screen needs: `id`, `name`, `area`,
  `enabled`. Ignore `description`, `mode`, `nodes`, `edges` for
  now (DTO can carry them for future screens; the UI doesn't read
  them).
- **Deletion.** A retained publish with an empty payload on
  `oikos/automation/<id>` is a delete — drop the row.
- **State out → REST.** The toggle calls `POST
  /api/v1/flows/{id}/enabled` with `{"enabled": true|false}` through
  `LanOrGatewayGet.post(...)`. The optimistic flip is reverted if the
  call fails; the retained MQTT echo then reconciles state. Base URL
  is derived the same way `ProfileHistoryClient` derives its
  `effectiveHistoryUrl()` — from the broker host, hub port 8081. No
  new field on `ConnectionProfile`, no new settings UI.

### Where it lives in the app

- **New Gradle module** `core:flows` (`oikos.kmp.jvm.library`):
  - `commonMain`: `FlowDto`, `FlowsClient` interface, a
    `FlowsRepository` that exposes `StateFlow<List<FlowDto>>` and a
    `suspend setEnabled(id, Boolean): Result<Unit>`.
  - `jvmMain` (or Android bind in `:app`): the
    `LanOrGatewayGet`-backed `FlowsClient` implementation; the
    MQTT subscription glue that feeds the repository (hooked into
    the existing `MqttDiscoverySource` or a sibling source — the
    builder decides which fits best).
- **New Gradle module** `feature:automations`
  (`oikos.android.feature`):
  - `AutomationsRoute(...)`: the scaffold + screen.
  - `AutomationsViewModel`: pulls `flows` and `areas` from the
    repository and the existing area source, builds filtered rows,
    handles toggle.
  - `AutomationsScreen`: `RoomChips` row + `LazyColumn` of
    rows. Each row is a tile with the flow name on the left and a
    `Switch` on the right. Area label (localised) appears under
    the name when the "All" chip is selected; hidden otherwise
    (room is implied by the current chip).

### Tab bar entry

- Add `@Serializable data object AutomationsKey : NavKey` in
  `OikosNavHost.kt`.
- Insert `AutomationsKey` into `SIDE_TABS` in
  `MainTabs.kt` so back returns to Accueil like Énergie and
  Sécurité.
- Append the fourth `NavigationBarItem` to `MainTabBar`,
  **after** Sécurité.
- Register `entry<AutomationsKey> { AutomationsRoute(...) }` in
  `OikosNavHost`.
- Icon: a new `OikosGlyph.AUTOMATION` SVG path in
  `StaticIcon.kt`. Default proposal: a wand-with-spark or an
  arrow-loop glyph; the builder picks the concrete path from the
  existing icon vocabulary.
- Label string resource `R.string.nav_automations`:
  - FR: `Automatisations`
  - EN: `Automations`

### States

- **Empty.** "No automations yet. Create one from the Oikos-OS
  web interface." (localised; no CTA button — the web canvas is not
  linked from the app).
- **Loading.** Shimmer rows until the flows repo's own settle timer
  fires: whichever comes first between the first `oikos/automation/*`
  retained message and ~2 s after MQTT link-up. The flows repo owns
  this timer; it does not reuse `MqttDiscoverySource.hasSettled`.
- **Offline / hub unreachable.** Show the last-known list from the
  live MQTT subscription (whatever was received since the last broker
  link-up) with a muted banner "Hors ligne — état figé". Toggle
  attempts are disabled while offline. **Not guaranteed on cold start
  offline:** if the app is killed and relaunched without a broker
  reconnect, the tab shows the shimmer-then-empty state. No persisted
  cache for flows beyond what already lives in-memory on the MQTT
  source.
- **Toggle failure (incl. invalid flow on the hub side).** Row flips
  back; a one-shot toast "Impossible de modifier l'automatisation".
  No retry UI, no error reason surfaced, no `invalid` state badge
  on the row.

### Filter chips

- "Toutes" (All) first, then one chip per `Area` the app knows
  about (same source as Accueil's `HomeUiBuilder`), then "Non
  assignées" at the end if any flow has a null `area`.
- "Toutes" is the default on screen open. The last-selected chip is
  **not** persisted across sessions (keep it stateless — matches the
  minimal spirit).

## What's left to the builder

- Exact file layout inside `core:flows` and `feature:automations`
  (sources, DI wiring, module boundaries).
- Which MQTT source class owns the `oikos/automation/#`
  subscription (new source vs. extend `MqttDiscoverySource`).
- The concrete `OikosGlyph.AUTOMATION` SVG path.
- Any additional locale strings beyond FR/EN (follow whatever the
  rest of the app does).
- Test shape: unit tests for the repository (MQTT in → state flow
  out, toggle optimism + revert), screen test for empty / loading /
  loaded / filter / toggle (Robolectric screenshot tests follow the
  existing feature-module pattern).
