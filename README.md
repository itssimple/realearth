# Real Earth

A Minecraft **26.1** (Fabric) mod that adds an **Earth dimension**, generated from real-world elevation data at
**one block per metre** (true to scale where you start) instead of Perlin noise.

- Elevation: AWS Terrain Tiles (Terrarium encoding) at **zoom 15**, the highest the service serves (z16 is 404).
  Sample spacing is 4.8 m at the equator and 2.4 m at 60°N, so it is interpolated up to 1 m blocks.
- The whole planet is **one continuous Mercator map**, like a web map: north is up everywhere and every place has
  its true shape. One block is exactly one metre at the start point's latitude (see *The map* below).
- Vertical: heights follow the same scale, so mountains keep their true proportions. Up to 2,800 blocks above
  sea level the vertical scale is linear; higher than that, mountains are eased smoothly so Everest still fits
  under the build limit (details below).
- Water from **OpenStreetMap**, land cover from **ESA WorldCover** (10 m), climate from the **Köppen-Geiger** map
  (1 km). Optional **Distant Horizons** support draws distant terrain straight from the data.

## The map

A round planet can't be drawn flat without distorting something. Real Earth uses the **Mercator projection**,
the projection of web maps and of the elevation tiles themselves, with 0°, 0° at block 0, 0:

- **North is up everywhere, and shapes are true everywhere.** The Grand Canyon, Japan or the Alps look the same
  whether you start in Stockholm or next to them. One map, no seams: you can fly from Stockholm to Arizona.
- **Scale changes with latitude.** One block is exactly one metre at the start latitude (`scaleLatitude`).
  Further from the equator places come out bigger, nearer it smaller. With the default start in Stockholm:

  | Where | 1 block equals | Size |
  |---|---|---|
  | Kiruna, 68°N | 0.74 m | 1.35× |
  | Stockholm, 59°N | 1.00 m | true |
  | Berlin, 52°N | 1.19 m | 0.84× |
  | Grand Canyon, 36°N | 1.58 m | 0.63× |
  | Equator | 1.96 m | 0.51× |

  The change is gradual (about 3 % per 100 km at 60°N), so locally nothing looks off. Heights scale the same way,
  so a smaller Grand Canyon is still correctly shaped. `/earth where` shows the scale where you stand.
- **East-west it wraps.** Past 180° the world carries on into the other side of the globe, so you can fly all the
  way round. North-south it ends at 85°, where the elevation data ends.

Worlds created before the Mercator map keep the older **sinusoidal** map (one block per metre everywhere, but
north-south lines lean far from its centre). Set `"projection": "sinusoidal"` to make new worlds that way.

## Towns, roads and railways

- **Place names.** Walking into a town shows its name as a title, with the district under it (Södermalm,
  Stockholm); walking on from one district into the next shows the new one above the hotbar. Names come from
  the map's places: a city's name reaches some kilometres, a village's one or two.
- **Signs.** Shops and halls have their name from the map on a sign over the door, and now and then a post at
  the kerb carries the name of the street beside it.
- **Advancements for real places**, in a tab of their own: walk into a town on the map and into a country's
  capital, cross the equator, a polar circle, the prime meridian and the date line, stand 4,000 m up in thin
  air or on dry land below sea level.

- **Real buildings.** Every building mapped in OpenStreetMap stands on its real footprint, built like a village
  house in the style of its climate: oak, cobblestone and brick in most of Europe, spruce in the north, snowy
  roofs in the far north, sandstone in deserts, acacia and terracotta in the tropics.
  - Storeys are at least 4.5 m (5 blocks) high; their number comes from the building's real height at about
    3 m each, up to 10.
  - Windows, corner posts; small buildings get hipped roofs, large ones flat roofs with a parapet.
  - **As mapped, where the map says more:** walls in the building's colour or material (yellow render, Falu red,
    white plaster, brick, glass facades); roofs in their mapped shape (gabled with gable ends, hipped, pyramidal,
    domes, onions, barrels), height and colour (red tile, slate, copper green, gold); and the parts landmarks are
    mapped in, such as towers and spires, rising from the roofs with their own caps. Roof shapes, colours and parts are
    OpenStreetMap's 3D tags, read from the Overture Maps buildings tiles one map tile at a time and kept on disk.
  - **What each building is**, from the map's points of interest and land use (OpenFreeMap's `poi` and `landuse`
    layers):
    - **Flats**: cut into stairwell sections, each with a street door into its landing and a turning stair that
      climbs a storey round a half landing; where the outer wall slants away from the stairwell, a short hallway
      leads from the landing out to the street door. Two flats or so open off each landing, divided by walls into a hall,
      living room, kitchen, bedrooms and sometimes a bathroom, a door in every wall.
    - **Shops**: a shop on the map takes the ground-floor flat nearest its point, as one room with its own street
      door, walled off from the stairwell; the flats above are as on every other floor. Bakeries, grocers,
      butchers, clothes shops, bookshops, hardware stores, cafés and restaurants, pharmacies, florists and
      others each have their own stock and their own job block.
    - **Halls**: a church (one tall nave with pews and an altar), library, school or supermarket that fills a big
      building gets it to itself.
    - **Offices** on commercial land, **warehouses** (one tall floor of stores) on industrial land, and **houses**
      where a home is small: one household, with stairs if there is room, else a ladder.
  - Furnished by room: a bed in every bedroom, a stove, sink and worktop in kitchens, a table and rug, bookshelves,
    flower pots, lanterns, and villager job blocks. Beds, chests and barrels are block entities (drawn as models
    or kept in memory), so only about one bed in ten is a real bed, the rest made up of wool under a blanket,
    and chests and barrels are kept few. A villager moves into about one chunk of town in twenty, into a real
    bed, and takes up the trade it finds. Round each player more come home to beds out of sight, one for every
    four real beds in the town within about 50 blocks, up to `townsfolk` (24): a few in a village, a busy
    city centre; children among them.
    Once every player is far away they go again, unless someone has traded with one, named it or leashed it.
    So the streets near you are busy, and a city of thousands of beds doesn't slow the server down.
  - **Loot.** House chests hold what a village house's chest of the same climate holds, pantry barrels household
    goods (`realearth:chests/storeroom`), shop barrels the shop's stock (`realearth:chests/shop_bakery` and the
    like); a datapack can change any of them. All fill when first opened. With
    [Lootr](https://modrinth.com/mod/lootr) installed each player gets their own. Barrels that are a villager's
    job block stay empty. Some stoves have fuel left in them, and some bookshelves are chiseled ones with books.
  - Between them: wells, farm plots, bells on their posts, hay stores, and street lamps along the streets.
  - **Street life from the map**, where it is mapped: bins, drinking fountains, bus and tram stops with their names
    on a post and a bench, statues, monuments, picnic tables, sandpits in playgrounds, market stalls on market
    squares, boats by marinas, docks and ferry terminals, a minecart on the rails at stations. Parks get benches
    along their paths and edges, lamps, flower beds, and now and then a fountain.
  - Each building stands level, on the ground under its whole footprint: a foundation on the low side, dug in on
    the high side.
- **Villages where buildings aren't mapped.** Built-up land without mapped buildings (ESA WorldCover) gets vanilla
  villages about 160 blocks apart instead, in the style of the climate. Pillager outposts stay out of towns, and
  villages keep clear of railways and motorways.
- **Roads, tracks and paths** come from OpenStreetMap: asphalt (grey concrete) for streets and highways, sized by
  class, coarse dirt and gravel for tracks, dirt paths for footways and cycleways. Roads are levelled across
  their width where the ground allows.
- **Railways and trams** are real rail on a gravel bed, curving and sloping to follow the line.
- **Bridges** carry roads and railways over water and valleys. A bridge the map splits into pieces is built as
  one: its deck runs straight between its two real ends, rising in a rounded crest only where it has to clear
  water or a road beneath. Near the ground it is a road on filled ground at the deck's own height, so there is
  no step onto it, and roads meeting a raised end ramp up to it (a block every three or so). Roads the map
  draws across the water's edge are built up out of it as a causeway, so they don't break off at the shore.
  Tunnels are left inside the ground.

Only newly generated chunks get these: in a world from an earlier version, towns and roads start where new
terrain begins.

## Two ways to play

- **Real Earth world type.** On the Create World screen, set *World Type* to **Real Earth**. The Overworld itself
  is the real Earth: you spawn on the ground at the start point, and the Nether and the End work as usual. The globe
  portal does nothing in this world type, because you're already on Earth.
- **Earth as a separate dimension** in any normal world, reached through a portal (below).

## Getting there (normal worlds)

Build a nether-portal-shaped frame of **lapis lazuli blocks** (inside 2×3 up to 21×21; corners optional), then
**right-click the frame with a Globe Banner Pattern**. You get the pattern from a cartographer villager, and it is
not used up. The frame fills with an animated blue-and-green portal.

- **Going in**, you arrive on the real ground at the same distance from Earth's **start point** (Stockholm by
  default) as the portal is from Overworld 0,0. A portal built 1 km east of 0,0 opens 1 km east of the start point.
  A portal is built for you on arrival, standing on a small landing.
- **Coming back**, you return to the Overworld portal you entered through.
- An existing portal within 16 blocks of the arrival point is reused, like the nether.
- Breaking the frame collapses the portal.

The frame and activator are tags, so a datapack can change them: `realearth:portal_frame` (blocks) and
`realearth:portal_activators` (items).

## Commands

| Command | |
|---|---|
| `/earth where` | The town and district, latitude, longitude and real elevation where you stand, with a clickable OpenStreetMap link |
| `/earth origin` | Which map this world uses, its scale, and the start point |
| `/earth tp <lat> <lon>` | Go to a real place (permission level 2 by default; configurable) |
| `/earth download <radius> [<lat> <lon>]` | Download an area's data in advance, around you or a place: elevation, map water, land cover, and the coarser data distant terrain uses. Up to 50 km radius / 20,000 elevation tiles. `/earth download cancel` stops it |
| `/earth repair [all]` | Regenerate chunks that were generated from placeholders while offline (see below) |
| `/earth cache` | Cache size per source, this session's downloads, network state, chunks waiting for repair |
| `/earth cache clear` | Delete the whole download cache (permission level 4) |
| `/earth credits` | Where Earth's data comes from, with links (everyone) |
| `/earth perf [reset]` | Time spent in each stage of generating Earth, for finding what is slow |

`download`, `repair` and `cache` need permission level 2.

In the Earth dimension, F3 also shows latitude, longitude and elevation.

## Playing offline

Everything downloaded is kept in `realearth-cache/` in the game folder, shared by all worlds, so places you have
been work without a connection. To play somewhere new offline, or to have a server ready before players
arrive, run `/earth download 5000` there first.

If new terrain is generated while the data can't be downloaded, Minecraft still needs a chunk, so it gets
a placeholder: elevation from coarser tiles already on disk (or flat at sea level), water from elevation alone,
no land cover. Such chunks are listed in `<world>/realearth-degraded.json`, and operators get a message.
Back online, `/earth repair` fetches their data again and deletes them from the save (blocks and entities),
so they generate again, properly, the next time they load. It leaves alone:

- chunks loaded right now, since the game would save them again (move away and run it again);
- chunks where a player placed or broke blocks. `/earth repair all` includes them, losing what was built there.

Distant Horizons is never given placeholders: while offline its distant terrain waits and is filled in
once the data arrives.

The cache is capped at `cacheLimitMegabytes` (8 GB by default). Beyond that, the files used longest ago are
deleted; anything deleted is downloaded again when needed.

## How the terrain is made

```
Terrarium z15 PNG ─┐                          OpenStreetMap water (OpenFreeMap z14 vector tiles)
                   ├─► per tile, with its 8 neighbours:
                   │     despike ► water (map, or flat-lake detection) ► water levels ► depth ► smoothing
                   └─► per chunk: cubic B-spline to 1 m blocks ► vertical scale
                         + ESA WorldCover (10 m) + Köppen climate (1 km)
                         ► biomes, surface, caves, structures, vanilla features
```

**Smoothing, against glitches.** Raw elevation tiles have three kinds of artefact that look wrong at 1 m per block:

1. **Spikes and pits.** Single-sample errors from data voids, bridges and masts. Any sample more than 25 m from
   the median of its 3×3 neighbourhood is replaced by that median.
2. **Creases and terraces.** Much of the world's source data is about 30 m resolution, bilinearly upsampled
   into the tile set, which leaves straight creases every few dozen metres. Integer-metre sources add
   one-metre terraces. A Gaussian with a 5 m standard deviation (in metres, so it behaves the same at every
   latitude) rounds both off.
3. **The grid itself.** Going from 2.4–4.8 m samples to 1 m blocks uses a **uniform cubic B-spline**, not
   bilinear filtering. It is C²-continuous, so slopes curve smoothly instead of showing the sample grid as
   facets. Unlike Catmull-Rom, it never overshoots, so it can't invent ridges or pits next to cliffs.

**Water from the map.** Where OpenStreetMap is reachable, it decides where water is: seas, lakes, ponds,
riverbanks and docks as areas, and rivers, canals, streams and ditches as lines of their real width. It's drawn at
four times the elevation grid's resolution, so shorelines and narrow streams land on the right blocks. Land below
sea level stays dry unless the map says sea, so Dutch polders are farmland, not ocean. The map doesn't know water
*levels*, so those come from the elevation:
- **The sea** is at 0.
- **Flat water.** Where the elevation is flat on the water (lakes, and river stretches the source flattened),
  that flat value is the measured surface. It's spread to the rest of the same water body, so a lake crossing tile
  edges gets one level and a river steps down where its data steps down.
- **Water with no flat stretch** (streams, ditches) stands at the lowest shore it can see.
- **Never above the shore.** No water stands more than half a metre above its lowest visible shore.

Without the map (offline, or `useMapWater: false`), water is inferred from elevation alone, as follows.

**Lakes.** Lake surfaces are *exactly* flat in this data (Siljan reads 160.00 m across a whole tile; farmland never
stays within 2 cm over 40–80 m). Flat windows become water, beds slope down from the shore, and lakes get the river
biome.

**Impossible water levels.** Water can't stand higher than its lowest shore. Real lakes obey that in the data:
Tahoe, Geneva, Siljan, Mjøsa and Hjälmaren have no shore sample more than 1 m below the water. But some sources
flattened the sea to arbitrary heights. In Stockholm's archipelago the Baltic reads as patches at 4, 9, 15 and
22 m, with 90–98% of their shores *lower* than the water. Any water body where over 30% of the shore lies below
the surface is lowered to the shore, and to sea level when that is within 3 m of it. Without this, the sea
would be terraces with waterfalls between them.

**Seams.** Everything works in one global sample grid. Each tile is filtered together with real data from its
neighbours, so chunks computed separately agree exactly. The self-test checks 1,568 shared columns: 0 differ.

**Vertical scale.** The dimension uses the game's maximum height, Y −2032 to 2031, with sea level at **Y −1200**:

| Real elevation | Y |
|---|---|
| −11,000 m (Challenger Deep) | −2024 |
| −600 m | −1800 (1:1 above this) |
| 0 m | −1200 |
| 2,800 m | 1600 (1:1 below this) |
| 4,808 m (Mont Blanc) | ~1915 |
| 8,849 m (Everest) | ~2026 |

Above and below the knees, the scale eases logarithmically with a slope of exactly 1 at the knee, so there is no
visible kink. Solid deepslate far below the surface and open ocean far from shore are stored as single-value
chunk sections, so the tall world costs almost no memory.

**Caves.** Vanilla's own cave system: cheese caverns, spaghetti and noodle tunnels, pillars, and the entrances
that open them to the surface. They are built from the same density functions vanilla registers, seeded from the
world seed, and measured from the real ground. Where vanilla has caves from Y 64 down to Y −64, Earth has them from
the local ground down to 128 m below it, with lava at the bottom as in vanilla. Ravines follow the land. Caves
keep 8 blocks away from the sea floor, lake beds and shorelines, so they never drain a lake. Lush caves, dripstone
caves and the deep dark appear underground in patches, the deep dark 64 m down or more.

**Features at vanilla depths.** Every vanilla decoration runs: ores, glow lichen, geodes, dungeons, springs,
fossils, sculk, lakes and vegetation. Vanilla places the underground ones at absolute heights (diamonds near
Y −59, under ground at about Y 64). On Earth the ground is anywhere from Y −1200 to 2000, so a small hook reads
those heights as depth below the local ground. Measured in the self-test: coal averages 27 m down, iron 57 m,
gold 92 m, diamonds 106 m, redstone 113 m.

**Structures.** All of vanilla's: villages, pillager outposts, temples, igloos, witch huts, mansions, ruined
portals, shipwrecks, ocean ruins, monuments, mineshafts, strongholds, trail ruins, trial chambers and ancient
cities.
- **Ground blending.** Ground is shaped around villages and other jigsaw structures the way vanilla does it.
- **Fixed-height structures.** Ancient cities and trial chambers start that far below the *local* ground.
  Otherwise they would hang 1,200 m up over the lowlands.
- **Monuments.** They sit 24 blocks under Earth's sea, with water filled only to sea level. Over deep ocean
  they stand on prismarine pillars down to the floor, the way vanilla monuments do over deep spots.
- **Mineshafts and strongholds** keep vanilla's range below sea level.
- **Pillager outposts** stay out of towns (built-up land or mapped buildings round them); patrols, raids and
  other mobs spawn as in vanilla.

**Mobs.** Fish, squid and dolphins spawn near the sea level; in a lake or river above the sea (Siljan is 161 m
up), its own surface counts instead, so inland waters have fish. Vanilla's other spawn rules measure from the
sea level, so they work at Earth's, with one exception:
slimes used fixed heights (slime chunks below Y 40, swamps between Y 50 and 70), which put them on the surface
of every slime chunk. They are measured from Earth's sea level instead: slime chunks 23 blocks under the sea,
as in vanilla.
- **Biome checks** during generation use exact terrain. `/locate`, explorer maps and the stronghold search use
  a coarse ~1 km elevation grid instead, so they stay fast. Near coastlines their answer can occasionally
  disagree with what generates.

**What grows where.** Two real datasets:

- **Land cover:** [ESA WorldCover 2021](https://esa-worldcover.org), 10 m. It says *what* grows:
  - tree cover
  - shrubland
  - grassland
  - cropland
  - built-up
  - bare ground
  - snow and ice
  - water
  - wetland
  - mangroves
  - moss and lichen

  Only the needed tiles are fetched, with HTTP range requests, and cached on disk.
- **Climate:** [Köppen-Geiger 1991–2020](https://www.nature.com/articles/s41597-023-02549-6), 1 km. It says
  *which kind* grows: a Swedish forest is taiga, the Amazon jungle, Tuscany light oak wood, Mongolia steppe. The
  whole planet's climate map is bundled in the mod (6.5 MB) and works offline.

Elevation still sets the snowline on mountains, and smooth noise only picks between neighbouring kinds (spruce or
birch, oak or dark oak). The ground follows suit:
- **Fields** are farmland in plots of about 24 blocks, each with its own crop (wheat, potatoes, carrots,
  beetroot) at its own ripeness, in rows, with some lying fallow.
- **Built-up areas** are paved ground with yards and no forest.
- **Wetlands** are mud and moss with shallow pools.
- **Glaciers and ice caps** are packed ice and snow.
- **Deserts:** hot deserts are sand, cold deserts are gravel.
- **Tundra** is moss with moss carpets.

Vanilla trees, plants, snow and ice decorate on top. Deepslate starts 64 m below the ground. Vanilla's
height-based cooling is cut to a quarter on Earth (about the real 6.5 °C/km), so ordinary forests don't get snow
at a few hundred metres.

**Distant Horizons.** With [Distant Horizons](https://modrinth.com/mod/distanthorizons) 3.3+ installed, Real Earth
supplies its distant terrain directly from the data, without generating any chunks:
- **Matching resolution:** each column reads elevation and land cover at a resolution matching its size.
- **Near the player:** columns use the same smoothed terrain the chunks use, measured within 0.1 blocks on
  average up to 64 m columns, so loaded chunks blend into the distant terrain without a visible seam.
- **Far out:** forests get a canopy, and terrain steps read as hillside instead of grey walls.
- **Cost:** a 16 × 16 km patch costs about 7 s of downloading the first time, then nothing.

At one block per metre this is what makes mountains on the horizon visible at all.

## Configuration: `config/realearth.json`

| Key | Default | |
|---|---|---|
| `startLatitude`, `startLongitude` | 59.3293, 18.0686 | Where players start, and what portals lead relative to, for **new** worlds |
| `projection` | `mercator` | `mercator` or `sinusoidal`, for **new** worlds |
| `scaleLatitude` | the start latitude | Mercator: where one block is exactly one metre (at most 70°) |
| `smoothingMeters` | 5.0 | Gaussian smoothing (0 to disable) |
| `despikeMeters` | 25.0 | Spike threshold |
| `useMapWater`, `mapTileJson` | true, OpenFreeMap | Water from OpenStreetMap |
| `useLandCover`, `landCoverUrl` | true, ESA S3 | Land cover from ESA WorldCover |
| `useRoads`, `useRailways` | true, true | Roads, tracks and paths; railways and trams (need `useMapWater`'s map tiles) |
| `useBuildings` | true | Build the map's buildings (and no villages where they are) |
| `useBuildingDetail` | true | Roof shapes, colours and building parts from the Overture Maps buildings tiles |
| `buildingDetailUrl`, `buildingDetailRelease`, `buildingDetailCatalog` | Overture's S3, `latest`, Overture's STAC | Where those tiles are, and which monthly release |
| `placeTitles` | true | Show a town's name on walking into it, and the district's above the hotbar |
| `storeyMeters`, `maxFloors` | 4.5, 10 | Height of a storey (at least 5 blocks) and most storeys per building |
| `villagerChance` | 0.2 | Chance that a chunk with beds gets a villager (one); about one chunk of town in twenty |
| `townsfolk` | 24 | Most villagers kept in the town round each player (one per four real beds there); 0 turns it off |
| `detectLakes` | true | Flat-lake detection, where map water isn't available |
| `lakeWindowSamples`, `lakeFlatnessMeters` | 17, 0.02 | How large and how flat a lake must be |
| `maxLakeDepthMeters` | 12 | Deepest carved lake bed |
| `compressAboveMeters`, `compressBelowMeters` | 2800, −600 | Where 1:1 vertical scale ends |
| `teleportPermissionLevel` | 2 | Who may use `/earth tp` (0 = everyone) |
| `downloadThreads` | 8 | Concurrent tile downloads |
| `cacheLimitMegabytes` | 8192 | Largest the download cache may grow (0 = no limit) |
| `tileUrl`, `cacheDirectory` | AWS, `realearth-cache` | Tile source and the shared on-disk cache |

**The map is pinned per world.** The projection, scale and start point are copied into `<world>/realearth.json`
the first time the world is opened, because changing them later would tear new terrain away from old. To start
somewhere else, set it in the config *before* creating the world. (The Mercator map itself is the same in every
world; only the start point and scale differ.)

## Limits worth knowing

- **Scale is only exact at one latitude** (see *The map*). Places far from the start's latitude are bigger or
  smaller than real; sinusoidal worlds instead lean far from their centre, and `/earth tp` warns you there.
- **Wrapping repeats the world.** Flying round the globe reaches a second copy of each place, at different block
  coordinates; what you build in one copy is not in the other. `/earth tp` always takes you to the first copy.
- **Render distance.** At one block per metre, a 16-chunk view is 256 m of real ground. Install Distant Horizons.
- **The data, not the mod.**
  - The elevation source is coarse, and in places a surface model. Central Stockholm is a flat 26–27 m patch
    for land and water alike, so its city channels stand at 26 m (real: under 1 m), and Mälaren reads 4 m
    (real: 0.7 m). National LiDAR (e.g. Lantmäteriet's 1 m model for Sweden) would fix this region by region.
  - Without map water (offline), land below sea level that isn't a flat lake floods.
- **Buildings are village houses, not replicas.** Shapes and heights are real, but the look is the village style of
  the climate, and interiors are open floors furnished along the walls rather than real rooms. A building that
  crosses a map tile boundary (every 1–2 km) is built as two halves that may differ in floor level and roof.
- Distant Horizons shows buildings only near the player, as solid blocks up to their roofs.
- **Chunk memory.** Chunks are 254 sections tall (vanilla has 24). Solid rock and open ocean are stored compactly,
  but each loaded chunk still costs several times a vanilla one.
- Some shallow caves stop just under the surface, leaving a thin roof of dirt.
- Needs internet the first time an area is visited, unless it was downloaded in advance (see Playing offline).
- A village or other structure that crosses into a repaired chunk may not line up with its parts in
  neighbouring chunks, since its layout follows the corrected terrain.
- Each game folder has its own cache: a dedicated server and your own client download separately.

Planned features and known issues: see [ROADMAP.md](ROADMAP.md).

## Development

Requires Java 25.

```bash
./gradlew build          # build/libs/realearth-<version>.jar
./gradlew runSelftest            # headless server, normal world + Earth dimension: terrain, caves, ores,
                                 # structures, portals; ends with [selftest] PASS/FAIL
./gradlew runSelftestEarthType   # the same on a Real Earth world-type world (portal must refuse)
./gradlew runShots               # client: flies to real places and saves screenshots, then quits
./gradlew runShots -PshotList=structures   # village, outpost, cave, ancient city in run-shots/saves/earthtest
./gradlew runShots -PshotOnly=geiranger    # one place; put Distant Horizons in run-shots/mods/ to test LODs
```

Distant Horizons is a compile-only dependency: download `DistantHorizons-3.3.3-26.1.2-fabric-neoforge.jar` from
Modrinth into `libs/` before building. The elevation tile approach (global sample grid, pixel-centre sampling,
the 404-over-ocean behaviour) carried over from the GeoWorld project.

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

## Data and credits

The full attribution, as each licence words it, is in [CREDITS.md](CREDITS.md), which is also packaged in the mod's
jar. In game, `/earth credits` lists the sources with links, the F3 screen on Earth shows a one-line credit, and
players see it when they join or arrive on Earth.

- Elevation: Terrain Tiles on AWS (Mapzen): USGS 3DEP, SRTM and GMTED2010, NOAA ETOPO1, Copernicus EU-DEM and
  national data from Australia, Austria, Canada, Mexico, New Zealand, Norway and the UK, and ArcticDEM.
- Water, roads, railways and bridges: © OpenStreetMap contributors (ODbL), served as vector tiles by OpenFreeMap
  in the OpenMapTiles schema (© OpenMapTiles).
- Roof shapes, roof and facade colours, and building parts: Overture Maps Foundation, buildings theme (from
  OpenStreetMap and others), ODbL 1.0, © OpenStreetMap contributors.
- Land cover: ESA WorldCover 10 m 2021 v200, © ESA WorldCover project / contains modified Copernicus Sentinel
  data (2021), CC BY 4.0.
- Climate: Beck, H. E., et al. (2023). High-resolution (1 km) Köppen-Geiger maps for 1901–2099 based on
  constrained CMIP6 projections. *Scientific Data* 10, 724. CC BY 4.0. Bundled re-tiled, classes unmodified.
