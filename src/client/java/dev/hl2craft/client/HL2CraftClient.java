package dev.hl2craft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hl2craft.HL2Craft;
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
 */
public class HL2CraftClient implements ClientModInitializer {
    private static final Identifier HUD_LAYER = Identifier.fromNamespaceAndPath(HL2Craft.MOD_ID, "viewmodel");
    private static final String DEFAULT_HL2_DIR = System.getProperty("user.home")
            + "/Applications/Sikarugir/Untitled.app/Contents/SharedSupport/prefix/drive_c/Program Files (x86)"
            + "/Steam/steamapps/common/Half-Life 2/hl2";

    private enum Held { NONE, CROWBAR, PISTOL }

    private static HL2Bridge bridge;
    private static HL2Frame frame;
    private static KeyMapping reloadKey;
    private static Held lastHeld = Held.NONE;
    private static boolean attackWasDown;
    private static boolean joined;
    private static boolean staged;

    @Override
    public void onInitializeClient() {
        Properties cfg = loadConfig();
        bridge = new HL2Bridge(Path.of(cfg.getProperty("hl2_dir", DEFAULT_HL2_DIR)));
        frame = new HL2Frame(Path.of(cfg.getProperty("frame_file",
                System.getProperty("user.home") + "/Library/Caches/hl2craft/frame.rgba")));

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(HL2Craft.MOD_ID, "controls"));
        reloadKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.hl2craft.reload", InputConstants.KEY_R, category));

        // Holding the pistol, left click fires instead of mining.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                HL2Craft.isPistol(player.getMainHandItem()) ? InteractionResult.FAIL : InteractionResult.PASS);
        // START runs before Minecraft.handleKeybinds, so clicks consumed here never reach vanilla attack/mining.
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

    private static Held held(ItemStack stack) {
        return HL2Craft.isPistol(stack) ? Held.PISTOL : HL2Craft.isCrowbar(stack) ? Held.CROWBAR : Held.NONE;
    }

    private static void tick(Minecraft client) {
        if (client.player == null) {
            joined = false;
            return;
        }
        if (!joined) {
            joined = true;
            staged = false;
            lastHeld = Held.NONE;
            onServer(client, sp -> { HL2Craft.giveKit(sp); return false; }, () -> { });
        }
        Held now = held(client.player.getMainHandItem());
        if (now != lastHeld) {
            if (now != Held.NONE && !staged) {
                // Weapons, ammo and the "viewmodel only" render settings (hl2cfg/hl2craft_stage.cfg).
                bridge.send("hc_stage");  // runs the stage once per HL2 launch (hl2cfg/hl2craft.cfg)
                staged = true;
            }
            if (now == Held.PISTOL) bridge.send("use weapon_pistol");
            if (now == Held.CROWBAR) bridge.send("use weapon_crowbar");
            lastHeld = now;
        }
        boolean attackDown = client.options.keyAttack.isDown();
        if (now == Held.PISTOL && client.gui.screen() == null) {
            while (client.options.keyAttack.consumeClick()) {
                onServer(client, sp -> HL2Craft.firePistol(sp), () -> bridge.tap("attack", 80));
            }
            client.options.keyAttack.setDown(false);  // no hold-to-mine with a gun out
            while (reloadKey.consumeClick()) {
                onServer(client, sp -> HL2Craft.reloadPistol(sp), () -> bridge.tap("reload", 80));
            }
        } else if (now == Held.CROWBAR && attackDown && !attackWasDown && client.gui.screen() == null) {
            bridge.tap("attack", 80);  // vanilla still does the hit; HL2 swings and sounds
        }
        attackWasDown = attackDown;
    }

    /** Run the action on the integrated server; if it succeeds, mirror it in HL2. */
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

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        ItemStack stack = client.player.getMainHandItem();
        Held now = held(stack);
        if (now == Held.NONE) {
            return;
        }
        int w = graphics.guiWidth(), h = graphics.guiHeight();
        if (frame.update(client)) {
            // HL2's 16:9 view, full width, anchored to the bottom so the gun sits where HL2 draws it.
            int fh = w * frame.height() / frame.width();
            graphics.blit(HL2Frame.TEXTURE, 0, h - fh, w, h, 0, 1, 0, 1);
        } else {
            graphics.centeredText(client.font, bridge.isHl2Running()
                    ? "HL2Craft: waiting for frames (run capture/hl2capture)"
                    : "HL2Craft: start Half-Life 2 (+exec hl2craft) and capture/hl2capture", w / 2, h - 60, 0xFFFFC040);
        }
        if (now == Held.PISTOL) {
            int clip = HL2Craft.PISTOL_CLIP - stack.getDamageValue();
            String ammo = clip + " / 18";
            graphics.text(client.font, ammo, w - client.font.width(ammo) - 12, h - 52, clip > 3 ? 0xFFFFD040 : 0xFFFF4020);
        }
    }
}
