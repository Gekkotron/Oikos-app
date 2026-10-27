# Automations Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a new "Automatisations" bottom tab that lists every Oikos-OS flow with a room filter and an enable/disable toggle.

**Architecture:** A new KMP module `core:flows` exposes `FlowsRepository` whose state is driven by retained MQTT messages on `oikos/automation/#` and whose only write path is `POST /api/v1/flows/{id}/enabled` via `LanOrGatewayGet`. A new Android module `feature:automations` renders the screen with the existing `RoomChips` composable, and the fourth tab is wired into `OikosNavHost` + `MainTabBar`, hidden under the Demo profile.

**Tech Stack:** Kotlin Multiplatform (commonMain for DTOs/interfaces, JVM for HTTP), Jetpack Compose, kotlinx-serialization, existing `LanOrGatewayGet` in `core:transport`, existing MQTT infrastructure in `core:source`.

**Spec:** `docs/superpowers/specs/2026-10-27-oikos-automations-tab-design.md`

## Global Constraints

- REST route is `POST /api/v1/flows/{id}/enabled` with body `{"enabled": bool}`. The `/api/v1` prefix is mandatory.
- Base URL for the hub is derived the same way `ProfileHistoryClient.effectiveHistoryUrl()` derives its own: broker host + port `8081`. No new field on `ConnectionProfile`.
- MQTT subscription is `oikos/automation/#`; each retained message `oikos/automation/<id>` carries a full `Flow` JSON. An empty retained payload on that topic means the flow was deleted.
- Only HTTP primitive is `LanOrGatewayGet` (GET + JSON POST). No WebSocket client, no new HTTP library.
- Tab order: Accueil, Énergie, Sécurité, **Automatisations** (last). FR label `Automatisations`, EN label `Automations`.
- The Automations tab is hidden under the Demo profile. No fake flow data.
- Zero changes to the Oikos-OS repository.
- Non-UI code lives in `commonMain` under `core:flows` (`oikos.kmp.jvm.library`); the screen stays Android in `feature:automations` (`oikos.android.feature`).

## Review Focus

- **Toggle in-flight when the retained MQTT echo lands.** The echo must reconcile the switch to the server-side truth without flashing or dropping the user's intent. Covered by Task 4 ViewModel tests.
- **Flow whose `area` id is not in `EntitySource.areas`.** Must group under "Non assignées" (same bucket as `area: null`), never crash. Covered by Task 4 ViewModel tests.
- **Rapid toggle presses on the same row.** While a POST is in flight the switch is latched to its optimistic value and further presses are ignored. Covered by Task 4 ViewModel tests.
- **Malformed JSON on `oikos/automation/<id>`.** The subscription keeps running; the bad message is dropped (and logged at `warn`). Covered by Task 3 sink tests.
- **Toggle while offline (no broker link).** The switch is disabled; no POST is attempted. Covered by Task 4 ViewModel tests and Task 5 screen tests.

---

### Task 1: `core:flows` module — DTO, client interface, repository

**Files:**
- Create: `core/flows/build.gradle.kts` (uses `oikos.kmp.jvm.library`)
- Create: `core/flows/src/commonMain/kotlin/com/gekkotron/oikos/flows/FlowDto.kt`
- Create: `core/flows/src/commonMain/kotlin/com/gekkotron/oikos/flows/FlowsClient.kt`
- Create: `core/flows/src/commonMain/kotlin/com/gekkotron/oikos/flows/FlowsRepository.kt`
- Create: `core/flows/src/commonMain/kotlin/com/gekkotron/oikos/flows/FlowsSink.kt`
- Test: `core/flows/src/commonTest/kotlin/com/gekkotron/oikos/flows/FlowDtoParseTest.kt`
- Test: `core/flows/src/commonTest/kotlin/com/gekkotron/oikos/flows/FlowsRepositoryTest.kt`
- Modify: `settings.gradle.kts` — `include(":core:flows")`

**Interfaces:**
- Consumes: nothing (new module).
- Produces:
  - `@Serializable data class FlowDto(val id: String, val name: String, val area: String? = null, val enabled: Boolean = true)` (ignore the fields the UI doesn't read; `@Serializable(ignoreUnknownKeys = true)` on the serializer).
  - `interface FlowsClient { suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> }`.
  - `interface FlowsSink { fun onRetained(id: String, payload: String?) }` — `payload == null` or empty means delete.
  - `class FlowsRepository(client: FlowsClient, clock: TimeSource = TimeSource.Monotonic, settleAfter: Duration = 2.seconds) : FlowsSink` exposing `val flows: StateFlow<FlowsSnapshot>` where `data class FlowsSnapshot(val isSettled: Boolean, val flows: List<FlowDto>)`, and `suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit>` that optimistically updates `flows`, calls `client.setEnabled`, and reverts on failure.
  - `fun FlowsRepository.markLinkUp()` — call when the MQTT link goes up; starts the settle timer.

- [ ] **Step 1: Write the failing tests**

```kotlin
// FlowDtoParseTest
class FlowDtoParseTest {
  @Test fun `parses minimal flow with defaults`() {
    val dto = flowsJson.decodeFromString<FlowDto>("""{"id":"a","name":"Morning"}""")
    assertEquals(FlowDto(id = "a", name = "Morning", area = null, enabled = true), dto)
  }
  @Test fun `parses full flow and ignores unknown fields`() {
    val dto = flowsJson.decodeFromString<FlowDto>(
      """{"id":"b","name":"Night","area":"bedroom","enabled":false,"description":"x","mode":"single","nodes":[],"edges":[]}"""
    )
    assertEquals(FlowDto("b", "Night", "bedroom", false), dto)
  }
}

// FlowsRepositoryTest
class FlowsRepositoryTest {
  @Test fun `retained message adds a flow`() { … }
  @Test fun `empty payload deletes the flow`() { … }
  @Test fun `malformed payload is dropped and does not affect state`() { … }
  @Test fun `setEnabled optimistically updates then confirms on success`() { … }
  @Test fun `setEnabled reverts on client failure`() { … }
  @Test fun `in-flight toggle ignores further setEnabled on the same id`() { … }
  @Test fun `retained echo during in-flight toggle wins once request completes`() { … }
  @Test fun `isSettled becomes true after settleAfter when no messages arrive`() { … }
  @Test fun `isSettled becomes true immediately on first retained message`() { … }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:flows:jvmTest`
Expected: FAIL — module or symbols not defined.

- [ ] **Step 3: Scaffold the module**

Create `core/flows/build.gradle.kts` with `plugins { alias(libs.plugins.oikos.kmp.jvm.library); alias(libs.plugins.kotlinx.serialization) }`, dependency on `libs.kotlinx.coroutines.core` and `libs.kotlinx.serialization.json`. Add `include(":core:flows")` to `settings.gradle.kts`.

- [ ] **Step 4: Implement `FlowDto`, `FlowsClient`, `FlowsSink`, `FlowsRepository`**

- `FlowDto`: `@Serializable` data class as above; expose a module-level `val flowsJson = Json { ignoreUnknownKeys = true; isLenient = false }`.
- `FlowsRepository`: holds `MutableStateFlow<FlowsSnapshot>` keyed by `id`; `onRetained(id, null|"")` removes; valid payloads are `flowsJson.decodeFromString<FlowDto>` and upserted; failures are caught, logged (`println` is fine in commonMain for now), state unchanged. `setEnabled` records an in-flight id, applies the optimistic flip, awaits `client.setEnabled`, reverts on failure, then clears the in-flight marker; while in flight, concurrent `setEnabled` calls for the same id return `Result.success(Unit)` without a request. The settle timer is a `Job` started by `markLinkUp()` that flips `isSettled = true` after `settleAfter` unless the first retained message already did.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:flows:jvmTest`
Expected: PASS, all cases.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts core/flows
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(flows): add core:flows KMP module with repository and DTO"
```

---

### Task 2: `FlowsClient` HTTP implementation over `LanOrGatewayGet`

**Files:**
- Create: `core/flows/src/jvmMain/kotlin/com/gekkotron/oikos/flows/HttpFlowsClient.kt`
- Test: `core/flows/src/jvmTest/kotlin/com/gekkotron/oikos/flows/HttpFlowsClientTest.kt`
- Modify: `core/flows/build.gradle.kts` — add `jvmMain` dependency on `project(":core:transport")`.

**Interfaces:**
- Consumes: `FlowsClient` from Task 1, `LanOrGatewayGet` from `core:transport`, `HttpRoutes` provider from `core:transport`.
- Produces: `class HttpFlowsClient(private val transport: LanOrGatewayGet) : FlowsClient` that POSTs `/api/v1/flows/{id}/enabled` with body `{"enabled": <bool>}` and maps a non-2xx response to `Result.failure(FlowsHttpError(status, body))`.

- [ ] **Step 1: Write the failing test**

```kotlin
class HttpFlowsClientTest {
  @Test fun `setEnabled posts to the correct path with json body`() { … }
  @Test fun `setEnabled returns failure on 4xx`() { … }
  @Test fun `setEnabled returns failure on transport exception`() { … }
}
```
Use a fake `LanOrGatewayGet` that records the last `(path, body)` and returns a canned response.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:flows:jvmTest --tests "*HttpFlowsClientTest*"`
Expected: FAIL — class not defined.

- [ ] **Step 3: Implement `HttpFlowsClient`**

Serialise `mapOf("enabled" to enabled)` with `flowsJson`, call `transport.post(path = "/api/v1/flows/$id/enabled", contentType = "application/json", body = serializedBody)`, return `Result.success(Unit)` on 2xx, else `Result.failure(FlowsHttpError(status, body))`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:flows:jvmTest --tests "*HttpFlowsClientTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/flows
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(flows): HTTP FlowsClient over LanOrGatewayGet"
```

---

### Task 3: MQTT wiring — route `oikos/automation/#` into `FlowsSink`

**Files:**
- Modify: `core/source/src/jvmMain/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt` — accept an optional `flowsSink: FlowsSink?` constructor param; in `handle(topic, payload)`, when `topic.startsWith("oikos/automation/") && topic.count('/') == 2`, call `flowsSink?.onRetained(id = topic.substringAfterLast('/'), payload = payloadString)`. In `onLinkUp()` (or equivalent), also invoke `flowsSink?.markLinkUp()`.
- Modify: `core/source/build.gradle.kts` — add `commonMain` dependency on `project(":core:flows")`.
- Test: `core/source/src/jvmTest/kotlin/com/gekkotron/oikos/source/MqttDiscoverySourceFlowsTest.kt`

**Interfaces:**
- Consumes: `FlowsSink` from Task 1.
- Produces: existing `MqttDiscoverySource` gains the flows-routing branch; no new public type.

- [ ] **Step 1: Write the failing test**

```kotlin
class MqttDiscoverySourceFlowsTest {
  @Test fun `retained flow topic forwards to FlowsSink`() { … }
  @Test fun `empty payload forwards null to FlowsSink`() { … }
  @Test fun `sub-topic deeper than id is not forwarded`() { … } // e.g. oikos/automation/abc/state is ignored
  @Test fun `null sink is a no-op`() { … }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:source:jvmTest --tests "*MqttDiscoverySourceFlowsTest*"`
Expected: FAIL.

- [ ] **Step 3: Add the routing branch and `markLinkUp` hook**

Minimal edit to `handle()`; thread `flowsSink` through the constructor with a default of `null` to avoid breaking existing call sites.

- [ ] **Step 4: Run the full `:core:source` test suite**

Run: `./gradlew :core:source:jvmTest`
Expected: PASS, including new tests and all prior ones.

- [ ] **Step 5: Commit**

```bash
git add core/source core/flows
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(flows): route oikos/automation/# to FlowsSink"
```

---

### Task 4: `feature:automations` — ViewModel + UI state

**Files:**
- Create: `feature/automations/build.gradle.kts` (uses `oikos.android.feature`)
- Create: `feature/automations/src/main/kotlin/com/gekkotron/oikos/feature/automations/AutomationsUiState.kt`
- Create: `feature/automations/src/main/kotlin/com/gekkotron/oikos/feature/automations/AutomationsViewModel.kt`
- Test: `feature/automations/src/test/kotlin/com/gekkotron/oikos/feature/automations/AutomationsViewModelTest.kt`
- Modify: `settings.gradle.kts` — `include(":feature:automations")`

**Interfaces:**
- Consumes: `FlowsRepository` from Task 1, `EntitySource.areas: StateFlow<List<Area>>` from `core:source`, `Area` + `UNASSIGNED_AREA` from `core:model`.
- Produces:
  - `data class AutomationsUiState(val isLoading: Boolean, val isOffline: Boolean, val chips: List<ChipItem>, val selectedChipKey: String, val rows: List<AutomationRow>, val toast: String?)`
  - `data class AutomationRow(val id: String, val name: String, val areaLabel: String?, val enabled: Boolean, val toggleEnabled: Boolean)` — `areaLabel` is non-null only when the "All" chip is selected.
  - `class AutomationsViewModel(repo: FlowsRepository, areas: StateFlow<List<Area>>, linkState: StateFlow<Boolean>)` with `val state: StateFlow<AutomationsUiState>`, `fun onChipSelected(key: String)`, `fun onToggle(id: String, enabled: Boolean)`, `fun onToastShown()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class AutomationsViewModelTest {
  @Test fun `loading state before settle`() { … }
  @Test fun `empty state after settle with no flows`() { … }
  @Test fun `rows grouped by area when All chip selected`() { … }
  @Test fun `rows for a selected area hide the area label`() { … }
  @Test fun `flow with unknown area id falls into Unassigned bucket`() { … }
  @Test fun `flow with area=null falls into Unassigned bucket`() { … }
  @Test fun `offline state disables toggles and surfaces the offline banner`() { … }
  @Test fun `toggle flips optimistically and clears when the repo succeeds`() { … }
  @Test fun `toggle reverts and sets toast when the repo fails`() { … }
  @Test fun `rapid toggle presses on the same row are ignored while in-flight`() { … }
  @Test fun `retained echo during in-flight toggle resolves to server truth`() { … }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :feature:automations:testDebugUnitTest`
Expected: FAIL.

- [ ] **Step 3: Implement the ViewModel**

Combine `repo.flows`, `areas`, `linkState`, and an internal `selectedChipKey` into `AutomationsUiState`. Chip list = `[ChipItem("all", R.string.automations_chip_all)] + areas.map { ChipItem(it.id, text = it.displayName) } + if (hasUnassigned) listOf(ChipItem("unassigned", R.string.automations_chip_unassigned)) else emptyList()`. `onToggle` delegates straight to `repo.setEnabled` and surfaces a toast string on failure. `toggleEnabled = linkState.value && !repoReportsInFlight(id)`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:automations:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts feature/automations
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(automations): ViewModel and UI state model"
```

---

### Task 5: `AutomationsScreen` composable + `AutomationsRoute`

**Files:**
- Create: `feature/automations/src/main/kotlin/com/gekkotron/oikos/feature/automations/AutomationsScreen.kt`
- Create: `feature/automations/src/main/kotlin/com/gekkotron/oikos/feature/automations/AutomationsRoute.kt`
- Test: `feature/automations/src/test/kotlin/com/gekkotron/oikos/feature/automations/AutomationsScreenTest.kt` (Robolectric screenshot, following the existing feature-module pattern)

**Interfaces:**
- Consumes: `AutomationsUiState` + ViewModel callbacks from Task 4, `RoomChips` from `core:designsystem`.
- Produces:
  - `@Composable fun AutomationsScreen(state: AutomationsUiState, onChipSelected: (String) -> Unit, onToggle: (String, Boolean) -> Unit, onToastShown: () -> Unit)`
  - `@Composable fun AutomationsRoute(viewModel: AutomationsViewModel = hiltViewModel())`

- [ ] **Step 1: Write the failing screen tests**

```kotlin
class AutomationsScreenTest {
  @Test fun `shimmer when loading`() { … }
  @Test fun `empty state copy when settled with no flows`() { … }
  @Test fun `renders chips and rows when loaded`() { … }
  @Test fun `selected chip filters rows`() { … }
  @Test fun `offline banner shows and toggles are disabled`() { … }
  @Test fun `toggle callback is invoked on switch click`() { … }
}
```
Use the existing Robolectric screenshot test harness for feature modules.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :feature:automations:testDebugUnitTest --tests "*AutomationsScreenTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement the screen**

Scaffold with top bar titled `R.string.automations_title`, `RoomChips` under the top bar, then a `LazyColumn` of rows. Each row is a `ListItem`-like composable with the flow name on the leading side, an area label as supporting text when `areaLabel != null`, and a `Switch(checked = row.enabled, enabled = row.toggleEnabled, onCheckedChange = { onToggle(row.id, it) })` on the trailing side. Empty state: centered column with `R.string.automations_empty_title` + `R.string.automations_empty_body`. Loading: shimmer rows. Offline: muted banner above the chips carrying `R.string.automations_offline_banner`. Toast is shown via `SnackbarHost` and dismissed via `onToastShown`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:automations:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/automations
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(automations): screen composable and route"
```

---

### Task 6: Navigation, tab bar, icon, strings, DI, Demo gate

**Files:**
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt` — add `@Serializable data object AutomationsKey : NavKey` next to the existing keys; add `entry<AutomationsKey> { AutomationsRoute() }` in the `NavDisplay` block.
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/MainTabs.kt` — add `AutomationsKey` to `SIDE_TABS`; add the fourth `NavigationBarItem` to `MainTabBar` **after** the Security item; make the tab bar conditional on the current profile (`showsTabBar()` + a `showAutomationsTab(profile)` helper that returns `false` for `ConnectionProfile.Demo`).
- Modify: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/StaticIcon.kt` — add `OikosGlyph.AUTOMATION` with an SVG path matching the existing stroke style; wand-and-spark is the suggested motif.
- Modify: `app/src/main/res/values/strings.xml` and `app/src/main/res/values-fr/strings.xml` (and any other locale copies that already exist for `nav_home`): add
  - `nav_automations`: EN "Automations" / FR "Automatisations"
  - `automations_title`: EN "Automations" / FR "Automatisations"
  - `automations_chip_all`: EN "All" / FR "Toutes"
  - `automations_chip_unassigned`: EN "Unassigned" / FR "Non assignées"
  - `automations_empty_title`: EN "No automations yet" / FR "Aucune automatisation"
  - `automations_empty_body`: EN "Create one from the Oikos-OS web interface." / FR "Créez-en une depuis l'interface web d'Oikos-OS."
  - `automations_offline_banner`: EN "Offline — state is frozen" / FR "Hors ligne — état figé"
  - `automations_toggle_failed`: EN "Could not update the automation" / FR "Impossible de modifier l'automatisation"
- Modify: DI wiring (whichever module currently binds `MqttDiscoverySource` and the HTTP transport) — provide `FlowsRepository` as a singleton, bind `HttpFlowsClient`, pass the repository as the `flowsSink` into `MqttDiscoverySource`.
- Modify: `app/build.gradle.kts` — add `implementation(project(":core:flows"))` and `implementation(project(":feature:automations"))`.
- Test: `app/src/test/kotlin/com/gekkotron/oikos/navigation/AutomationsTabVisibilityTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: no new types; the tab is now visible (and hidden under Demo), and tapping it launches `AutomationsRoute`.

- [ ] **Step 1: Write the failing visibility test**

```kotlin
class AutomationsTabVisibilityTest {
  @Test fun `MainTabBar shows Automations tab for Direct profile`() { … }
  @Test fun `MainTabBar hides Automations tab for Demo profile`() { … }
  @Test fun `tab bar order is Home, Energy, Security, Automations`() { … }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*AutomationsTabVisibilityTest*"`
Expected: FAIL — tab not present.

- [ ] **Step 3: Wire the key, entry, tab, icon, strings, DI, Demo gate**

Follow the "Files" list above. Make a single commit's worth of edits.

- [ ] **Step 4: Run the visibility test and the full `:app` unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Build the APK to catch any wiring regression**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app core/designsystem settings.gradle.kts
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(automations): wire Automatisations tab, icon, strings, DI"
```

---

## Self-review

- **Spec coverage.** Tab bar entry → Task 6. List from MQTT retained → Tasks 1 + 3. Toggle via REST → Tasks 1 + 2. Live updates → Tasks 1 + 3. Room filter chips → Tasks 4 + 5. Empty / loading / offline / toggle-failure states → Tasks 4 + 5. KMP `core:flows` + Android `feature:automations` module split → Tasks 1 + 4. Hide under Demo → Task 6. `/api/v1` prefix + base URL derivation → Task 2. Nothing uncovered.
- **Step scan.** Every step picks one action with a checkable outcome. Code steps give signatures and files, not bodies, except the `handle()` branch in Task 3 which needs the exact topic predicate.
- **Type consistency.** `FlowDto`, `FlowsClient`, `FlowsSink`, `FlowsRepository`, `FlowsSnapshot`, `AutomationsUiState`, `AutomationRow`, `AutomationsViewModel`, `AutomationsScreen`, `AutomationsRoute`, `AutomationsKey`, `OikosGlyph.AUTOMATION` — each name is defined once and reused with the same signature downstream.
- **Review Focus coverage.** All five items have a named test in the owning task.
- **Proportion.** The plan is shorter than the implementation it describes; test blocks list names + the one or two assertions the names don't carry, not full bodies.
