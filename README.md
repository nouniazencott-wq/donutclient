# Donut Client

A client-side Fabric utility mod for Minecraft **26.2** with a click GUI,
a base finder, through-wall ESP and HUD modules.

Press **Right Shift** in game to open the click GUI. Left click a module to
toggle it, right click to open its settings. Every module also has a rebindable
key under *Options → Controls → Donut Client*.

| Category | Module | What it does |
|---|---|---|
| World | **Base Finder** | Scores every loaded chunk by its storage, hoppers, spawners, beacons etc. Chunks over *Min Score* are boxed through walls, announced in chat, listed on the HUD and logged to `config/donutclient-bases.csv`. |
| World | Storage ESP | Outlines chests, barrels, shulkers, ender chests and spawners through walls. |
| World | Player Alert | Chat message when a player enters or leaves render range. |
| Render | Player ESP | Boxes, tracers and name/health/distance tags for other players. |
| Render | Fullbright | Client-side night vision. |
| Render | Zoom | Hold `C` to zoom. |
| Movement | Auto Sprint, Auto Walk | Hands-free travel. |
| HUD | FPS, Coordinates, Keystrokes, Module List | On-screen info. |

The base finder only reads what the server sends to your client. Servers that
hide block entities or run anti-xray will show fewer (or no) results, and most
public servers ban this kind of mod — use it where it is allowed.

## Requirements

- **JDK 25** (Minecraft 26.x requires it) — https://adoptium.net
- IntelliJ IDEA (Community is fine) with the Minecraft Development plugin, or VS Code
- A Minecraft Java account (the dev client logs in as an offline "Player###" by default)

## First run

```bash
# 1. Set up IDE run configs + download Minecraft/Fabric
./gradlew genSources            # attaches Minecraft sources (26.x is unobfuscated)
./gradlew runClient             # launches a dev client with the mod loaded

# 2. Build a distributable jar
./gradlew build                 # -> build/libs/donutclient-0.2.0.jar
```

Drop the jar into `.minecraft/mods/` together with Fabric API, launched via the
Fabric Loader profile for 26.2.

## Project layout

```
src/main/      common code (tiny — this mod is client-only)
src/client/    everything else
  DonutClientClient.java     entrypoint: wires events → ModuleManager
  module/                    Module base class, categories, ModuleManager
  modules/*.java             the features
  setting/                   BoolSetting / NumSetting shown in the click GUI
  gui/ClickGuiScreen.java    the click GUI
  render/World.java          through-wall boxes, lines and text (vanilla Gizmos)
  hud/HudContext.java        thin facade over Minecraft's HUD drawing API
  mixin/                     CameraMixin (zoom FOV), LevelRendererMixin (world draw hook)
  config/ClientConfig.java   saves module state + settings to config/donutclient.json
```

## Adding a module

1. Create `modules/MyThingModule.java` extending `Module`.
2. Override `onTick()`, `onRenderHud(HudContext)` and/or `onRenderWorld(float)`;
   declare settings with `add(new BoolSetting(...))`.
3. Add `register(new MyThingModule());` in `ModuleManager.registerDefaults()`.
4. Add a `key.donutclient.my_thing` line to `assets/donutclient/lang/en_us.json`.

Keybinds show up automatically under *Options → Controls → DonutClient Modules*.

## Things to know about modding 26.x

- No Yarn/Intermediary mappings anymore — the game ships unobfuscated, so class
  names are Mojang's (`Minecraft`, `GuiGraphics…`, `KeyMapping`, `ResourceLocation`).
- The Loom plugin id is `net.fabricmc.fabric-loom` (not `fabric-loom`), and mod
  deps use `implementation`, not `modImplementation`.
- The rendering pipeline was rewritten in 26.1. HUD drawing goes through
  `HudContext`, so that is the one file to fix if `GuiGraphicsExtractor` changes.
- Fabric docs: https://docs.fabricmc.net/develop/ · Versions: https://fabricmc.net/develop

## Roadmap ideas

- Draggable HUD positions saved in config
- Waypoints for logged bases, shown in-world after a restart
- Armor/potion status HUD, server ping
