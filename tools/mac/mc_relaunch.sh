#!/bin/sh
# Relaunch the HL2Craft Minecraft installation from an open launcher and enter the demo world.
# usage: mc_relaunch.sh <launcher_pid> <ids_file>   (writes "<pid> <window>" of the new game to ids_file)
# Launcher coordinates are for a 1280x752 launcher window at (100,33) on the Installations tab; adjust if moved.
T="$(cd "$(dirname "$0")" && pwd)"; M="$T/../.."; L="$M/mc-profile/logs/latest.log"
cp "$M/build/libs/hl2craft-1.0.0.jar" "$M/mc-profile/mods/"
"$T/click" "$1" 1159 514
P=""; for i in $(seq 1 20); do sleep 2; P=$(ps -axo pid,command | grep 'java-runtime-epsilon' | grep -v grep | awk '{print $1}' | head -1); [ -n "$P" ] && break; done
W=""; for i in $(seq 1 30); do W=$("$T/wins" "$P" | grep 'Minecraft' | awk '{print $1}' | head -1); [ -n "$W" ] && break; sleep 2; done
echo "$P $W" > "$2"; echo "pid $P win $W"
sleep 8; "$T/click" "$P" 864 563
for i in $(seq 1 30); do grep -q 'joined the game' "$L" && break; sleep 2; done
sleep 4; "$T/click" "$P" 1110 815   # "Continue Playing!" on the demo dialog
