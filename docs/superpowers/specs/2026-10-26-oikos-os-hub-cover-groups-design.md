# Oikos-OS hub cover groups in the app — design (2026-10-26)

Oikos-OS now owns group definitions and publishes each group as a Home Assistant discovery entity under its `oikos/` prefix. The app already subscribes to that prefix (master `8374930`) and `CoverMapper` already understands the JSON state/position/command shape the hub uses. What is missing is the UX: a hub cover group currently shows up as a plain narrow shutter tile beside its still-visible members. This spec covers how to fold the hub-published cover group into the same wide group card the client-side `RoomGroups.fold` produces and hide the members, so the user sees one group ribbon per hub-defined cover group.

Scope is covers only. Light, climate, switch and other hub group kinds use the same hub plumbing and will follow the same approach, but shipping them is out of this spec.

## 1. Hub contract (recap)

What the hub publishes, from `~/superconductor/projects/Oikos-os/crates/oikos-os/src/groups/`:

- `oikos/cover/group/<id>/config` — HA-discovery config:
  - `unique_id = group_<id>`
  - `device = { identifiers: ["oikos_group"], name: "Groups" }`
  - `state_topic = command_topic = oikos/group/<id>` (JSON)
  - `value_template = {{ value_json.state }}`
  - `position_topic = set_position_topic = oikos/group/<id>` when every member has `position` capability
  - `position_template = {{ value_json.position }}`
  - `payload_open / close / stop` are JSON blobs (`{"type":"open"}` etc.) — the mapper publishes them verbatim; the hub parses them
  - `availability_topic = oikos/group/<id>/avail`, `payload_available = "online"`
- `oikos/group/<id>/definition` — retained JSON `GroupSpec`:
  ```json
  { "name": "Living shutters", "kind": "cover",
    "members": ["cover.shutter_a", "cover.shutter_b"],
    "hideMembers": true }
  ```
- No per-group area is published. The app derives the group's area from its members (all-same → that area, else `UNASSIGNED_AREA`).

## 2. App changes

### 2.1 Model

`core/model/.../HubGroup.kt` (new):

```kotlin
const val HUB_GROUP_DEVICE_ID = "oikos_group"

@Serializable data class HubGroup(
    val id: String,
    val kind: Kind,
    val name: String,
    val members: List<String>,
    val hideMembers: Boolean = true,
)
```

### 2.2 Source

`core/source/.../discovery/HubGroupDefinitions.kt` (new): one function `parse(id, payload) -> HubGroup?`, using the GroupSpec JSON above. Unknown fields are allowed (schema may grow); a missing or malformed payload returns null and the diagnostic reports it.

`MqttDiscoverySource`:
- A new `_hubGroups: MutableStateFlow<Map<String, HubGroup>>` and a new `onHubGroupDefinition(id, payload)` branch keyed by `topic.startsWith("oikos/group/") && topic.endsWith("/definition")`.
- Empty payload removes the entry.
- `EntitySource` grows `val hubGroups: StateFlow<Map<String, HubGroup>>`. No commands flow through it — it is read-only presentation state.

The entity itself (from the config topic) already lands in `entities` through `DiscoveryParser` because its component is `cover`.

### 2.3 Home builder

`HomeUiBuilder.build()` takes a new `hubGroups: Map<String, HubGroup>` parameter (default `emptyMap()` keeps older callers working). Fold happens in a new `RoomGroups.foldHubGroups` run **before** the existing client-side `fold`:

1. For each hub group whose kind is `COVER` and whose group entity is in `entities`:
   - Collect the member `CardUi`s (those whose `id` is in `members` and that would otherwise appear on Home).
   - Replace the group entity's `CardUi` with a wide `CardUi.group(area, Kind.COVER, members, name, favorite)` reusing the current room-group factory. Area = common member area or `UNASSIGNED_AREA`.
   - Remove the member cards from the grid.
   - Mark the new card `isHubGroup = true`.
2. The client-side `RoomGroups.fold` then runs on what remains. Members already covered by a hub group are gone, so there is no double-fold.

`CardUi` grows `val isHubGroup: Boolean = false` (defaulted so existing group tests stay valid).

### 2.4 Command routing

- `GroupCommands.targets(group, command)`: when `group.isHubGroup`, return `listOf(group.id)` directly, no capability filtering — the hub handles capability intersection when it builds the discovery config.
- `HomeViewModel.sendGroup`: unchanged. One command to the group id reaches `MqttDiscoverySource.send`, which encodes with `CoverMapper` through the group entity's bindings and publishes the hub-authored payloads.
- The retry UX (`groupSends`) keeps working: a rejection returns under `card.id` and the user retries once. We lose per-member retry granularity for hub groups; the hub owns fanout.

## 3. Interaction with client-side room groups

Both systems coexist. A member that belongs to a hub group is removed before `RoomGroups.fold` sees it, so client-side folding only applies to members the hub does not cover — the migration state the user picked at the hub decides which shutters are hub-owned. Nothing in the client fold needs to know about hub groups; the hide step is enough.

Favourites: a hub group card is favorite-eligible via its own `UiOverride.favorite` (through the group entity's override topic `oikos/ui/group_<id>`). The per-area `groupFavorites` map keeps serving client-side folds only.

## 4. Non-goals

- Non-cover hub groups (light, climate, switch, water_heater, vacuum, number, select, button). Same approach; separate spec when the user turns them on.
- Area publication from the hub. If the hub later publishes a per-group area, the app will prefer it over the member-derived one; shipping without it is fine.
- Per-member retry on a hub group. The hub runs the fanout.
- Group creation / editing from the app. The hub owns the registry; the app only renders.

## 5. Tests

- `HubGroupDefinitionsTest`: good payload → `HubGroup`; empty → null; unknown fields ignored; missing fields → null.
- `MqttDiscoverySourceTest`: a definition payload lands in `hubGroups`; an empty payload removes it; the cover config entity still surfaces.
- `HomeUiBuilderTest`: a hub cover group with two members produces one wide `CardUi` and no member tiles; shared area applies; mismatched areas → `UNASSIGNED_AREA`.
- `GroupCommandsTest`: `targets` on a hub group returns `[groupId]` regardless of member capabilities.

## 6. Follow-ups

- Light / switch / climate / vacuum hub groups. The home builder's `foldHubGroups` already handles any kind; the UX reuse step needs the matching `CardValue` for each.
- Hub-published area field, when it exists.
- Per-member partial failure surface (the hub exposes it on `oikos/group/<id>/avail` or similar — not yet wired).
