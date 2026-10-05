package dev.hl2craft.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads the keyed HL2 frames that capture/hl2capture writes (layout documented there) into a DynamicTexture.
 * RGBA bytes in the file are already NativeImage's memory layout, so a frame is one memcpy.
 */
public final class HL2Frame {
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("hl2craft", "dynamic/viewmodel");

    private final Path file;
    private MappedByteBuffer map;
    private DynamicTexture texture;
    private int width, height;
    private long lastSeq = -1;
    private long lastFrameMillis;

    public HL2Frame(Path file) {
        this.file = file;
    }

    /** Uploads the newest frame if there is one. Returns true when a recent frame is ready to draw. */
    public boolean update(Minecraft client) {
        if (map == null && !open()) {
            return false;
        }
        long seq = map.getLong(16);
        if (seq != lastSeq && seq > 0) {
            lastSeq = seq;
            lastFrameMillis = map.getLong(24);
            if (texture == null) {
                texture = new DynamicTexture(() -> "hl2craft-viewmodel", new NativeImage(width, height, false));
                client.getTextureManager().register(TEXTURE, texture);
            }
            int slot = 32 + (int) (seq & 1) * width * height * 4;
            MemoryUtil.memCopy(MemoryUtil.memAddress(map) + slot, texture.getPixels().getPointer(), (long) width * height * 4);
            texture.upload();
        }
        return texture != null && System.currentTimeMillis() - lastFrameMillis < 2000;
    }

    public int width() { return width; }
    public int height() { return height; }

    private boolean open() {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
                m.order(ByteOrder.LITTLE_ENDIAN);
                if (m.get(0) != 'H' || m.get(1) != 'C' || m.get(2) != '2' || m.get(3) != 'F') {
                    return false;
                }
                width = m.getInt(8);
                height = m.getInt(12);
                if (ch.size() < 32 + 2L * width * height * 4) {
                    return false;
                }
                map = m;
                return true;
            }
        } catch (IOException e) {
            return false;
        }
    }
}
