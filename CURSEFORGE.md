# Real Earth

**Play on the real Earth, at one block per metre.** Real Earth builds Minecraft terrain from open data about the
actual planet: real mountains and valleys, real lakes, rivers and coastlines, real forests and farmland, real
roads and railways, and real towns and cities with their buildings.

Start in your own street, fly to the nearest town centre, or teleport anywhere on the planet.

## Features

- **Real terrain** from elevation data at about 3–5 m resolution, smoothed so it looks natural, with heights
  true to scale up to 2,800 m (higher peaks are gently compressed so Everest fits).
- **One continuous map of the whole planet** (Mercator, like a web map): north is up everywhere, shapes are true
  everywhere, and one block is one metre at the latitude you start at.
- **Real water**: seas, lakes, rivers, canals and streams from OpenStreetMap.
- **Real land cover and climate**: forests, grassland, farmland, wetlands, deserts, snow and ice, with biomes and
  surfaces chosen by the real climate. Red banded rock in dry canyons, sandstone in deserts.
- **Roads, paths and railways**: bridges you can walk onto, railways you can ride (continuous, powered track,
  through stations), and tunnels dug under the ground, stone-lined and lit.
- **Real buildings** on their real footprints, in the colours, roof shapes and heights the map gives them, with
  towers, spires and domes where they are mapped. Storeys of at least 4.5 m (up to 10).
- **Buildings you can go into**: blocks of flats with stairwells and flats, shops in ground-floor units, halls
  (churches, libraries, schools, markets), offices, warehouses and houses, every room furnished and reachable:
  real beds, kitchens, bookshelves, shelves of stock, pews. Chests, pantries and shop stock carry loot tables
  (Lootr-ready); bookshops sell maps of their own town. Signs name the shops, halls and streets.
- **Towns that feel lived in**: the town and district you walk into on screen; villagers who live in the
  buildings and come and go round you; benches, bins, bus stops, statues, market stalls, boats by the marinas and
  minecarts at the stations, where the map has them; parks with fountains, cemeteries, allotments, pitches.
- **Advancements for real places**: towns, capitals, the equator, the polar circles, the prime meridian, the date
  line, thin air, below the sea.
- **Vanilla underneath**: caves, ores at their usual depths, and all structures: villages where no buildings are
  mapped, mineshafts, ancient cities, ocean monuments and more.
- **Distant Horizons support**: distant terrain is drawn straight from the data, so you can see mountains tens of
  kilometres away.
- **Jade and Xaero's Minimap support**: the building, room, street, town, latitude and longitude of the block you
  look at; latitude, longitude and town under the minimap, and waypoints for the towns and stations round you.
- **An API** for other mods: positions both ways, the place, street and building at a block, and a town-entered
  event.
- **Works offline** for places already visited or downloaded in advance (`/earth download`), with a size-capped
  cache and a repair command for terrain generated while the data couldn't be downloaded.

## Two ways to play

- **Real Earth world type**: choose *Real Earth* as the World Type when creating a world. The Overworld is the
  real Earth, and you start in Stockholm unless you change `startLatitude`/`startLongitude` in
  `config/realearth.json` before creating the world.
- **Earth as a dimension** in a normal world: build a nether-portal-shaped frame of **lapis lazuli blocks** and
  right-click it with a **Globe Banner Pattern** (from a cartographer villager).

## Commands

- `/earth where`: your real latitude, longitude and elevation, with a map link
- `/earth tp <latitude> <longitude>`: go anywhere (operators)
- `/earth download <radius>`: download an area for offline play (operators)
- `/earth repair`, `/earth cache`: maintenance (operators)
- `/earth perf`, `/earth perf log <seconds>`: generation times, downloads and waits, and a running performance
  log in `logs/realearth-perf.log` (operators)
- `/earth credits`: the data sources

## Requirements

- Minecraft **26.1**, Fabric Loader 0.19.5+, Fabric API, Java 25
- Install on **both client and server**
- Recommended: **Distant Horizons** 3.3+ (at one block per metre, vanilla render distance shows only a few
  hundred metres)
- Optional, supported when installed: **Jade**, **Xaero's Minimap**, **Lootr**
- An **internet connection** the first time you visit a place (see below)

## Internet use and privacy

Real Earth downloads public map data while you play, from these services only:

- elevation tiles from the Terrain Tiles dataset on Amazon Web Services' open data registry,
- map tiles (water, roads, railways, buildings, places) from OpenFreeMap,
- building detail (roofs, colours, building parts) from the Overture Maps Foundation's buildings release on
  Amazon S3, and its release catalogue,
- land cover tiles from the European Space Agency's WorldCover dataset on Amazon S3.

Only map tiles are requested; no information about you, your world or your computer is sent, and there is no
telemetry. Downloads are kept in `realearth-cache/` in the game folder (8 GB at most by default, configurable)
and reused, so places you have been work offline. The climate map is bundled.

## Good to know

- Generation in big cities costs more than countryside; first visits wait for downloads.
- Buildings follow their real footprints, heights, roofs and colours, but are not replicas: walls, windows and
  rooms are laid out by the mod, not taken from the real building.
- Every bed is a real bed, so a city has many block entities; a culling mod such as Entity Culling may help frame
  rates there.
- Scale is exact only at the start latitude: places nearer the equator come out smaller, places nearer the poles
  larger.
- The elevation data is coarse in places (central Stockholm is unusually flat, for example).

## About how this mod was made

Real Earth was written almost entirely by an AI, **Claude (by Anthropic)**, working in Claude Code at my direction.
I want to be upfront about that.

**What I did:** came up with the idea and decided what the mod should be and do, chose between the options
offered (the Mercator map, the world type, real buildings instead of villages, and so on), set the requirements
(one block per metre, storeys of at least 4.5 m, up to 10 floors, credits for every data source), play-tested it,
found problems in game and reported them, and decided when it was ready to release.

**What Claude did:** wrote the code, the data handling, the self-tests and the documentation (including this
page), researched the data sources and their licences, ran the automated tests and screenshot checks, and fixed
the bugs that turned up, including the ones I found while playing.

I have not reviewed the code line by line. It is tested by its own automated self-tests and by play-testing,
but treat it as the beta it is, and please report anything that looks wrong.

## Credits

Map data © OpenStreetMap contributors (ODbL), served by OpenFreeMap (© OpenMapTiles). Building detail: Overture
Maps Foundation, buildings theme (ODbL), mostly from OpenStreetMap. Elevation: Terrain Tiles
on AWS (Mapzen), from USGS, NOAA, Copernicus EU-DEM and national agencies. Land cover: © ESA WorldCover project
2021, contains modified Copernicus Sentinel data (CC BY 4.0). Climate: Beck et al. (2023), Köppen-Geiger maps
(CC BY 4.0). Full credits in CREDITS.md in the mod's jar, or in game with `/earth credits`.
