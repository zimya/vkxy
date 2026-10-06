package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Driver for {@code ./gradlew runTestWorld}. Active only when -Dvoxy.testWorld=true.
 * The world is joined through --quickPlaySingleplayer; once the player is in the world this
 * waits a little, spins the camera a full turn taking screenshots, then closes the game.
 */
public final class VoxyTestWorld {
    private static final int WARMUP_TICKS = 100;
    private static final int SHOTS = 8;
    private static final int TICKS_PER_SHOT = 45; // Slow down rotation: 1 degree per tick

    private static int tick = 0;
    private static int shots = 0;

    private VoxyTestWorld() {}

    public static boolean enabled() {
        return Boolean.getBoolean("voxy.testWorld");
    }

    public static void init() {
        if (!enabled()) return;
        Logger.info("Voxy test world driver enabled (ticksPerShot=" + TICKS_PER_SHOT + ", shots=" + SHOTS + ")");
        ClientTickEvents.END_CLIENT_TICK.register(VoxyTestWorld::onTick);
    }

    private static void onTick(Minecraft mc) {
        var player = mc.player;
        if (player == null || mc.level == null) return;
        tick++;
        if (tick < WARMUP_TICKS) {
            if (tick % 20 == 0) {
                Logger.info("Test world warmup: tick " + tick + "/" + WARMUP_TICKS);
            }
            return;
        }
        int t = tick - WARMUP_TICKS;
        // Smooth rotation every tick
        float yaw = (t * 360f / (SHOTS * TICKS_PER_SHOT)) % 360f;
        player.setYRot(yaw);
        player.setXRot(10f);

        if (t % 20 == 0) {
            var voxy = me.cortex.voxy.client.core.IVoxyRenderSystemHolder.getNullable();
            java.util.List<String> debug = new java.util.ArrayList<>();
            if (voxy != null) {
                voxy.addDebugInfo(debug);
            }
            Logger.info(String.format("Test world tick %d: yaw=%.1f, pos=(%.1f, %.1f, %.1f), voxy=%s",
                    t, yaw, player.getX(), player.getY(), player.getZ(), String.join("; ", debug)));
        }

        if (t % TICKS_PER_SHOT == TICKS_PER_SHOT - 1 && shots < SHOTS) {
            int n = shots++;
            var voxy = me.cortex.voxy.client.core.IVoxyRenderSystemHolder.getNullable();
            java.util.List<String> debug = new java.util.ArrayList<>();
            if (voxy != null) {
                voxy.addDebugInfo(debug);
            }
            Logger.info(String.format("Test world taking screenshot %d (yaw=%.1f, pos=(%.1f, %.1f, %.1f), voxy=%s)",
                    n, yaw, player.getX(), player.getY(), player.getZ(), String.join("; ", debug)));
            Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(), msg -> Logger.info("Screenshot " + n + ": " + msg.getString()));
        }
        if (shots >= SHOTS && t > SHOTS * TICKS_PER_SHOT + 40) {
            Logger.info("Test world finished, stopping");
            mc.stop();
        }
    }
}
