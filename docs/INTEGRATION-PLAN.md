# Integration with other mods: a plan

A proposal to discuss, not built yet. It asks what other mods could usefully get from Real Earth, and in what
form, so the first pieces are the ones that earn their keep.

## What Real Earth knows that others can't

- **Where on Earth a block is.** Latitude and longitude of any block, and back. The real elevation of a Y, and the
  Y of an elevation.
- **What is there on the map.** The town and district, the street, the building (its use, its shop and the
  shop's name, its floors), the land use, the climate, land cover and water.
- **What it built.** The rooms of a building, its beds, job blocks and loot containers, railway track and
  stations, bridges, tunnels, and parks and their furniture.

## Three layers, from cheapest to richest

### 1. Data, no code: datapacks and tags (partly there)

Mods and modpacks can already change loot (`realearth:chests/*`). The same approach could cover:

- **Block palettes** as block tags (`realearth:facade/brick`, `realearth:roof/slate`, `realearth:furniture/table`):
  a furniture mod (Macaw's, Handcrafted) or a building block mod puts its blocks in, and the buildings use them.
- **Landmarks** as structure files keyed by OpenStreetMap or Overture id (`data/<ns>/realearth_landmarks/*.json` →
  `.nbt`): a hand-made build replaces the generated one. This is the hook for real-looking landmarks that
  someone made by hand.
- **Shop stock and job blocks** per shop kind, as data.

### 2. A small Java API (`se.itssimple.realearth.api`)

A stable, versioned interface that other mods compile against with `modCompileOnly`. Real Earth then works with
them when it is present and nothing breaks when it isn't.

- `RealEarthApi.get(level)`: empty outside Earth.
- `toLatLon(BlockPos)`, `toBlock(lat, lon)`, `elevationMeters(y, lat)`.
- `placeAt(pos)`: town, district, street.
- `buildingAt(pos)`: use, shop name, floor, room kind.
- Events (Fabric `Event`s):
  - `TownEntered` (player, town, district);
  - `BuildingGenerated` (chunk, plan): lets a mod decorate rooms;
  - `ChunkTerrainReady`.

### 3. Built-in compatibility with mods people use (optional, each behind a check that the mod is loaded)

- **Xaero's minimap and world map:** lat/lon under the minimap, and waypoints for the town you walked into or the
  stations nearby.
- **Jade:** what you look at, with the building's name and use, the street, and lat/lon.
- **Distant Horizons:** already integrated (far terrain).
- **Lootr:** already works through loot tables.
- **JEI:** nothing needed.
- **Create:** its trains could run on our railways if track were laid as Create track. Big; only if wanted.

## Suggested order

1. The API's position queries and `TownEntered` (small, and the base for everything else).
2. Jade and Xaero's support, through the API, since they are in the usual pack.
3. Palette tags and the landmark structure hook.
4. `BuildingGenerated` for interior decorators.

## Questions to settle first

- Which of these matter for the packs you play or ship (Xaero's, Jade, Create, furniture mods)?
- Is a published API (a separate artifact on Maven, kept stable across versions) worth the upkeep, or are
  datapacks plus built-in compatibility enough?
- For hand-made landmarks: should Real Earth ship any, or only the hook?
