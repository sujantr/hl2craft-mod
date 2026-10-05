package dev.hl2craft;

/**
 * The HL2 Demo weapons that load (the demo's data has no RPG, AR2 or crossbow).
 *
 * Damage is HL2's skill.cfg (Normal) scaled by zombie health, HL2 50 -> Minecraft 20 (x0.4). Refire is in
 * Minecraft ticks (20/s), rounded from HL2's fire rates.
 */
public enum Weapon {
    //        item id        HL2 classname          kind          dmg   pellets spread refire auto
    CROWBAR("crowbar", "weapon_crowbar", Kind.MELEE, 4.0F, 0, 0, 8, false),
    PISTOL("pistol", "weapon_pistol", Kind.HITSCAN, 2.0F, 1, 0.017, 2, false),
    SMG("smg", "weapon_smg1", Kind.HITSCAN, 1.6F, 1, 0.045, 2, true),
    SHOTGUN("shotgun", "weapon_shotgun", Kind.HITSCAN, 3.2F, 7, 0.09, 14, false),
    MAGNUM("magnum", "weapon_357", Kind.HITSCAN, 16.0F, 1, 0.0, 15, false),
    GRENADE("grenade", "weapon_frag", Kind.THROW, 0, 0, 0, 20, false),
    GRAVITY_GUN("gravity_gun", "weapon_physcannon", Kind.PUNT, 3.0F, 0, 0, 10, false);

    public enum Kind { MELEE, HITSCAN, THROW, PUNT }

    public final String id;
    public final String hl2;
    public final Kind kind;
    public final float damage;
    public final int pellets;
    public final double spread;
    public final int refireTicks;
    public final boolean automatic;

    Weapon(String id, String hl2, Kind kind, float damage, int pellets, double spread, int refireTicks, boolean automatic) {
        this.id = id;
        this.hl2 = hl2;
        this.kind = kind;
        this.damage = damage;
        this.pellets = pellets;
        this.spread = spread;
        this.refireTicks = refireTicks;
        this.automatic = automatic;
    }

    /** Right click: SMG launches a grenade, shotgun fires both barrels (HL2's +attack2). */
    public boolean hasAltFire() {
        return this == SMG || this == SHOTGUN;
    }
}
