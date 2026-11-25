package com.example.spawnerfinder;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class SpawnerScreen extends Screen {
    private static final int ROW_H = 12;

    private TextFieldWidget search;
    private ButtonWidget hudButton;
    private ButtonWidget minecartButton;
    private String selected = SpawnerFinderClient.trackedMob;
    private int mobPage, spawnPage, rows, ticks;
    private List<Map.Entry<String, Long>> mobs = new ArrayList<>();
    private List<SpawnerStore.Entry> spawners = new ArrayList<>();
    private String status = "";

    public SpawnerScreen() {
        super(Text.literal("Spawner Finder"));
    }

    private int lx() { return width / 2 - 200; }
    private int rx() { return lx() + 190; }
    private static final int TOP = 54;

    @Override
    protected void init() {
        rows = Math.max(3, (height - 110) / ROW_H);
        int lx = lx(), rx = rx(), by = height - 28;

        search = new TextFieldWidget(textRenderer, lx, 20, 180, 16, Text.literal("Поиск"));
        search.setPlaceholder(Text.literal("Поиск моба..."));
        search.setChangedListener(s -> { mobPage = 0; refresh(); });
        addDrawableChild(search);
        setInitialFocus(search);

        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { if (mobPage > 0) mobPage--; })
                .dimensions(lx, by, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { if ((mobPage + 1) * rows < mobs.size()) mobPage++; })
                .dimensions(lx + 24, by, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { if (spawnPage > 0) spawnPage--; })
                .dimensions(rx, by, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { if ((spawnPage + 1) * rows < spawners.size()) spawnPage++; })
                .dimensions(rx + 24, by, 20, 20).build());
        hudButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> toggleHud())
                .dimensions(rx + 48, by, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Файл"), b -> status = "Сохранено: " + SpawnerFinderClient.STORE.saveNow())
                .dimensions(rx + 152, by, 58, 20).build());

        minecartButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                    SpawnerFinderClient.showMinecarts = !SpawnerFinderClient.showMinecarts;
                    updateMinecartButton();
                })
                .dimensions(rx, 17, 140, 20).build());

        refresh();
        updateHudButton();
        updateMinecartButton();
    }

    private void updateMinecartButton() {
        minecartButton.setMessage(Text.literal(SpawnerFinderClient.showMinecarts ? "Вагонетки: ВКЛ" : "Вагонетки: ВЫКЛ"));
    }

    private void toggleHud() {
        if (selected == null) { status = "Сначала выбери моба слева"; return; }
        SpawnerFinderClient.trackedMob = selected.equals(SpawnerFinderClient.trackedMob) ? null : selected;
        updateHudButton();
    }

    private void updateHudButton() {
        boolean on = selected != null && selected.equals(SpawnerFinderClient.trackedMob);
        hudButton.setMessage(Text.literal(on ? "Метки: ВКЛ" : "Метки: ВЫКЛ"));
    }

    private void refresh() {
        if (client == null || client.world == null || client.player == null) return;
        String dim = client.world.getRegistryKey().getValue().toString();
        String q = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);

        mobs = new ArrayList<>();
        for (Map.Entry<String, Long> e : SpawnerFinderClient.STORE.mobCounts(dim).entrySet()) {
            if (q.isEmpty()
                    || e.getKey().toLowerCase(Locale.ROOT).contains(q)
                    || SpawnerFinderClient.prettyName(e.getKey()).toLowerCase(Locale.ROOT).contains(q)) {
                mobs.add(e);
            }
        }
        if (selected != null) {
            Vec3d p = client.player.getPos();
            spawners = new ArrayList<>(SpawnerFinderClient.STORE.byMob(selected, dim));
            spawners.sort(Comparator.comparingDouble(e -> SpawnerFinderClient.distSq(p, e)));
        } else {
            spawners = new ArrayList<>();
        }
    }

    @Override
    public void tick() {
        if (++ticks % 20 == 0) refresh();
    }

    private static int pages(int size, int rows) {
        return Math.max(1, (size + rows - 1) / rows);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int lx = lx(), rx = rx();

        ctx.drawCenteredTextWithShadow(textRenderer, "Spawner Finder", width / 2, 6, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer,
                "Мобы: " + mobs.size() + "  (стр. " + (mobPage + 1) + "/" + pages(mobs.size(), rows) + ")",
                lx, 42, 0xFFFFFFFF);
        String rh = selected == null
                ? "Выбери моба слева"
                : SpawnerFinderClient.prettyName(selected) + ": " + spawners.size()
                  + " шт.  (стр. " + (spawnPage + 1) + "/" + pages(spawners.size(), rows) + ")";
        ctx.drawTextWithShadow(textRenderer, rh, rx, 42, 0xFFFFAA00);

        Vec3d p = client.player.getPos();
        for (int i = 0; i < rows; i++) {
            int y = TOP + i * ROW_H;

            int mi = mobPage * rows + i;
            if (mi < mobs.size()) {
                Map.Entry<String, Long> e = mobs.get(mi);
                boolean hover = inside(mouseX, mouseY, lx, y, 180, ROW_H);
                int color = e.getKey().equals(selected) ? 0xFFFFAA00 : hover ? 0xFFFFFFFF : 0xFFAAAAAA;
                ctx.drawTextWithShadow(textRenderer,
                        SpawnerFinderClient.prettyName(e.getKey()) + "  x" + e.getValue(), lx + 2, y + 2, color);
            }

            int si = spawnPage * rows + i;
            if (si < spawners.size()) {
                SpawnerStore.Entry e = spawners.get(si);
                boolean hover = inside(mouseX, mouseY, rx, y, 210, ROW_H);
                String line = e.x() + " " + e.y() + " " + e.z() + "   "
                        + (int) Math.sqrt(SpawnerFinderClient.distSq(p, e)) + " м";
                ctx.drawTextWithShadow(textRenderer, line, rx + 2, y + 2, hover ? 0xFFFFFFFF : 0xFFAAAAAA);
            }
        }
        ctx.drawTextWithShadow(textRenderer, "Клик по координатам = скопировать", rx, height - 40, 0xFF777777);
        if (!status.isEmpty()) ctx.drawTextWithShadow(textRenderer, status, lx, height - 40, 0xFF55FF55);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int lx = lx(), rx = rx();
        for (int i = 0; i < rows; i++) {
            int y = TOP + i * ROW_H;
            if (inside(mx, my, lx, y, 180, ROW_H)) {
                int idx = mobPage * rows + i;
                if (idx < mobs.size()) {
                    selected = mobs.get(idx).getKey();
                    spawnPage = 0;
                    refresh();
                    updateHudButton();
                    return true;
                }
            }
            if (inside(mx, my, rx, y, 210, ROW_H)) {
                int idx = spawnPage * rows + i;
                if (idx < spawners.size()) {
                    SpawnerStore.Entry e = spawners.get(idx);
                    String c = e.x() + " " + e.y() + " " + e.z();
                    client.keyboard.setClipboard(c);
                    status = "Скопировано: " + c;
                    return true;
                }
            }
        }
        return false;
    }
}
