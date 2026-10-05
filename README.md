# HL2Craft

Half-Life 2 weapons in Minecraft, as a passthrough mashup. Half-Life 2 (the free Steam demo, under Wine)
runs next to Minecraft and renders the real first-person weapon and its sound. Minecraft does the gameplay:
hits, damage, ammo. No Valve files are included; everything comes from your own install.

```
Minecraft (Fabric mod) --cfg/hl2craft/hc_N.cfg--> HL2 console (+attack, +reload, use weapon_x)
        ^                                                  |
        +--- keyed RGBA frames <-- hl2capture <-- HL2 window (viewmodel on black/green)
```

## Requirements
- Minecraft Java 26.3 with Fabric Loader 0.19.5 and Fabric API 0.161.0+26.3 (a demo account works).
- Half-Life 2 Demo (Steam app 219) running under Wine. Tested in a Sikarugir wrapper on macOS (arm64).
- macOS Screen Recording permission for `capture/hl2capture`.

## Run
1. Copy `hl2cfg/hl2craft.cfg` and `hl2cfg/hl2craft_stage.cfg` into `Half-Life 2/hl2/cfg/`.
2. Start the HL2 Demo with `-windowed -w 1728 -h 972 -novid -condebug +exec hl2craft`. It loads d1_town_01.
3. `swiftc -O -o capture/hl2capture capture/hl2capture.swift && capture/hl2capture`
4. Put `build/libs/hl2craft-1.0.0.jar` and Fabric API into your Fabric profile's `mods/`. If HL2 isn't in the
   default Sikarugir path, set `hl2_dir` in `config/hl2craft.properties`.
5. In a single-player world, select the crowbar or 9mm pistol (`/hl2craft` gives both). The first selection
   stages HL2: weapons, god mode, and the "viewmodel only" render settings.

Controls: left click fires or swings, R reloads. `/hl2craft zombie` spawns a test target that stands still.

## Notes
- Single-player only. HL2 is driven through its offline developer console (`sv_cheats 1` in the demo's own
  map); do not use any of this online.
- The HL2 demo only loads its own maps. `tools/make_keyroom.py` + `tools/compile_keyroom.sh` build a
  chroma-key room for owners of the full game; the demo route uses `hl2craft_stage.cfg` instead.
- Built with an AI coding agent (Claude Code).
