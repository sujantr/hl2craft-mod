# HL2Craft MODLOG

## Intake (2026-10-05)
- Idea: Half-Life 2 weapons inside Minecraft. First slice: crowbar + 9mm pistol.
- Machine: macOS 27.0.1 arm64.
- Minecraft Java 26.3, demo account, Fabric loader 0.19.5 already installed (profile from doomcraft).
  Saves: ~/Library/Application Support/minecraft/saves/Demo_World
- HL2 Demo = Steam app 219. Store metadata says mac:true, but Source mac builds are 32-bit x86, which
  can't run on Catalina+ or under Rosetta. HL2 (220) has dropped mac entirely. So HL2 never runs here.

## Route
- Content port, "bring your own game files": a local converter reads the user's own HL2 demo install
  (VPK -> wav sounds, HUD icon font / VTF) into a resource pack generated on the user's machine.
  A Fabric mod (template: ~/git repos/flash game/doomcraft-mod) adds the items and HL2-style behaviour.
  Ship no Valve files.

## Log
- Requested Steam install of app 219 (steam://install/219); user confirms in Steam.
- User: HL2 demo is already installed under Wine. Found in Sikarugir wrapper:
  HL2DIR=~/Applications/Sikarugir/Untitled.app/Contents/SharedSupport/prefix/drive_c/Program Files (x86)/Steam/steamapps/common/Half-Life 2
  appmanifest_219 "Half-Life 2: Demo" StateFlags 4; content is the HL2 (220) depot, buildid 19307283.
  hl2/: hl2_{misc,pak,sound_misc,sound_vo_english,textures}_dir.vpk + 26 data vpks; resource/halflife2.ttf (HUD weapon glyphs).
  The native Steam download of 219 I started is redundant; user can cancel it in Steam > Downloads.
- Scaffolded Fabric project from doomcraft (MC 26.3, loader 0.19.5, fabric-api 0.161.0+26.3, Java 25).

## Rethink (user): HL2 runs under Wine (Sikarugir) -> passthrough is possible
- hl2.exe is PE32 (32-bit), DXVK d3d9 present in bin/. Launch through the wrapper's Steam (steam.exe).
- engine.dll has: -condebug, con_logfile, sv_allow_wait_command, rcon_password; NO -netconport.
- bin/ has vbsp/vvis/vrad/studiomdl -> can compile our own map on the user's machine.
- Swift 6.4 available -> ScreenCaptureKit helper for capturing the HL2 window.
- Proposed passthrough: Minecraft owns the gameplay (hitscan in the MC world, ammo, damage); HL2 is the live weapon
  renderer + audio: viewmodel on a chroma-key map, driven by a cfg-exec poll loop; frames captured and drawn in MC.
- Backups: ~/.universal-modder/backups/{hl2-cfg-before-hl2craft,mc-demo-world-before-hl2craft}/20261005-173008.zip (no HL2 save dir existed). Restore: um backup restore.
- 18:03 User launched HL2 with the bridge flags. Bridge VERIFIED: console.log shows "HL2CRAFT bridge loaded",
  "ack 0"; hc_1 written via atomic rename executed within one poll (<10 ms observed), poll moved to hc_2.
  Gotcha: each missed poll logs "'hl2craft/hc_N' not present; not executing." (~25/s) -> console.log grows.
- HL2 window: owner "wine", title "HALF-LIFE 2 - Direct3D 9", 1280x720 client (+32 title bar). screencapture -l
  works from this shell (Screen Recording OK), captures at 2x (2560x1504).
- Key room: tools/make_keyroom.py (VMF, UnlitGeneric vgui/white $color [0 1 0], game_player_equip crowbar+pistol,
  no suit => no HUD). Compiling under Wine from the agent shell fails (exit 136, sandbox can't reach the running
  wineserver) and running Wine unsandboxed was refused -> user runs tools/compile_keyroom.sh in Terminal.
- 18:06 HL2 quit cleanly (user). hl2capture first matched the VS Code window ("Half-Life 2 and Minecraft…"):
  now matches title prefix "HALF-LIFE 2 - " (case-sensitive) AND owner app name containing "wine".
  Gotcha: CLI ScreenCaptureKit needs NSApplication.shared first (else CGS_REQUIRE_INIT assert).
  Capture verified at ~29 fps on the static menu (SCK only delivers changed frames), 960x540, header/seq OK.
- hl2craft.cfg now also: snd_mute_losefocus 0, engine_no_focus_sleep 0, r_decals 0, fps_max 120 (all present in
  this build's DLL strings). Source copy: hl2cfg/hl2craft.cfg.
- Fabric mod builds (JAVA_HOME=/opt/homebrew/opt/openjdk@25/...). Items hl2craft:pistol (clip 18 as durability,
  2.0 dmg, x3 headshot, 64-block hitscan) and hl2craft:crowbar (4.0 dmg, 2.5/s). First-person vanilla item hidden
  with a display_context select -> minecraft:empty. Overlay: HUD layer before CROSSHAIR, full width, bottom aligned.
  Fire path: START_CLIENT_TICK consumeClick -> integrated server firePistol -> bridge "+attack; wait 4; -attack".
- MC profile: launcher profile "hl2craft" (gameDir ~/git repos/hl2craft-mod/mc-profile, fabric-loader-0.19.5-26.3),
  mods: hl2craft + fabric-api-0.161.0+26.3, Demo_World copy. launcher_profiles.json backed up to
  ~/.universal-modder/backups/minecraft-launcher-profiles-before-hl2craft/.
- NEXT: user runs tools/compile_keyroom.sh, launches HL2 (+exec hl2craft) -> verify `map hl2craft_key` via bridge
  keeps the poll loop alive across map load; then run hl2capture + MC profile and test the slice.

## 2026-10-05 evening: slice WORKING in the real games
- Wine from the agent shell: exit 136 = SIGFPE in ntdll (crash report). Cause: client sync mode must match the
  wrapper's running wineserver -> WINEESYNC=1 WINEMSYNC=1 (from Info.plist). Helper ~/.local/bin/hl2wine (user
  allowed it via a Claude Code permission rule). The map tools and HL2 launch work through it.
- HL2 DEMO MAP WHITELIST: `map hl2craft_key` -> "map load failed: hl2craft_key not found or invalid" although
  the BSP is valid v20 and visible to `maps *`. The binaries carry a list of demo maps (d1_trainstation_01/02,
  d1_town_01/01a/02/03, background0x, ...). Not bypassed (no renaming over a demo map). The key-room tools stay
  for full-HL2 owners.
- Green screen inside a demo map (hl2cfg/hl2craft_stage.cfg, d1_town_01): fog_override + green fog makes
  everything left pure green; r_drawworld/staticprops/disp/brushmodels/detailprops/ropes/particles 0 leaves a
  black clear; mat_fullbright 1 lights the viewmodel; r_3dsky 0 + r_drawskybox 0. Viewmodel is NOT fogged.
  r_drawothermodels 0 also hides the viewmodel (don't). hl2capture keys black (max channel <24, soft) AND
  green (g > max(r,b)+10; dark green-black blends fully transparent).
- `wait` inside an exec'd cfg does NOT hold a button: "+attack; wait 4; -attack" = press+release in one frame,
  so no shot (getpos proved yaw unchanged with "+left; wait 60; -left", changed when split across files).
  HL2Bridge.tap() writes +x and -x as separate files ~80 ms apart. HL2 takes console input while unfocused.
- god/notarget are toggles: stage runs via alias hc_stage which replaces itself (once per HL2 launch).
- Measuring the viewmodel: whole-frame mean diffs hide a shot (gun is ~3% of the frame); crop the gun region.
  HL2 delivers ~20 new frames/s while behind Minecraft (SCK reports ~58 callbacks/s incl. repeats).
- Minecraft: demo account plays modded installations from the launcher's Installations tab (demo mode).
  Extra Play clicks launched duplicate instances (closed by PID); the launcher then shows a crash report
  (dismissed with "Do not share"). Demo world has no cheats -> /hl2craft gives the kit, /hl2craft zombie spawns
  a still target 6 blocks ahead.
- VERIFIED (screenshots): HL2 pistol and crowbar viewmodels drawn over Minecraft; left click fires (MC clip
  18->14, HL2 recoil anim in capture); 4 headshots kill the test zombie ("Monster Hunter" advancement); R plays
  HL2's reload animation and refills; slot switch swaps HL2 to the crowbar.
- Latency click->recoil on screen ~0.4 s (HL2 ~20 fps in background, bridge poll ~100 ms). NEXT: cut latency
  (HL2 background fps, App Nap), viewmodel sway from MC mouse look, crowbar swing timing, HL2 HUD icons, more
  weapons (SMG1, shotgun, .357 exist in the demo? check weapon scripts), showcase clip.
