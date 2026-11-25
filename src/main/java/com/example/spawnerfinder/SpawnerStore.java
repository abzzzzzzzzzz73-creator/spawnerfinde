package com.example.spawnerfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Хранит найденные спавнеры и сохраняет их в config/spawnerfinder/<сервер>.json и .txt */
public class SpawnerStore {
    public record Entry(String dim, int x, int y, int z, String mob) {
        String key() {
            return dim + "|" + x + "," + y + "," + z;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, Entry> entries = new HashMap<>();
    private Path jsonFile;
    private Path txtFile;
    private boolean dirty;

    public synchronized void load(String serverKey) {
        save(); // сбросить данные прошлого мира на диск
        entries.clear();
        String safe = serverKey.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("spawnerfinder");
        jsonFile = dir.resolve(safe + ".json");
        txtFile = dir.resolve(safe + ".txt");
        dirty = false;
        if (Files.exists(jsonFile)) {
            try {
                List<Entry> list = GSON.fromJson(Files.readString(jsonFile), new TypeToken<List<Entry>>() {}.getType());
                if (list != null) for (Entry e : list) entries.put(e.key(), e);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    /** Заменяет всё, что мы знали об одном чанке (так пропадают сломанные спавнеры). */
    public synchronized void replaceChunk(String dim, int cx, int cz, List<Entry> found) {
        Map<String, Entry> fresh = new HashMap<>();
        for (Entry e : found) fresh.put(e.key(), e);
        Map<String, Entry> old = new HashMap<>();
        for (Entry e : entries.values()) {
            if (e.dim().equals(dim) && (e.x() >> 4) == cx && (e.z() >> 4) == cz) old.put(e.key(), e);
        }
        if (old.equals(fresh)) return;
        entries.keySet().removeAll(old.keySet());
        entries.putAll(fresh);
        dirty = true;
    }

    public synchronized Map<String, Long> mobCounts(String dim) {
        Map<String, Long> counts = entries.values().stream()
                .filter(e -> e.dim().equals(dim))
                .collect(Collectors.groupingBy(Entry::mob, Collectors.counting()));
        List<Map.Entry<String, Long>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> {
            int c = Long.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : list) out.put(e.getKey(), e.getValue());
        return out;
    }

    public synchronized List<Entry> byMob(String mob, String dim) {
        return entries.values().stream()
                .filter(e -> e.mob().equals(mob) && e.dim().equals(dim))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public synchronized String saveNow() {
        dirty = true;
        save();
        return txtFile == null ? "(нет файла)" : "config/spawnerfinder/" + txtFile.getFileName();
    }

    public synchronized void save() {
        if (jsonFile == null || !dirty) return;
        try {
            Files.createDirectories(jsonFile.getParent());
            List<Entry> list = new ArrayList<>(entries.values());
            list.sort(Comparator.comparing(Entry::mob).thenComparing(Entry::dim)
                    .thenComparingInt(Entry::x).thenComparingInt(Entry::z));
            Files.writeString(jsonFile, GSON.toJson(list));
            StringBuilder sb = new StringBuilder("# моб | измерение | x y z\n");
            for (Entry e : list) {
                sb.append(e.mob()).append(" | ").append(e.dim()).append(" | ")
                        .append(e.x()).append(' ').append(e.y()).append(' ').append(e.z()).append('\n');
            }
            Files.writeString(txtFile, sb.toString());
            dirty = false;
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
