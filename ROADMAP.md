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
6. **Roads, railways and buildings** from OpenStreetMap (OpenFreeMap vector tiles already carry
   `transportation` and `building` layers, with heights for many buildings).

## Known issues

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
