package dev.hl2craft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HL2Craft: Half-Life 2 weapons in Minecraft. Minecraft owns the gameplay (hits, damage, ammo); a live HL2
 * process renders the first-person weapon and plays its sounds (see the client side).
 *
 * Numbers follow HL2's skill.cfg on Normal, scaled by HL2 zombie health (50) to Minecraft zombie health (20):
 * pistol 5 -> 2.0, crowbar 10 -> 4.0. Pistol clip 18, as in HL2. Single-player only: the client calls the
 * integrated server directly.
 */
public class HL2Craft implements ModInitializer {
    public static final String MOD_ID = "hl2craft";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static final int PISTOL_CLIP = 18;
    public static final float PISTOL_DAMAGE = 2.0F;
    public static final float HEADSHOT_MULTIPLIER = 3.0F;  // HL2 sk_npc_head
    public static final double PISTOL_RANGE = 64.0;
    public static final double PISTOL_SPREAD = 0.017;      // ~1 degree, HL2 VECTOR_CONE_1DEGREES while aiming steady
    public static final int PISTOL_REFIRE_TICKS = 2;       // HL2 lets you fire as fast as you click, ~0.1 s minimum
    public static final int PISTOL_RELOAD_TICKS = 30;      // v_pistol reload sequence is ~1.5 s

    public static Item PISTOL;
    public static Item CROWBAR;

    private static Item register(String name, java.util.function.Function<Item.Properties, Item> factory, Item.Properties props) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, name));
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(props.setId(key)));
    }

    @Override
    public void onInitialize() {
        // Clip = durability: damage value counts the shots fired from the current magazine.
        PISTOL = register("pistol", Item::new, new Item.Properties().stacksTo(1).durability(PISTOL_CLIP));
        // HL2 crowbar: 10 damage, 0.4 s per swing -> 4.0 damage, 2.5 swings/s (player base 1 + iron 2 + 1).
        CROWBAR = register("crowbar", Item::new, new Item.Properties().sword(ToolMaterial.IRON, 1.0F, -1.5F));
        // The demo world has no cheats, so /give is out: /hl2craft hands out the kit.
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                dispatcher.register(Commands.literal("hl2craft")
                        .executes(ctx -> {
                            giveKit(ctx.getSource().getPlayerOrException());
                            ctx.getSource().sendSuccess(() -> Component.literal("HL2Craft: crowbar and 9mm pistol"), false);
                            return 1;
                        })
                        // Test target: a zombie standing still 6 blocks ahead, so shots are repeatable.
                        .then(Commands.literal("zombie").executes(ctx -> {
                            spawnTarget(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))));
        LOG.info("HL2Craft items registered");
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
        level.addFreshEntity(zombie);
    }

    /** Gives the crowbar and pistol unless the player already has them. */
    public static void giveKit(ServerPlayer player) {
        if (!player.getInventory().contains(HL2Craft::isCrowbar)) {
            player.getInventory().add(new ItemStack(CROWBAR));
        }
        if (!player.getInventory().contains(HL2Craft::isPistol)) {
            player.getInventory().add(new ItemStack(PISTOL));
        }
    }

    public static boolean isPistol(ItemStack stack) {
        return PISTOL != null && stack.is(PISTOL);
    }

    public static boolean isCrowbar(ItemStack stack) {
        return CROWBAR != null && stack.is(CROWBAR);
    }

    /** Server side. Returns true if a round was fired (the client then tells HL2 to fire). */
    public static boolean firePistol(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!isPistol(stack) || player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }
        if (stack.getDamageValue() >= PISTOL_CLIP) {
            player.sendOverlayMessage(Component.literal("Reload (R)"));
            return false;
        }
        stack.setDamageValue(stack.getDamageValue() + 1);
        player.getCooldowns().addCooldown(stack, PISTOL_REFIRE_TICKS);
        hitscan(player, PISTOL_DAMAGE, PISTOL_RANGE, PISTOL_SPREAD);
        return true;
    }

    /** Server side. Returns true if a reload started. */
    public static boolean reloadPistol(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!isPistol(stack) || stack.getDamageValue() == 0 || player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }
        stack.setDamageValue(0);
        player.getCooldowns().addCooldown(stack, PISTOL_RELOAD_TICKS);
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
            target.hurtServer(level, level.damageSources().playerAttack(player), head ? damage * HEADSHOT_MULTIPLIER : damage);
            level.sendParticles(head ? ParticleTypes.CRIT : ParticleTypes.DAMAGE_INDICATOR, hit.x, hit.y, hit.z, head ? 8 : 3, 0.05, 0.05, 0.05, 0.1);
        } else if (entity != null) {
            Entity other = entity.getEntity();
            other.hurtServer(level, level.damageSources().playerAttack(player), damage);
        } else if (block.getType() == HitResult.Type.BLOCK) {
            Vec3 hit = block.getLocation();
            level.sendParticles(ParticleTypes.SMOKE, hit.x, hit.y, hit.z, 4, 0.02, 0.02, 0.02, 0.01);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, hit.x, hit.y, hit.z, 2, 0.02, 0.02, 0.02, 0.05);
        }
    }
}
