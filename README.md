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
3. `swiftc -O -o capture/hl2capture capture/hl2capture.swift && capture/hl2capture` (captures HL2 1:1, full res)
4. Put `build/libs/hl2craft-1.0.0.jar` and Fabric API into your Fabric profile's `mods/`. If HL2 isn't in the
   default Sikarugir path, set `hl2_dir` in `config/hl2craft.properties`.
5. In a single-player world, draw any HL2 weapon. The first time, HL2 loads d1_town_01 and stages itself
   (weapons, infinite ammo, god mode, "viewmodel only" rendering); this takes ~10 s.

Weapons (all given when you join or respawn, unlimited ammo): crowbar, 9mm pistol, SMG, shotgun, .357,
grenade, gravity gun. These are the ones the HL2 Demo's data contains.

Controls: left click fires or swings (hold for the SMG), right click is HL2's secondary fire (SMG grenade,
shotgun double barrel, grenade lob), R reloads. **F9** resets everything: morning, full health, the whole kit,
hostiles cleared, a wave of 15 zombies, and HL2 back at the start. `/hl2craft zombies` spawns another wave;
`/hl2craft zombie` spawns one that stands still.

## Notes
- Single-player only. HL2 is driven through its offline developer console (`sv_cheats 1` in the demo's own
  map); do not use any of this online.
- The HL2 demo only loads its own maps. `tools/make_keyroom.py` + `tools/compile_keyroom.sh` build a
  chroma-key room for owners of the full game; the demo route uses `hl2craft_stage.cfg` instead.
- Built with an AI coding agent (Claude Code).
