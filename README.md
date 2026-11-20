# Donut Client

A modular client-side Fabric mod for Minecraft **26.2**: toggleable HUD modules,
per-module keybinds, a zoom, and **Auto Beat** — a bot that plays a survival world
by itself, aiming to kill the Ender Dragon. Built to be extended one module at a time.

## Auto Beat (the bot)

**Singleplayer only.** The bot refuses to start unless the world is hosted by your own
client, and it stops if you leave it. That is by design: it asks the integrated server
where things are (the same answers `/locate` gives) instead of wandering and throwing
eyes of ender, so it cannot work on a multiplayer server.

Install: put `release/donutclient-0.2.0.jar` and Fabric API (for 26.2) in `.minecraft/mods/`
and start the Fabric Loader 26.2 profile (Java 25).

Use: open a **survival** singleplayer world, then

| | |
|---|---|
| `/bot start` or press **B** | hand control to the bot |
| `/bot stop` or press **B** again | take control back at any time |
| `/bot status` | what it is doing right now (also shown top-left) |

While it runs it keeps the game from pausing when the window loses focus, so you can
leave it going in the background. Opening the Esc menu still pauses it.

### What it does

It works through a checklist and always does the first unfinished item, so it picks
itself up again after a death or after you interfere:

1. Wood, crafting table, wooden then stone tools.
2. Food (hunts and cooks), iron (about 40 ore), smelting, full iron armour, shield,
   sword, two buckets, flint and steel, spare pickaxes.
3. A nether portal, built next to a lava pool by casting the obsidian in place with
   a water and a lava bucket.
4. In the Nether it travels by tunnelling under the lava sea. It takes blaze rods from a
   bunker dug under a fortress spawner, and ender pearls from endermen in a warped forest.
5. Back home it crafts eyes of ender, goes to the stronghold, fills the portal and jumps in.
6. In the End it climbs each tower, pops the crystal from behind a wall, fights the
   dragon when it perches and leaves through the exit portal.

All the actions are ordinary player actions (walking, mining, placing, crafting,
fighting); the bot never uses commands or cheats items in. It does use two things a
person cannot: the server's knowledge of where structures and biomes are, and it can
hit blocks and mobs through walls.

Reflexes that are always on: fights back with shield and sword, eats, flees creepers,
walls itself in to heal when badly hurt, shelters through the night while unarmoured,
breaks long falls with a water bucket, swims out of lava, surfaces for air, throws away
junk when the inventory fills up, and goes back for its items after dying.

### How far it gets today

Tested by launching real headless clients (`./gradlew runClientGameTest`, see below):

- **Works, repeatedly:** steps 1–3 from an empty inventory in a fresh world (about 35–40
  in-game minutes to full iron kit, then a lit portal and into the Nether), tunnelling to
  the fortress, and collecting blaze rods from the bunker.
- **Works but slowly:** blaze rods come in at roughly one per several minutes.
- **Written but not yet proven end to end:** the enderman hunt, the trip home, eyes of
  ender, the stronghold and the End portal.
- **Not reliable yet:** the dragon fight. In tests the bot reaches the island, mines end
  stone and starts on the towers, but so far it has always been killed by dragon breath
  before finishing. Expect to take over (`/bot stop`) for the End.

So right now it is a bot that plays the first two thirds of the game for you rather
than one that reliably finishes it. A full unattended run has not been achieved.

## Requirements

- **JDK 25** (Minecraft 26.x requires it) — https://adoptium.net
- IntelliJ IDEA (Community is fine) with the Minecraft Development plugin, or VS Code
- A Minecraft Java account (the dev client logs in as an offline "Player###" by default)

## First run

```bash
# 1. Generate the gradle wrapper jar (only needed once; gradle-wrapper.jar is not committed)
gradle wrapper --gradle-version 9.4.0        # if you have Gradle installed
#   …or open the folder in IntelliJ and let it import the project.

# 2. Set up IDE run configs + download Minecraft/Fabric
./gradlew genSources            # attaches Minecraft sources (26.x is unobfuscated)
./gradlew runClient             # launches a dev client with the mod loaded

# 3. Build a distributable jar
./gradlew build                 # -> build/libs/donutclient-0.2.0.jar

# 4. Watch the bot play in a headless client (needs xvfb; software OpenGL is fine)
LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a ./gradlew runClientGameTest
#    Options go in build/bot-test.properties, e.g.
#      seed=8675309
#      ticks=40000            how long to let it play
#      tickRate=200           10x speed (test builds only)
#      setup=give @p minecraft:iron_pickaxe;time set day     commands run before it starts
#    Screenshots land in build/run/clientGameTest/screenshots, progress in the log.
```

Drop the jar into `.minecraft/mods/` together with Fabric API, launched via the
Fabric Loader profile for 26.2.

## Project layout

```
src/main/      common code (tiny — this mod is client-only)
src/client/    everything else
  DonutClientClient.java        entrypoint: wires events → ModuleManager
  module/Module.java         base class: toggle, keybind, onTick, onRenderHud
  module/ModuleManager.java  registry + event dispatch + keybinds
  modules/*.java             the features (FPS, Coordinates, Keystrokes, Zoom)
  hud/HudContext.java        thin facade over Minecraft's HUD drawing API
  mixin/CameraMixin          FOV hook for Zoom
  mixin/MinecraftMixin       lets the bot hold "mine" without the mouse button
  bot/Bot.java               the bot: tick loop, respawn handling
  bot/path/                  A* pathfinder (dig, bridge, pillar, swim) + executor
  bot/Reflex.java            always-on survival behaviour
  bot/task/BeatGameTask      the checklist; every other task/* is one step of it
src/gametest/                headless end-to-end test driver
  config/ClientConfig.java   saves enabled state to config/donutclient.json
```

## Adding a module

1. Create `modules/MyThingModule.java` extending `Module`.
2. Override `onTick()` and/or `onRenderHud(HudContext)`.
3. Add `register(new MyThingModule());` in `ModuleManager.registerDefaults()`.
4. Add a `key.donutclient.my_thing` line to `assets/donutclient/lang/en_us.json`.

Keybinds show up automatically under *Options → Controls → DonutClient Modules*.

## Things to know about modding 26.x

- No Yarn/Intermediary mappings anymore — the game ships unobfuscated, so class
  names are Mojang's (`Minecraft`, `GuiGraphics…`, `KeyMapping`, `ResourceLocation`).
- The Loom plugin id is `net.fabricmc.fabric-loom` (not `fabric-loom`), and mod
  deps use `implementation`, not `modImplementation`.
- The rendering pipeline was rewritten in 26.1. If `HudContext` fails to compile,
  the method names on `GuiGraphicsExtractor` moved — that's the one file to fix.
- Fabric docs: https://docs.fabricmc.net/develop/ · Versions: https://fabricmc.net/develop

## Roadmap ideas

- Click GUI (`Screen` subclass) to toggle modules with the mouse — bind to Right Shift
- Draggable HUD positions saved in config
- Armor/potion status HUD, CPS counter, reach display, server ping
- Fullbright (gamma override), hit-colour/particle tweaks
- Discord Rich Presence, cosmetics (capes via a custom `PlayerRenderer` layer)
