package dev.yourserver.simpleinventaredit;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class Lang {
    private static Plugin plugin;
    private static File langDir;
    private static FileConfiguration en; // fallback
    private static FileConfiguration de;

    private static String defaultLang = "en";
    private static boolean autoByClient = true;

    private Lang() {}

    public static void init(Plugin pl) {
        plugin = pl;
        langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) langDir.mkdirs();

        plugin.saveDefaultConfig();
        defaultLang = plugin.getConfig().getString("language.default", "en").toLowerCase(Locale.ROOT);
        autoByClient = plugin.getConfig().getBoolean("language.autoByClient", true);

        // Standard-Resourcen aus dem JAR ablegen, falls fehlen
        saveLangResourceIfMissing("en.yml");
        saveLangResourceIfMissing("de.yml");

        // Laden
        en = YamlConfiguration.loadConfiguration(new File(langDir, "en.yml"));
        de = YamlConfiguration.loadConfiguration(new File(langDir, "de.yml"));
    }

    private static void saveLangResourceIfMissing(String name) {
        File out = new File(langDir, name);
        if (out.exists()) return;
        try (InputStream in = plugin.getResource("lang/" + name)) {
            if (in != null) {
                java.nio.file.Files.copy(in, out.toPath());
            } else {
                String minimal = "plugin:\n  name: SimpleInventarEdit\n";
                java.nio.file.Files.write(out.toPath(), minimal.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not write default lang file: " + name + " -> " + e.getMessage());
        }
    }

    /** ermittelt "de" oder "en" für den Spieler (oder Default) */
    public static String langFor(Player p) {
        if (!autoByClient || p == null) return defaultLang;
        try {
            // Paper: locale()
            java.util.Locale loc = p.locale();
            if (loc != null && "de".equalsIgnoreCase(loc.getLanguage())) return "de";
        } catch (NoSuchMethodError ignored) {
            try {
                // Spigot: getLocale() => "de_de" / "en_us"
                String raw = p.getLocale();
                if (raw != null) {
                    String lang = raw.split("[_\\-]")[0];
                    if ("de".equalsIgnoreCase(lang)) return "de";
                }
            } catch (Throwable ignored2) {}
        }
        return "en";
    }

    public static String tr(Player p, String key, Map<String, String> placeholders) {
        String lang = langFor(p);
        String value = get(lang, key);
        if (value == null) value = get(defaultLang, key);
        if (value == null) value = key;

        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                value = value.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', value);
    }

    public static String tr(Player p, String key) {
        return tr(p, key, null);
    }

    private static String get(String lang, String key) {
        FileConfiguration src = "de".equals(lang) ? de : en;
        return src != null ? src.getString(key) : null;
    }

    /** schneller Placeholder-Builder */
    public static Map<String, String> ph(String k, String v) {
        Map<String, String> m = new HashMap<>();
        m.put(k, v);
        return m;
    }
}
