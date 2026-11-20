# Donut Client

A client-side Fabric mod for Minecraft **26.2**. Its main feature is **Auto Beat**: a
singleplayer-only bot that plays the game by itself, from punching the first tree to
killing the Ender Dragon. It also has a few small HUD modules (FPS, coordinates,
keystrokes) and a hold-to-zoom key.

## Using the bot

The bot only runs in a singleplayer world (it refuses to start on a server).

| What | How |
| --- | --- |
| Start / stop | press **B**, or `/bot start` and `/bot stop` |
| Take over for a while | `/bot pause`, then `/bot resume` |
| Walk to coordinates | `/bot goto <x> <z>` |
| Ask what it is doing | `/bot status` |

While it runs, a panel in the top-left corner shows play time, deaths, the current
stage of the plan with a progress bar, and what the bot is doing right now.

The plan, in order: wood and stone tools, food, iron and iron gear, a Nether portal
cast from a lava pool, blaze rods and ender pearls, eyes of ender, the stronghold, and
the dragon fight. If the bot dies it respawns, tries to fetch its items and falls back
to whichever stage it now needs again.

All keys can be rebound under *Options → Controls → Donut Client Modules*.

## Requirements

- **JDK 25** (Minecraft 26.x requires it) — https://adoptium.net
- A Minecraft Java account to play; the dev client logs in as an offline player

## Building

```bash
./gradlew build                 # -> build/libs/donutclient-<version>.jar
./gradlew runClient             # launches a dev client with the mod loaded
```

Drop the jar into `.minecraft/mods/` together with Fabric API, launched via the
Fabric Loader profile for 26.2. Every push is also built by GitHub Actions, which
keeps the jar as a downloadable artifact.

## Project layout

```
src/main/      common code (tiny — this mod is client-only)
src/client/    everything else
  DonutClientClient.java     entrypoint: events, /bot commands
  module/                    Module base class, ModuleManager (registry, keybinds)
  modules/                   FPS, Coordinates, Keystrokes, Zoom, Auto Beat (BotModule)
  hud/HudContext.java        thin facade over Minecraft's HUD drawing API
  mixin/                     CameraMixin (zoom FOV), MinecraftMixin (bot owns mining)
  config/ClientConfig.java   saves enabled state to config/donutclient.json
  bot/                       the bot
    Bot.java                 main loop: death/respawn, dimension changes, pause
    Reflex.java              survival reactions that pre-empt the plan
    Inv, Act, Aim, Scan ...  inventory, world actions, aiming, block search
    path/                    A* pathfinder, path executor, goals
    task/                    BeatGameTask (the plan) and one task per job
```

## Adding a module

1. Create `modules/MyThingModule.java` extending `Module`.
2. Override `onTick()` and/or `onRenderHud(HudContext)`.
3. Add `register(new MyThingModule());` in `ModuleManager.registerDefaults()`.
4. Add a `key.donutclient.my_thing` line to `assets/donutclient/lang/en_us.json`.

Keybinds show up automatically under *Options → Controls → DonutClient Modules*.

## Things to know about modding 26.x

- No Yarn/Intermediary mappings anymore — the game ships unobfuscated, so class
  names are Mojang's (`Minecraft`, `GuiGraphics…`, `KeyMapping`, `Identifier`).
- The Loom plugin id is `net.fabricmc.fabric-loom` (not `fabric-loom`), and mod
  deps use `implementation`, not `modImplementation`.
- The rendering pipeline was rewritten in 26.1. If `HudContext` fails to compile,
  the method names on `GuiGraphicsExtractor` moved — that's the one file to fix.
- Fabric docs: https://docs.fabricmc.net/develop/ · Versions: https://fabricmc.net/develop

## Roadmap ideas

- Bow use against the End crystals, and beds against the dragon
- A settings screen for the bot (what to collect, how careful to be)
- Draggable HUD positions saved in config
