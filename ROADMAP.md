# Real Earth roadmap

Proposed features and known issues, roughly in the order they'd be worth doing. Done parts are noted where a feature is under way.

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
6. **Towns that feel lived in.** Done: loot in chests, pantries and shop stock, Lootr-ready; buildings divided
   by what the map says they are (flats in stairwell sections with rooms, shops in ground-floor units, halls,
   offices, warehouses, houses), with turning stairs, a door in every wall and furniture by room; place
   names on screen, street and shop signs; villagers kept round each player. Next:
   1. **Places from the map.** Parks with fountains and benches,
      cemeteries, allotments with crops and animals, marketplaces with stalls and a wandering trader,
      stations with minecarts on the platform, harbours with boats; cartographers' chests holding a map of
      their own town.
   2. **More villagers.** An iron golem at bell squares; villagers walking the streets by day rather than
      only round their homes.
   3. **Rooms and details.** Chairs at tables, chimney smoke (a campfire under a chimney on some roofs),
      gardens, hedges and benches, trees along streets; shops on upper floors where the map gives a level.
   4. **Interiors that follow the building.** Layouts are on the block grid, so a building at an angle has
      stepped inner walls. Stairwells reach the street through a hallway there, so only the smallest
      houses have ladders; laying out along the building's own axis would straighten the walls too.
   5. **Buildings that look like themselves.** Done: map colours, raised parts as towers, roof shapes, heights
      and colours from OpenStreetMap's 3D tags (via Overture's tiles). Next: overhangs and arcades (parts that
      start low), roof direction for skillions, and a datapack hook for hand-made landmark builds.
   Also buildings in Distant Horizons' far terrain.
7. **Villages that follow the streets**, where buildings aren't mapped: city villages are vanilla jigsaw villages
   on a grid; placing their houses along the real road network would fit better.
8. **Real places as advancements.** Done: towns, capitals, the equator, polar circles, prime meridian, date
   line, thin air, below the sea. Next: a country's highest point, visiting ten towns, every continent.

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
