#!/bin/sh
# Generate and compile the HL2Craft key room with HL2's own map tools, inside the Sikarugir Wine wrapper.
# usage: tools/compile_keyroom.sh [path/to/Wrapper.app]
set -e
# WINEESYNC/WINEMSYNC must match the wrapper (Info.plist): a client of its running wineserver with a different
# sync mode dies at once with SIGFPE (exit 136) and prints nothing.
APP="${1:-$HOME/Applications/Sikarugir/Untitled.app}"
C="$APP/Contents"; S="$C/SharedSupport"
export WINEPREFIX="$S/prefix" DYLD_FALLBACK_LIBRARY_PATH="$C/Frameworks:$S/wine/lib:/usr/lib" WINEDEBUG=-all WINEESYNC=1 WINEMSYNC=1
HL2="$WINEPREFIX/drive_c/Program Files (x86)/Steam/steamapps/common/Half-Life 2"
G='C:\Program Files (x86)\Steam\steamapps\common\Half-Life 2'
python3 "$(dirname "$0")/make_keyroom.py" "$HL2/hl2"
cd "$HL2/bin"
"$S/wine/bin/wine" vbsp.exe -game "$G\\hl2" "$G\\hl2\\mapsrc\\hl2craft_key"
"$S/wine/bin/wine" vvis.exe -fast -game "$G\\hl2" "$G\\hl2\\mapsrc\\hl2craft_key"
"$S/wine/bin/wine" vrad.exe -fast -game "$G\\hl2" "$G\\hl2\\mapsrc\\hl2craft_key"
cp "$HL2/hl2/mapsrc/hl2craft_key.bsp" "$HL2/hl2/maps/"
echo "OK: $HL2/hl2/maps/hl2craft_key.bsp"
