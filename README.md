# Donut Client

A modular client-side Fabric mod for Minecraft **26.2**: toggleable HUD modules,
per-module keybinds, and a zoom. Built to be extended one module at a time.

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
./gradlew build                 # -> build/libs/donutclient-0.1.0.jar
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
  mixin/GameRendererMixin    FOV hook for Zoom (the only vanilla patch)
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
