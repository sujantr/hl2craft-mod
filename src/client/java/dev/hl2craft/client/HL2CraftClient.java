package dev.hl2craft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hl2craft.HL2Craft;
import dev.hl2craft.Weapon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/**
 * Client half of the passthrough: Minecraft input -> HL2 console commands (HL2Bridge), and HL2's keyed
 * first-person view -> a HUD layer over the Minecraft world (HL2Frame). The vanilla held-item render is hidden
 * by the items' display_context model (assets/hl2craft/items/*.json).
 *
 * Left click = HL2 +attack, right click = +attack2 (SMG grenade, shotgun double, grenade lob), R = +reload,
 * F9 = reset both games to the start.
 */
public class HL2CraftClient implements ClientModInitializer {
    private static final Identifier HUD_LAYER = Identifier.fromNamespaceAndPath(HL2Craft.MOD_ID, "viewmodel");
    private static final String DEFAULT_HL2_DIR = System.getProperty("user.home")
            + "/Applications/Sikarugir/Untitled.app/Contents/SharedSupport/prefix/drive_c/Program Files (x86)"
            + "/Steam/steamapps/common/Half-Life 2/hl2";
    private static final int TAP_MS = 80;

    private static HL2Bridge bridge;
    private static HL2Frame frame;
    private static KeyMapping reloadKey;
    private static KeyMapping resetKey;
    private static Weapon lastHeld;
    private static boolean attackWasDown;
    private static boolean hl2AttackHeld;
    private static boolean joined;
    private static boolean staged;
    private static Object lastPlayer;

    // Viewmodel sway: the HL2 gun lags behind Minecraft's camera like HL2's own view lag.
    private static float lastYaw, lastPitch, swayX, swayY;
    private static long lastSwayNanos;

    @Override
    public void onInitializeClient() {
        Properties cfg = loadConfig();
        bridge = new HL2Bridge(Path.of(cfg.getProperty("hl2_dir", DEFAULT_HL2_DIR)));
        frame = new HL2Frame(Path.of(cfg.getProperty("frame_file",
                System.getProperty("user.home") + "/Library/Caches/hl2craft/frame.rgba")));

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(HL2Craft.MOD_ID, "controls"));
        reloadKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.hl2craft.reload", InputConstants.KEY_R, category));
        resetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.hl2craft.reset", InputConstants.KEY_F9, category));

        // With an HL2 gun out, left click fires instead of mining.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            Weapon w = HL2Craft.weaponOf(player.getMainHandItem());
            return w != null && w != Weapon.CROWBAR ? InteractionResult.FAIL : InteractionResult.PASS;
        });
        // START runs before Minecraft.handleKeybinds, so clicks consumed here never reach vanilla attack/use.
        ClientTickEvents.START_CLIENT_TICK.register(HL2CraftClient::tick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, HUD_LAYER, HL2CraftClient::renderHud);
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        Path file = FabricLoader.getInstance().getConfigDir().resolve("hl2craft.properties");
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
            } else {
                Files.writeString(file, "# Folder of the HL2 game (contains cfg/ and console.log)\nhl2_dir=" + DEFAULT_HL2_DIR
                        + "\n# Written by capture/hl2capture\n#frame_file=\n");
            }
        } catch (IOException e) {
            HL2Craft.LOG.warn("hl2craft.properties: {}", e.toString());
        }
        return p;
    }

    private static void tick(Minecraft client) {
        if (client.player == null) {
            joined = false;
            lastPlayer = null;
            return;
        }
        if (!joined) {
            joined = true;
            staged = false;
            lastHeld = null;
        }
        if (client.player != lastPlayer) {
            // Every gun at the start, and again after a respawn (dying drops them). A respawn keeps the entity id
            // but makes a new LocalPlayer, so compare the object.
            lastPlayer = client.player;
            onServer(client, sp -> { HL2Craft.giveKit(sp); return false; }, () -> { });
        }
        Weapon now = HL2Craft.weaponOf(client.player.getMainHandItem());
        if (now != lastHeld) {
            if (hl2AttackHeld) {
                bridge.send("-attack");
                hl2AttackHeld = false;
            }
            if (now != null) {
                if (!staged) {
                    // HL2 may sit at its menu (its background map replaces any startup "map"), so always load the
                    // stage map, wait for the player, then stage: weapons, sv_infinite_ammo, "viewmodel only".
                    Weapon held = now;
                    bridge.resetAndStage(() -> "use " + held.hl2);
                    staged = true;
                } else {
                    bridge.send("use " + now.hl2);
                }
            }
            lastHeld = now;
        }

        if (resetKey.consumeClick()) {
            onServer(client, sp -> { HL2Craft.reset(sp); return false; }, () -> { });
            bridge.resetAndStage(() -> {
                Weapon held = HL2Craft.weaponOf(Minecraft.getInstance().player.getMainHandItem());
                return held != null ? "use " + held.hl2 : "";
            });
            staged = true;
        }

        boolean inGame = client.gui.screen() == null;
        boolean attackDown = client.options.keyAttack.isDown();
        if (now != null && now != Weapon.CROWBAR && inGame) {
            if (now.automatic) {
                // SMG: hold to fire. HL2 holds +attack for as long as the button is down.
                while (client.options.keyAttack.consumeClick()) { }
                if (attackDown) {
                    onServer(client, sp -> HL2Craft.fire(sp, false), () -> { });
                }
                if (attackDown != hl2AttackHeld) {
                    if (HL2Bridge.TRACE) {
                        HL2Craft.LOG.info("hl2 input t={} attack {}", System.currentTimeMillis(), attackDown ? "down" : "up");
                    }
                    bridge.send(attackDown ? "+attack" : "-attack");
                    hl2AttackHeld = attackDown;
                }
            } else {
                while (client.options.keyAttack.consumeClick()) {
                    fire(client, false);
                }
            }
            boolean altWeapon = now.hasAltFire() || now == Weapon.GRENADE;
            while (altWeapon && client.options.keyUse.consumeClick()) {
                fire(client, true);
            }
            while (reloadKey.consumeClick()) {
                bridge.tap("reload", TAP_MS);
            }
            // Mining stays blocked by the AttackBlockCallback, so the key is left alone (the SMG needs isDown).
        } else if (now == Weapon.CROWBAR && attackDown && !attackWasDown && inGame) {
            bridge.tap("attack", TAP_MS);  // vanilla still does the hit; HL2 swings and sounds
        }
        attackWasDown = attackDown;
    }

    /**
     * Tell HL2 at once (prediction: ammo is unlimited, so only the cooldown can refuse) and let the server do the
     * hit. Waiting for the server first cost a tick or two of latency.
     */
    private static void fire(Minecraft client, boolean alt) {
        ItemStack stack = client.player.getMainHandItem();
        if (client.player.getCooldowns().isOnCooldown(stack)) {
            return;
        }
        bridge.tap(alt ? "attack2" : "attack", TAP_MS);
        onServer(client, sp -> HL2Craft.fire(sp, alt), () -> { });
        if (HL2Craft.weaponOf(stack) == Weapon.GRENADE) {
            // After a throw HL2 switches to another gun as if the frag were used up (sv_infinite_ammo or not).
            bridge.sendLater("use weapon_frag", 1200);
        }
    }

    /** Run the action on the integrated server; if it returns true, run inHl2 too. */
    private static void onServer(Minecraft client, java.util.function.Predicate<ServerPlayer> action, Runnable inHl2) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            client.player.sendOverlayMessage(Component.literal("HL2Craft weapons work in single-player only"));
            return;
        }
        UUID id = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null && action.test(sp)) {
                inHl2.run();
            }
        });
    }

    private static void updateSway(Minecraft client) {
        long t = System.nanoTime();
        float dt = lastSwayNanos == 0 ? 0 : Math.min(0.1F, (t - lastSwayNanos) / 1e9F);
        lastSwayNanos = t;
        float yaw = client.player.getYRot(), pitch = client.player.getXRot();
        float dYaw = yaw - lastYaw, dPitch = pitch - lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;
        if (dt == 0 || Math.abs(dYaw) > 90) {
            return;
        }
        // Spring back to rest; turning pushes the gun the other way (HL2's view lag feel).
        float k = (float) Math.exp(-dt * 9.0);
        swayX = Math.max(-40, Math.min(40, swayX * k - dYaw * 1.2F));
        swayY = Math.max(-30, Math.min(30, swayY * k - dPitch * 1.0F));
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        Weapon now = HL2Craft.weaponOf(client.player.getMainHandItem());
        updateSway(client);
        if (now == null) {
            return;
        }
        int w = graphics.guiWidth(), h = graphics.guiHeight();
        if (frame.update(client)) {
            // HL2's view, full width, anchored to the bottom so the gun sits where HL2 draws it. A little overscan
            // keeps the edges covered while the gun sways.
            int fw = w + 16;
            int fh = fw * frame.height() / frame.width();
            int x = (int) (-8 + swayX * w / 1000F);
            int y = (int) (h - fh + 6 + swayY * h / 1000F);
            graphics.blit(HL2Frame.TEXTURE, x, y, x + fw, y + fh, 0, 1, 0, 1);
        } else {
            graphics.centeredText(client.font, bridge.isHl2Running()
                    ? "HL2Craft: waiting for frames (run capture/hl2capture)"
                    : "HL2Craft: start Half-Life 2 (+exec hl2craft) and capture/hl2capture", w / 2, h - 60, 0xFFFFC040);
        }
        if (now != Weapon.CROWBAR) {
            String ammo = "∞";
            graphics.text(client.font, ammo, w - client.font.width(ammo) - 14, h - 52, 0xFFFFD040);
        }
    }
}
