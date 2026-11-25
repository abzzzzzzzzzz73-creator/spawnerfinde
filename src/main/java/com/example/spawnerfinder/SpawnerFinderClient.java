package com.example.spawnerfinder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class SpawnerFinderClient implements ClientModInitializer {
    public static final SpawnerStore STORE = new SpawnerStore();
    /** Моб, чьи спавнеры сейчас показываются (null = метки спавнеров выключены). */
    public static String trackedMob = null;
    /** Подсветка и подписи вагонеток (читается ещё и из EntityMixin). */
    public static volatile boolean showMinecarts = true;

    private static final List<AbstractMinecartEntity> minecarts = new ArrayList<>();

    private static KeyBinding openKey;
    private static KeyBinding minecartKey;
    private static final Map<ChunkPos, Integer> pending = new HashMap<>();
    private static int tick = 0;

    @Override
    public void onInitializeClient() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spawnerfinder.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.spawnerfinder"));
        minecartKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spawnerfinder.toggle_minecarts", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.spawnerfinder"));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            STORE.load(serverKey(client));
            pending.clear();
            minecarts.clear();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            STORE.save();
            pending.clear();
            minecarts.clear();
            trackedMob = null;
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> STORE.save());

        // Данные блока-спавнера приходят чуть позже самого чанка, поэтому сканируем с задержкой.
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> pending.put(chunk.getPos(), tick + 5));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tick++;
            while (openKey.wasPressed()) {
                if (client.currentScreen == null && client.world != null) client.setScreen(new SpawnerScreen());
            }
            while (minecartKey.wasPressed()) {
                showMinecarts = !showMinecarts;
                if (client.player != null) {
                    client.player.sendMessage(net.minecraft.text.Text.literal(
                            "Вагонетки: " + (showMinecarts ? "ВКЛ" : "ВЫКЛ")), true);
                }
            }
            if (client.world != null) {
                processPending(client.world);
                if (tick % 5 == 0) scanMinecarts(client.world);
            }
            if (tick % 1200 == 0) STORE.save();
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> renderHud(ctx));
        WorldRenderEvents.AFTER_ENTITIES.register(SpawnerFinderClient::renderWorldLabels);
    }

    private static String serverKey(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        if (info != null) return info.address;
        if (client.getServer() != null) return "singleplayer_" + client.getServer().getSaveProperties().getLevelName();
        return "unknown";
    }

    // ---------- спавнеры ----------

    private static void processPending(ClientWorld world) {
        if (pending.isEmpty()) return;
        Iterator<Map.Entry<ChunkPos, Integer>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos, Integer> e = it.next();
            if (e.getValue() > tick) continue;
            it.remove();
            scanChunk(world, e.getKey());
        }
    }

    private static void scanChunk(ClientWorld world, ChunkPos cp) {
        WorldChunk chunk = world.getChunkManager().getWorldChunk(cp.x, cp.z);
        if (chunk == null) return;
        String dim = world.getRegistryKey().getValue().toString();
        List<SpawnerStore.Entry> found = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof MobSpawnerBlockEntity)) continue;
            NbtCompound nbt = be.createNbt(world.getRegistryManager());
            String id = nbt.getCompound("SpawnData").getCompound("entity").getString("id");
            if (id.isEmpty()) continue;
            BlockPos p = be.getPos();
            found.add(new SpawnerStore.Entry(dim, p.getX(), p.getY(), p.getZ(), id));
        }
        STORE.replaceChunk(dim, cp.x, cp.z, found);
    }

    // ---------- вагонетки ----------

    private static void scanMinecarts(ClientWorld world) {
        minecarts.clear();
        for (Entity e : world.getEntities()) {
            if (e instanceof AbstractMinecartEntity m && !m.isRemoved()) minecarts.add(m);
        }
    }

    private static String minecartName(AbstractMinecartEntity m) {
        return m.getType().getName().getString(); // "Вагонетка", "Вагонетка с сундуком" и т.д. (на языке игры)
    }

    private static int minecartColor(AbstractMinecartEntity m) {
        return m.getType() == EntityType.CHEST_MINECART ? 0xFFFFD700 : 0xFFFFFFFF;
    }

    // ---------- HUD ----------

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private static String arrow(MinecraftClient mc, Vec3d pp, double tx, double tz) {
        double dx = tx - pp.x, dz = tz - pp.z;
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = MathHelper.wrapDegrees(targetYaw - mc.player.getYaw());
        return ARROWS[Math.floorMod((int) Math.round(rel / 45.0), 8)];
    }

    private static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
        Vec3d pp = mc.player.getPos();
        int y = 6;

        if (trackedMob != null) {
            String dim = mc.world.getRegistryKey().getValue().toString();
            List<SpawnerStore.Entry> list = STORE.byMob(trackedMob, dim);
            list.sort(Comparator.comparingDouble(e -> distSq(pp, e)));
            ctx.drawTextWithShadow(mc.textRenderer,
                    "Спавнеры: " + prettyName(trackedMob) + " (" + list.size() + ")", 6, y, 0xFFFFAA00);
            y += 11;
            for (int i = 0; i < Math.min(5, list.size()); i++) {
                SpawnerStore.Entry e = list.get(i);
                String line = arrow(mc, pp, e.x() + 0.5, e.z() + 0.5) + " " + (int) Math.sqrt(distSq(pp, e))
                        + " м  (" + e.x() + " " + e.y() + " " + e.z() + ")";
                ctx.drawTextWithShadow(mc.textRenderer, line, 6, y, 0xFFFFFFFF);
                y += 10;
            }
            y += 4;
        }

        if (showMinecarts && !minecarts.isEmpty()) {
            List<AbstractMinecartEntity> list = new ArrayList<>(minecarts);
            list.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(pp)));
            ctx.drawTextWithShadow(mc.textRenderer, "Вагонетки рядом (" + list.size() + ")", 6, y, 0xFF55FFFF);
            y += 11;
            for (int i = 0; i < Math.min(5, list.size()); i++) {
                AbstractMinecartEntity m = list.get(i);
                String line = arrow(mc, pp, m.getX(), m.getZ()) + " " + minecartName(m) + " "
                        + (int) Math.sqrt(m.squaredDistanceTo(pp)) + " м";
                ctx.drawTextWithShadow(mc.textRenderer, line, 6, y, minecartColor(m));
                y += 10;
            }
        }
    }

    // ---------- подписи в мире (видны сквозь стены) ----------

    private static void renderWorldLabels(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        MatrixStack matrices = ctx.matrixStack();
        VertexConsumerProvider consumers = ctx.consumers();
        if (matrices == null || consumers == null) return;
        Camera camera = ctx.camera();
        Vec3d cam = camera.getPos();
        Vec3d pp = mc.player.getPos();

        if (showMinecarts) {
            for (AbstractMinecartEntity m : minecarts) {
                if (m.isRemoved()) continue;
                int dist = (int) Math.sqrt(m.squaredDistanceTo(pp));
                drawLabel(mc, matrices, consumers, camera, cam, m.getPos().add(0, 1.2, 0),
                        minecartName(m) + " " + dist + " м", minecartColor(m));
            }
        }

        if (trackedMob != null) {
            String dim = mc.world.getRegistryKey().getValue().toString();
            List<SpawnerStore.Entry> list = STORE.byMob(trackedMob, dim);
            list.sort(Comparator.comparingDouble(e -> distSq(pp, e)));
            for (int i = 0; i < Math.min(10, list.size()); i++) {
                SpawnerStore.Entry e = list.get(i);
                drawLabel(mc, matrices, consumers, camera, cam, new Vec3d(e.x() + 0.5, e.y() + 1.3, e.z() + 0.5),
                        "Спавнер: " + prettyName(e.mob()) + " " + (int) Math.sqrt(distSq(pp, e)) + " м", 0xFFFFAA00);
            }
        }

        if (consumers instanceof VertexConsumerProvider.Immediate imm) imm.draw();
    }

    private static void drawLabel(MinecraftClient mc, MatrixStack matrices, VertexConsumerProvider consumers,
                                  Camera camera, Vec3d cam, Vec3d pos, String text, int color) {
        double dx = pos.x - cam.x, dy = pos.y - cam.y, dz = pos.z - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float s = (float) (0.003 * Math.max(dist, 6.0)); // примерно одинаковый размер текста на любом расстоянии

        matrices.push();
        matrices.translate(dx, dy, dz);
        matrices.multiply(camera.getRotation());
        matrices.scale(-s, -s, s);
        TextRenderer tr = mc.textRenderer;
        float w = tr.getWidth(text);
        tr.draw(text, -w / 2f, 0f, color, false, matrices.peek().getPositionMatrix(), consumers,
                TextRenderer.TextLayerType.SEE_THROUGH, 0x66000000, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();
    }

    // ---------- утилиты ----------

    public static double distSq(Vec3d p, SpawnerStore.Entry e) {
        double dx = e.x() + 0.5 - p.x, dy = e.y() + 0.5 - p.y, dz = e.z() + 0.5 - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public static String prettyName(String id) {
        return EntityType.get(id).map(t -> t.getName().getString()).orElse(id);
    }
}
