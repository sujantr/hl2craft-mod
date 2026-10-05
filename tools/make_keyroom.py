"""Write the HL2Craft chroma-key room (VMF + material) into the user's HL2 folder.

The room is a sealed box lined with an unlit pure-green material. The player spawns
without the HEV suit (so HL2 draws no HUD) and a game_player_equip hands out the slice
weapons. Compile it with HL2's own vbsp/vvis/vrad (see compile_keyroom.sh).

usage: python3 make_keyroom.py "<.../Half-Life 2/hl2>"
"""
import os
import sys

KEY = "hl2craft/key"
WEAPONS = ["weapon_crowbar", "weapon_pistol"]


def side(sid, plane, mat):
    return (
        '\tside\n\t{\n\t\t"id" "%d"\n\t\t"plane" "%s"\n\t\t"material" "%s"\n'
        '\t\t"uaxis" "[1 0 0 0] 0.25"\n\t\t"vaxis" "[0 -1 0 0] 0.25"\n'
        '\t\t"lightmapscale" "16"\n\t}\n' % (sid, plane, mat)
    )


def box(bid, sid, lo, hi, mat):
    (x1, y1, z1), (x2, y2, z2) = lo, hi
    p = lambda *a: "(%g %g %g)" % a
    planes = [
        p(x1, y2, z2) + " " + p(x2, y2, z2) + " " + p(x2, y1, z2),  # top
        p(x1, y1, z1) + " " + p(x2, y1, z1) + " " + p(x2, y2, z1),  # bottom
        p(x1, y2, z2) + " " + p(x1, y1, z2) + " " + p(x1, y1, z1),  # -x
        p(x2, y2, z1) + " " + p(x2, y1, z1) + " " + p(x2, y1, z2),  # +x
        p(x2, y2, z2) + " " + p(x1, y2, z2) + " " + p(x1, y2, z1),  # +y
        p(x2, y1, z1) + " " + p(x1, y1, z1) + " " + p(x1, y1, z2),  # -y
    ]
    body = "".join(side(sid + i, pl, mat) for i, pl in enumerate(planes))
    return 'solid\n{\n\t"id" "%d"\n%s}\n' % (bid, body), sid + 6


def vmf():
    r, h, t = 256, 256, 16  # interior half-width, height, wall thickness
    slabs = [
        ((-r - t, -r - t, -t), (r + t, r + t, 0)),      # floor
        ((-r - t, -r - t, h), (r + t, r + t, h + t)),   # ceiling
        ((-r - t, -r - t, 0), (-r, r + t, h)),          # walls
        ((r, -r - t, 0), (r + t, r + t, h)),
        ((-r, -r - t, 0), (r, -r, h)),
        ((-r, r, 0), (r, r + t, h)),
    ]
    solids, sid = [], 1
    for i, (lo, hi) in enumerate(slabs):
        s, sid = box(i + 1, sid, lo, hi, KEY)
        solids.append(s)
    world = 'world\n{\n\t"id" "1"\n\t"mapversion" "1"\n\t"classname" "worldspawn"\n\t"skyname" "sky_day01_01"\n%s}\n' % "".join(
        "\t" + l + "\n" for s in solids for l in s.splitlines()
    )
    equip = "".join('\t"%s" "1"\n' % w for w in WEAPONS) + '\t"item_ammo_pistol_large" "3"\n'
    ents = [
        'entity\n{\n\t"id" "100"\n\t"classname" "info_player_start"\n\t"origin" "0 0 1"\n\t"angles" "0 0 0"\n}\n',
        'entity\n{\n\t"id" "101"\n\t"classname" "game_player_equip"\n\t"origin" "0 0 8"\n%s}\n' % equip,
        # vrad needs a light for the viewmodel's ambient cube; the walls are unlit anyway.
        'entity\n{\n\t"id" "102"\n\t"classname" "light"\n\t"origin" "0 -128 200"\n\t"_light" "255 255 255 400"\n}\n',
        'entity\n{\n\t"id" "103"\n\t"classname" "light"\n\t"origin" "0 128 200"\n\t"_light" "255 250 240 250"\n}\n',
    ]
    return "versioninfo\n{\n\t\"editorversion\" \"400\"\n\t\"formatversion\" \"100\"\n}\n" + world + "".join(ents)


def main(hl2):
    os.makedirs(os.path.join(hl2, "materials", "hl2craft"), exist_ok=True)
    os.makedirs(os.path.join(hl2, "mapsrc"), exist_ok=True)
    with open(os.path.join(hl2, "materials", KEY + ".vmt"), "w", newline="\r\n") as f:
        f.write('"UnlitGeneric"\n{\n\t"$basetexture" "vgui/white"\n\t"$color" "[0 1 0]"\n}\n')
    with open(os.path.join(hl2, "mapsrc", "hl2craft_key.vmf"), "w", newline="\r\n") as f:
        f.write(vmf())
    print("wrote", os.path.join(hl2, "mapsrc", "hl2craft_key.vmf"))


if __name__ == "__main__":
    main(sys.argv[1])
