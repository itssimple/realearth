# Real Earth roadmap

Proposed features and known issues, roughly in the order they'd be worth doing. Nothing here is started.

## Features

1. **Height cooling at map scale.** Biome temperature cools with height in *blocks* (`BiomeTemperatureMixin`).
   On the Mercator map a block is not a metre away from the scale latitude, so snow lines come out too low where
   the map is enlarged (Lapland) and too high where it is shrunk (Alps, Rockies). Divide by
   `Projection.blocksPerMeter(lat)`. On the client the latitude needs the world's projection, which
   multiplayer clients don't have yet; send it on join.
2. **High-resolution national elevation.** Lantmäteriet's 1 m terrain model for Sweden fixes Stockholm's flat
   26 m city patch (the AWS source is a surface model there). The car game (`D:/cloud/projects/car-game 5.8/`)
   already has `LidarSheetClient` and `HeightCog` for it. Same idea later for USGS 3DEP (1 m, USA), and
   other national LiDAR, merged over the Terrarium tiles where present.
3. **`/earth go <place name>`.** Place search through OpenStreetMap's Nominatim: cached, at most one request
   per second, with a User-Agent, as its usage policy requires. The car game has a client for it.
4. **Real day length and sun.** Sunrise and sunset from latitude and today's date: midnight sun in Kiruna in
   June, six-hour days in Stockholm in December. Optionally seasonal snow cover.
5. **Spawn safety.** If the start point is in the sea, spawn on the nearest land.
6. **Towns that feel lived in.** Done: real footprints as furnished village houses; house chests roll the
   village house loot of their climate, storeroom barrels `realearth:chests/storeroom`, so Lootr makes them
   per player; some furnaces keep fuel, some bookshelves are chiseled ones with books. Next, in this order:
   1. **What each building is.** OpenFreeMap's tiles also carry `poi` (shop, amenity, place of worship...) and
      `landuse` (residential, industrial, retail...) layers, not read yet. A point inside a footprint names
      the building: a bakery gets smokers and bread barrels and a butcher's loot, a library shelves, a lectern
      and a librarian, a church pews and a bell, a hardware shop a smithy; industrial land gets warehouses.
      Each type its own loot table. Signs by the door with the shop's real name.
   2. **Places from the map.** "Entering Stockholm" on screen at town edges (the `place` layer); street
      name signs at junctions; parks with fountains and benches, cemeteries, allotments with crops and
      animals, marketplaces with stalls and a wandering trader, stations with minecarts on the platform,
      harbours with boats; cartographers' chests holding a map of their own town.
   3. **More villagers, near the player.** Keep a number of villagers in the town around each player,
      instead of a few placed for good at generation; those far away and never traded with go. More beds
      per home, so cats come too; children; an iron golem at bell squares.
   4. **Rooms and details.** Interior walls into rooms, tables and chairs, carpets, kitchens; chimney smoke
      (a campfire under a chimney on some roofs); gardens, hedges and benches; trees along streets.
   Also landmarks, and buildings in Distant Horizons' far terrain.
7. **Villages that follow the streets**, where buildings aren't mapped: city villages are vanilla jigsaw villages
   on a grid; placing their houses along the real road network would fit better.
8. **Real places as advancements.** Visit a capital, stand on a country's highest point, cross the equator.

## Known issues

- **Humps under city bridges.** Where the elevation is a surface model (central Stockholm), a bridge is
  part of the "ground", so a street passing under it is measured at the bridge's height and the deck is
  lifted to clear it. A bare-earth model (feature 2) fixes it. A few steps of two blocks also remain where a
  ramp forks off a deck and climbs beside it, and between twin decks over water.
- **Doors you can walk round.** Some buildings get a door with no wall block beside it, so the door
  can be bypassed.
- **LOD land-cover patches.** Seen from high up, sparse forest (e.g. the Kaibab plateau by the Grand Canyon) shows
  faint rectangles of different green where Distant Horizons switches detail level: each level samples
  ESA WorldCover at its own resolution, so the share of tree pixels differs. Fix by taking the majority
  class over the column instead of the class at its centre.
- **Wrapped copies.** Flying round the globe reaches a second copy of each place at other coordinates;
  builds are not shared between copies.
- **Repaired chunks and structures.** A village crossing into a repaired chunk may not line up with its parts
  next door, since its layout follows the corrected terrain.
- **Snow on high forest.** Cold high forests (e.g. the Grand Canyon's South Rim at 2,100 m) get vanilla snow
  layers on steep stone faces. Will change with the height cooling fix above.
- **Separate caches.** A dedicated server and a client on the same machine each download their own data.
