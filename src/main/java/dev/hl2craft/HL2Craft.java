package dev.hl2craft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * HL2Craft: Half-Life 2 weapons in Minecraft. Minecraft owns the gameplay (hits, damage); a live HL2 process
 * renders the first-person weapon and plays its sounds (see the client side). Ammo is unlimited on both sides
 * (here, and sv_infinite_ammo in HL2). Single-player only: the client calls the integrated server directly.
 */
public class HL2Craft implements ModInitializer {
    public static final String MOD_ID = "hl2craft";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static final float HEADSHOT_MULTIPLIER = 3.0F;  // HL2 sk_npc_head
    public static final double RANGE = 64.0;
    public static final String WAVE_TAG = "hl2craft_wave";
    public static final int WAVE_SIZE = 15;

    public static final Map<Weapon, Item> ITEMS = new EnumMap<>(Weapon.class);

    /** Thrown frags (fuse) and SMG grenades (burst on contact). */
    private record Grenade(ItemEntity entity, ServerPlayer owner, int explodeAt, boolean impact) { }
    private static final List<Grenade> GRENADES = new ArrayList<>();

    private static Item register(Weapon w, Item.Properties props) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, w.id));
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(props.setId(key)));
    }

    @Override
    public void onInitialize() {
        for (Weapon w : Weapon.values()) {
            // Crowbar: HL2 10 damage, 0.4 s per swing -> 4.0 damage, 2.5 swings/s (player 1 + iron 2 + 1).
            Item.Properties props = w == Weapon.CROWBAR
                    ? new Item.Properties().sword(ToolMaterial.IRON, 1.0F, -1.5F)
                    : new Item.Properties().stacksTo(1);
            ITEMS.put(w, register(w, props));
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                dispatcher.register(Commands.literal("hl2craft")
                        .executes(ctx -> {
                            giveKit(ctx.getSource().getPlayerOrException());
                            ctx.getSource().sendSuccess(() -> Component.literal("HL2Craft: all HL2 weapons"), false);
                            return 1;
                        })
                        // Test target: a zombie standing still 6 blocks ahead, so shots are repeatable.
                        .then(Commands.literal("zombie").executes(ctx -> {
                            spawnTarget(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                        .then(Commands.literal("zombies").executes(ctx -> {
                            spawnWave(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                        .then(Commands.literal("reset").executes(ctx -> {
                            reset(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))));
        ServerTickEvents.END_SERVER_TICK.register(HL2Craft::tickGrenades);
        LOG.info("HL2Craft items registered: {}", ITEMS.keySet());
    }

    public static Weapon weaponOf(ItemStack stack) {
        for (Map.Entry<Weapon, Item> e : ITEMS.entrySet()) {
            if (stack.is(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Gives every HL2 weapon the player doesn't already carry. */
    public static void giveKit(ServerPlayer player) {
        for (Weapon w : Weapon.values()) {
            Item item = ITEMS.get(w);
            if (!player.getInventory().contains(s -> s.is(item))) {
                player.getInventory().add(new ItemStack(item));
            }
        }
    }

    /** F9: back to the start. Morning, full health and food, the whole kit, hostiles cleared, a fresh wave of zombies. */
    public static void reset(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.clearFire();
        giveKit(player);
        ServerLevel level = player.level();
        // A clean arena: morning (so you can see them coming), last wave gone, and no stray creepers or
        // skeletons within 48 blocks. Runs the vanilla command as the server; the demo world itself has no cheats.
        level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withSuppressedOutput(), "time set day");
        List<Entity> old = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            boolean strayHostile = e instanceof Enemy && e.distanceToSqr(player) < 48 * 48;
            if (e.entityTags().contains(WAVE_TAG) || strayHostile) {
                old.add(e);
            }
        }
        old.forEach(Entity::discard);
        GRENADES.forEach(g -> g.entity().discard());
        GRENADES.clear();
        spawnWave(player);
        if (java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("user.home"), ".hl2craft-showcase"))) {
            // Recording a showcase with scripted (bad) aim: an HEV-suit-like buff so the clip shows the guns,
            // not the death screen. Only when this marker file exists; normal play is unchanged.
            player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 120, 3, false, false), null);
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 120, 2, false, false), null);
        }
        player.sendOverlayMessage(Component.literal("HL2Craft reset: " + WAVE_SIZE + " zombies incoming"));
    }

    /** 15 zombies in a ring 14-24 blocks out, capped so daylight doesn't burn them before you get to. */
    public static void spawnWave(ServerPlayer player) {
        ServerLevel level = player.level();
        for (int i = 0; i < WAVE_SIZE; i++) {
            Zombie z = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            if (z == null) {
                continue;
            }
            double angle = (Math.PI * 2 * i) / WAVE_SIZE + player.getRandom().nextDouble() * 0.3;
            double dist = 14 + player.getRandom().nextDouble() * 10;
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * dist);
            int zc = (int) Math.floor(player.getZ() + Math.sin(angle) * dist);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, zc);
            z.setPos(x + 0.5, y, zc + 0.5);
            z.setPersistenceRequired();
            z.addTag(WAVE_TAG);
            // A cap keeps them from burning in daylight (vanilla rule), so they're readable on screen and alive
            // long enough to shoot. Not dropped on death.
            z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            z.setDropChance(EquipmentSlot.HEAD, 0.0F);
            z.setTarget(player);
            level.addFreshEntity(z);
        }
    }

    private static void spawnTarget(ServerPlayer player) {
        ServerLevel level = player.level();
        Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        if (zombie == null) {
            return;
        }
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
        zombie.setPos(player.getX() + flat.x * 6, player.getY(), player.getZ() + flat.z * 6);
        zombie.setYRot(player.getYRot() + 180);
        zombie.setYHeadRot(player.getYRot() + 180);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.addTag(WAVE_TAG);
        level.addFreshEntity(zombie);
    }

    /**
     * Server side. Returns true if the weapon fired (the client mirrors it in HL2). Ammo is unlimited, so the
     * only gate is the refire cooldown.
     */
    public static boolean fire(ServerPlayer player, boolean alt) {
        ItemStack stack = player.getMainHandItem();
        Weapon w = weaponOf(stack);
        if (w == null || w == Weapon.CROWBAR || player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }
        switch (w.kind) {
            case HITSCAN -> {
                if (alt && w == Weapon.SMG) {
                    throwGrenade(player, 1.6, true);  // SMG1 contact grenade
                    player.getCooldowns().addCooldown(stack, 20);
                    return true;
                }
                int pellets = alt && w == Weapon.SHOTGUN ? 12 : w.pellets;  // both barrels
                for (int i = 0; i < pellets; i++) {
                    hitscan(player, w.damage, RANGE, w.spread);
                }
                player.getCooldowns().addCooldown(stack, alt ? w.refireTicks + 6 : w.refireTicks);
            }
            case THROW -> {
                throwGrenade(player, alt ? 0.6 : 1.2, false);  // right click lobs it short
                player.getCooldowns().addCooldown(stack, w.refireTicks);
            }
            case PUNT -> {
                punt(player, w.damage);
                player.getCooldowns().addCooldown(stack, w.refireTicks);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void hitscan(ServerPlayer player, float damage, double range, double spread) {
        ServerLevel level = player.level();
        Vec3 from = player.getEyePosition();
        Vec3 dir = player.getLookAngle()
                .add(player.getRandom().triangle(0, spread), player.getRandom().triangle(0, spread),
                        player.getRandom().triangle(0, spread))
                .normalize();
        Vec3 to = from.add(dir.scale(range));

        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        AABB box = player.getBoundingBox().expandTowards(end.subtract(from)).inflate(1.0);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(
                player, from, end, box, e -> e.isPickable() && !e.isSpectator() && e != player, from.distanceToSqr(end));

        if (entity != null && entity.getEntity() instanceof LivingEntity target) {
            Vec3 hit = entity.getLocation();
            boolean head = hit.y >= target.getEyeY() - 0.25;
            target.setInvulnerableTime(0);  // every pellet and SMG round counts, as in HL2
            target.hurtServer(level, level.damageSources().playerAttack(player), head ? damage * HEADSHOT_MULTIPLIER : damage);
            level.sendParticles(head ? ParticleTypes.CRIT : ParticleTypes.DAMAGE_INDICATOR, hit.x, hit.y, hit.z, head ? 8 : 3, 0.05, 0.05, 0.05, 0.1);
        } else if (entity != null) {
            entity.getEntity().hurtServer(level, level.damageSources().playerAttack(player), damage);
        } else if (block.getType() == HitResult.Type.BLOCK) {
            Vec3 hit = block.getLocation();
            level.sendParticles(ParticleTypes.SMOKE, hit.x, hit.y, hit.z, 4, 0.02, 0.02, 0.02, 0.01);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, hit.x, hit.y, hit.z, 2, 0.02, 0.02, 0.02, 0.05);
        }
    }

    /** Gravity gun primary: punt the first entity within 12 blocks. */
    private static void punt(ServerPlayer player, float damage) {
        ServerLevel level = player.level();
        Vec3 from = player.getEyePosition();
        Vec3 dir = player.getLookAngle();
        Vec3 to = from.add(dir.scale(12));
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        AABB box = player.getBoundingBox().expandTowards(end.subtract(from)).inflate(1.5);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, from, end, box, e -> !e.isSpectator() && e != player, from.distanceToSqr(end));
        if (hit == null) {
            return;
        }
        Entity e = hit.getEntity();
        if (e instanceof LivingEntity living) {
            living.setInvulnerableTime(0);
            living.hurtServer(level, level.damageSources().playerAttack(player), damage);
        }
        e.push(dir.x * 2.2, 0.45 + dir.y * 1.2, dir.z * 2.2);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY(0.5), e.getZ(), 12, 0.2, 0.3, 0.2, 0.2);
    }

    private static void throwGrenade(ServerPlayer player, double speed, boolean impact) {
        ServerLevel level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getLookAngle();
        ItemEntity e = new ItemEntity(level, eye.x + dir.x * 0.6, eye.y - 0.2 + dir.y * 0.6, eye.z + dir.z * 0.6,
                new ItemStack(ITEMS.get(Weapon.GRENADE)));
        e.setNeverPickUp();
        e.setDeltaMovement(dir.scale(speed).add(0, 0.2, 0));
        level.addFreshEntity(e);
        // HL2 frag: 3 s fuse (here 2.5 s); the SMG grenade bursts on contact, or after 3 s.
        GRENADES.add(new Grenade(e, player, level.getServer().getTickCount() + (impact ? 60 : 50), impact));
    }

    private static void tickGrenades(MinecraftServer server) {
        int now = server.getTickCount();
        for (Iterator<Grenade> it = GRENADES.iterator(); it.hasNext(); ) {
            Grenade g = it.next();
            ItemEntity e = g.entity();
            boolean hit = g.impact() && now > g.explodeAt() - 57 && (e.onGround() || e.horizontalCollision);
            if (e.isRemoved() || now >= g.explodeAt() || hit) {
                if (!e.isRemoved()) {
                    // HL2 frag: 150 damage in 250 units (~6 blocks). No block damage in the demo world.
                    e.level().explode(g.owner(), e.getX(), e.getY(), e.getZ(), 3.5F, Level.ExplosionInteraction.NONE);
                    e.discard();
                }
                it.remove();
            }
        }
    }
}
