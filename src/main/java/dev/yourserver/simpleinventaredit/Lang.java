package dev.yourserver.simpleinventaredit;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Lang {
    private static JavaPlugin plugin;
    private static final String DEFAULT_LOCALE = "en_US";
    private static final Map<String, YamlConfiguration> BUNDLES = new HashMap<>();

    public static void init(JavaPlugin pl) {
        plugin = pl;
        ensureDefaults();           // legt en_US.yml & de_DE.yml im Datenordner an (falls fehlen)
        loadBundles();              // lädt erst aus Datenordner, sonst aus Jar
    }

    private static void ensureDefaults() {
        saveIfMissing("lang/en_US.yml");
        saveIfMissing("lang/de_DE.yml");
    }

    private static void saveIfMissing(String path) {
        File out = new File(plugin.getDataFolder(), path);
        if (out.exists()) return;
        out.getParentFile().mkdirs();
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) throw new IllegalArgumentException("missing resource " + path);
            try (OutputStream os = new FileOutputStream(out)) {
                in.transferTo(os);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save default lang file: " + path + " -> " + e.getMessage());
        }
    }

    private static void loadBundles() {
        BUNDLES.clear();
        loadOne("en_US");
        loadOne("de_DE");
    }

    private static void loadOne(String code) {
        // 1) Datenordner bevorzugt
        File f = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        YamlConfiguration cfg = null;
        if (f.exists()) {
            cfg = YamlConfiguration.loadConfiguration(f);
            cfg.setDefaults(loadDefaultsFromJar(code));
            cfg.options().copyDefaults(true);
        } else {
            cfg = loadDefaultsFromJar(code);
        }
        if (cfg != null) BUNDLES.put(code, cfg);
    }

    private static YamlConfiguration loadDefaultsFromJar(String code) {
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in == null) return null;
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            return null;
        }
    }

    private static String normalize(String locale) {
        if (locale == null || locale.isBlank()) return DEFAULT_LOCALE;
        String l = locale.replace('-', '_');
        String[] parts = l.split("_", 2);
        String lang = parts[0].toLowerCase(Locale.ROOT);
        String country = parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "";
        return country.isEmpty() ? lang : lang + "_" + country;
    }

    private static String playerLocale(Player p) {
        // Spigot 1.20.x: getLocale() existiert
        try {
            String loc = p != null ? p.getLocale() : null;
            return normalize(loc != null ? loc : DEFAULT_LOCALE);
        } catch (Throwable t) {
            return DEFAULT_LOCALE;
        }
    }

    public static String tr(Player p, String key) {
        return tr(p, key, Collections.emptyMap());
    }

    public static Map<String,String> ph(String k, String v) {
        Map<String,String> m = new HashMap<>();
        m.put(k, v);
        return m;
    }

    public static String tr(Player p, String key, Map<String,String> placeholders) {
        String loc = playerLocale(p);
        String msg = lookup(loc, key);
        if (msg == null) {
            // Fallback: Sprache ohne Land (z.B. de)
            String base = loc.contains("_") ? loc.substring(0, loc.indexOf('_')) : loc;
            msg = lookup(base, key);
        }
        if (msg == null) msg = lookup(DEFAULT_LOCALE, key);
        if (msg == null) msg = key;

        if (placeholders != null) {
            for (Map.Entry<String,String> e : placeholders.entrySet()) {
                msg = msg.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', msg);
    }

    private static String lookup(String code, String path) {
        if (code == null) return null;
        // exakte Locale
        YamlConfiguration cfg = BUNDLES.get(code);
        if (cfg != null && cfg.contains(path)) return cfg.getString(path);

        // nur Sprache (de/en)
        if (code.length() == 2) return null;
        String langOnly = code.substring(0, 2);
        cfg = BUNDLES.get(langOnly);
        if (cfg != null && cfg.contains(path)) return cfg.getString(path);
        return null;
    }
}
