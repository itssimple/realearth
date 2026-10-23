# Changelog

## 0.2.0 (2026-10-23)

Towns that feel lived in, buildings that look like themselves, railways you can ride, tunnels under the ground,
and Real Earth in Jade and Xaero's Minimap.

### Buildings

- **Divided by what the map says they are**: blocks of flats in stairwell sections with flats and rooms off each
  landing, shops in ground-floor units, halls (churches, libraries, schools, markets), offices, warehouses, and
  houses. Turning stairs, hallways, a door in every wall, and every room can be walked to.
- **Furnished by room**: real beds in bedrooms, stoves, sinks and worktops in kitchens, tables, rugs,
  bookshelves, flower pots, pews in churches, shelves of stock in shops, crates in warehouses.
- **Loot**: house chests, pantries, storeroom barrels and shop stock have loot tables (`realearth:chests/*`),
  so Lootr gives each player their own. Bookshops sell maps of their own town.
- **Look like themselves**: walls and roofs in the colours the map gives them, roof shapes and heights, and towers,
  spires and domes raised from the map's building parts (OpenStreetMap's 3D tags, read from Overture Maps).
- **Signs** with the names of shops, halls and streets.

### Towns

- **Place names**: walking into a town shows its name as a title, with the district under it; a new district
  shows above the hotbar.
- **Villagers**: one moves into about one chunk of town in twenty; round each player more come home to beds out
  of sight (one per forty beds, up to `townsfolk`, 24), and go again once everyone is far away.
- **Street life from the map**: benches, bins, drinking water, bus and tram stops, statues, picnic tables,
  market stalls with a wandering trader, boats by marinas and ferry terminals, minecarts at stations.
- **Parks** with benches, lamps, flower beds and fountains; **cemeteries** with headstones, **allotments** with
  crops, **pitches** with their lines.
- No lava pools in town parks and yards; pillager outposts are kept out of towns more reliably (wherever their
  buildings are mapped), and appear elsewhere as usual.
- **Advancements for real places**: towns, capitals, the equator, the polar circles, the prime meridian, the date
  line, thin air, below the sea.

### Getting about

- **Bridges** as whole structures you can walk onto, with ramps up to them.
- **Railways you can ride**: continuous track, powered rails on slopes and along the way, through stations and
  tunnels.
- **Tunnels** for roads, paths and railways, dug under the ground: a stone-walled ramp down from each portal,
  then at least three blocks of ground over the roof, stone-lined and lit with lanterns. Branches and ramps that
  meet the main tunnel under the ground stay down there. Short ones (a way under a road) stay level.
- You start on dry land even when the start point is in a lake or the sea.

### Nature

- Fish spawn in lakes above sea level, not only in the sea.
- Slimes come from slime chunks deep underground, as in vanilla, not on the surface.

### Other mods

- **Jade**: under the block you look at, the building and room (a shop's name and what it sells, the storey),
  the street, district and town, and the latitude, longitude and real elevation.
- **Xaero's Minimap**: latitude, longitude and elevation, and the district and town, under the minimap; and
  waypoints for the towns round you and the nearest railway and metro stations (`mapWaypoints` turns them off).
- **An API for mod authors** (`se.itssimple.realearth.api`): positions both ways, elevation, place, street and
  building at a block, and a `TOWN_ENTERED` event.

### Performance and diagnostics

- A flight at glide speed no longer stalls the server: furnishing a big hall took over a minute and held up the
  chunks round it; it now takes a fraction of a millisecond.
- Each map tile or byte range is downloaded once at a time; anything else asking for it waits for that download.
- `/earth perf` shows downloads by source (how many, how fast, retries, rate limits, errors), how busy the
  download threads are, and how long chunks waited for their data.
- An optional running performance log (`perfLogSeconds`, or `/earth perf log <seconds>`) in
  `logs/realearth-perf.log`, with a note of what the server is doing whenever it stops ticking for five seconds.

### New settings in `config/realearth.json`

`placeTitles`, `townsfolk`, `mapWaypoints`, `perfLogSeconds`, and `useBuildingDetail` with its
`buildingDetailUrl`, `buildingDetailRelease` and `buildingDetailCatalog`.

### Data

- New source: building detail (roofs, colours, parts) from the **Overture Maps Foundation**'s buildings theme
  (ODbL; mostly OpenStreetMap), downloaded as needed and kept in `realearth-cache/overture`.

## 0.1.0 (2026-10-03)

First release: the real Earth at one block per metre as a world type or a dimension, with real elevation, water,
land cover and climate, roads and railways, buildings on their real footprints, Distant Horizons support and
offline play.
