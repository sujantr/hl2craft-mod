package dev.hl2craft.client;

import dev.hl2craft.HL2Craft;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends console commands to a running HL2 through cfg/hl2craft/hc_N.cfg (see hl2cfg/hl2craft.cfg).
 *
 * HL2 polls for the next file every two frames and each file points the poll at N+1, so every command runs once.
 * HL2's console.log (-condebug) tells us which N it is waiting for, which survives HL2 restarts. File IO runs
 * on a single background thread so the render thread never waits on the disk.
 */
public final class HL2Bridge {
    private static final Pattern WAITING = Pattern.compile("'hl2craft/hc_(\\d+)' not present");
    private static final Pattern ACK = Pattern.compile("HL2CRAFT ack (\\d+)");
    private static final String LOADED = "HL2CRAFT bridge loaded";

    private final Path cfgDir;
    private final Path consoleLog;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hl2craft-bridge");
        t.setDaemon(true);
        return t;
    });
    private int next = -1;

    public HL2Bridge(Path hl2GameDir) {
        this.cfgDir = hl2GameDir.resolve("cfg").resolve("hl2craft");
        this.consoleLog = hl2GameDir.resolve("console.log");
    }

    public void send(String commands) {
        io.execute(() -> {
            try {
                write(commands);
            } catch (IOException e) {
                HL2Craft.LOG.warn("HL2 bridge write failed: {}", e.toString());
            }
        });
    }

    private void write(String commands) throws IOException {
        Files.createDirectories(cfgDir);
        int expected = expectedNext();
        if (next < 0 || expected > next || (expected >= 0 && expected < next && !Files.exists(file(expected)))) {
            // First send, or HL2 restarted / moved on: follow HL2.
            next = Math.max(expected, 0);
        }
        int n = next++;
        String body = commands + "\n"
                + "echo \"HL2CRAFT ack " + n + "\"\n"
                + "alias hc_next \"exec hl2craft/hc_" + (n + 1) + "\"\n";
        Path tmp = cfgDir.resolve(".hc_" + n + ".tmp");
        Files.writeString(tmp, body, StandardCharsets.US_ASCII);
        Files.move(tmp, file(n), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(file(n - 4));
    }

    private Path file(int n) {
        return cfgDir.resolve("hc_" + n + ".cfg");
    }

    /** The command file HL2 is polling for, from the newest relevant console.log line; -1 if unknown. */
    private int expectedNext() {
        String tail = tail(64 * 1024);
        int best = -1, bestAt = -1;
        Matcher m = WAITING.matcher(tail);
        while (m.find()) {
            if (m.start() > bestAt) { bestAt = m.start(); best = Integer.parseInt(m.group(1)); }
        }
        m = ACK.matcher(tail);
        while (m.find()) {
            if (m.start() > bestAt) { bestAt = m.start(); best = Integer.parseInt(m.group(1)) + 1; }
        }
        int loaded = tail.lastIndexOf(LOADED);
        if (loaded > bestAt) {
            best = 0;
        }
        return best;
    }

    private String tail(int bytes) {
        try (RandomAccessFile f = new RandomAccessFile(consoleLog.toFile(), "r")) {
            long start = Math.max(0, f.length() - bytes);
            byte[] buf = new byte[(int) (f.length() - start)];
            f.seek(start);
            f.readFully(buf);
            return new String(buf, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return "";
        }
    }

    public boolean isHl2Running() {
        try {
            return Files.exists(consoleLog)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(consoleLog).toMillis() < 3000;
        } catch (IOException e) {
            return false;
        }
    }
}
