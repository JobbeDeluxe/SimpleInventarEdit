package dev.yourserver.simpleinventaredit;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Sprach-Helfer mit Abwärtskompatibilität:
 * - Lang.init(plugin)
 * - Lang.tr(player, key)
 * - Lang.tr(player, key, placeholders)
 * - Lang.ph(key, value) (+ Overload zum Erweitern)
 *
 * Platzhalter-Formate unterstützt: {key} und %key%
 * Farb-Codes: &x -> ChatColor
 */
public final class Lang {

    // ---- Static Singleton + Backcompat API ----
    private static Lang INSTANCE;

    public static Lang init(JavaPlugin plugin) {
        if (INSTANCE == null) {
            INSTANCE = new Lang(plugin);
        }
        return INSTANCE;
    }

    public static Lang get() {
        return INSTANCE;
    }

    /** Backcompat: einfache Übersetzung ohne Platzhalter */
    public static String tr(Player p, String path) {
        if (INSTANCE == null) return path;
        return INSTANCE.t(p, path);
    }

    /** Backcompat: Übersetzung mit Platzhaltern */
    public static String tr(Player p, String path, Map<String, String> placeholders) {
        if (INSTANCE == null) return applyPlaceholders(path, placeholders);
        return INSTANCE.t(p, path, placeholders);
    }

    /** Backcompat: Einfacher Placeholder-Builder */
    public static Map<String, String> ph(String key, String value) {
        HashMap<String, String> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    /** Optionaler Helfer, um eine bestehende Map zu erweitern */
    public static Map<String, String> ph(Map<String, String> base, String key, String value) {
        if (base == null) base = new HashMap<>();
        base.put(key, value);
        return base;
    }

    // ---- Instanz-Implementierung ----
    private final JavaPlugin plugin;
    private final Map<String, YamlConfiguration> bundles = new HashMap<>();
    private final String defaultKey = "en_US";

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
        ensureDefaults();
    }

    private void ensureDefaults() {
        saveIfMissing("lang/en_US.yml");
        saveIfMissing("lang/de_DE.yml");
    }

    private void saveIfMissing(String path) {
        File f = new File(plugin.getDataFolder(), path);
        if (!f.exists()) {
            plugin.saveResource(path, false);
        }
    }

    private YamlConfiguration loadBundle(String key) {
        return bundles.computeIfAbsent(key, k -> {
            File f = new File(plugin.getDataFolder(), "lang/" + k + ".yml");
            if (f.exists()) {
                return YamlConfiguration.loadConfiguration(f);
            }
            // Fallback: aus JAR
            try (InputStreamReader r = new InputStreamReader(
                    Objects.requireNonNull(plugin.getResource("lang/" + k + ".yml")),
                    StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(r);
            } catch (Exception e) {
                return new YamlConfiguration();
            }
        });
    }

    // ------- Locale-Ermittlung (Spigot/Paper kompatibel) -------
    private static Locale resolveLocale(Player p) {
        if (p != null) {
            // Paper neu: Player#locale() -> Locale
            try {
                var m = p.getClass().getMethod("locale");
                Object v = m.invoke(p);
                if (v instanceof Locale loc && !isEmpty(loc)) {
                    return loc;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
            // Spigot/klassisch: Player#getLocale() -> String
            try {
                String tag = p.getLocale();
                if (tag != null && !tag.isBlank()) {
                    return toLocale(tag);
                }
            } catch (Throwable ignored) {
            }
        }
        return Locale.ENGLISH;
    }

    private static boolean isEmpty(Locale l) {
        return l == null || l.getLanguage() == null || l.getLanguage().isEmpty();
    }

    private static Locale toLocale(String tag) {
        String norm = tag.replace('_', '-'); // "de_de" -> "de-de"
        Locale loc = Locale.forLanguageTag(norm);
        return isEmpty(loc) ? Locale.ENGLISH : loc;
    }

    private String keyFor(Player p) {
        Locale loc = resolveLocale(p);
        String lang = (loc.getLanguage() == null) ? "" : loc.getLanguage().toLowerCase(Locale.ROOT);
        // Ganz einfache Zuordnung: alles "de*" -> de_DE, sonst en_US
        if ("de".equals(lang)) return "de_DE";
        return "en_US";
    }

    // ------- Öffentliche Instanz-API -------
    public String t(Player p, String path) {
        String key = keyFor(p);
        String raw = loadBundle(key).getString(path);
        if (raw == null) {
            raw = loadBundle(defaultKey).getString(path, path);
        }
        return colorize(raw);
    }

    public String t(Player p, String path, Map<String, String> placeholders) {
        String out = t(p, path);
        if (placeholders != null && !placeholders.isEmpty()) {
            out = applyPlaceholders(out, placeholders);
        }
        return out;
    }

    public String t(String path) { // ohne Player
        String raw = loadBundle(defaultKey).getString(path, path);
        return colorize(raw);
    }

    // ------- Utils -------
    private static String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private static String applyPlaceholders(String s, Map<String, String> map) {
        if (s == null || map == null || map.isEmpty()) return s;
        String out = s;
        for (var e : map.entrySet()) {
            String k = e.getKey();
            String v = e.getValue() == null ? "" : e.getValue();
            // beide Schreibweisen unterstützen
            out = out.replace("{" + k + "}", v);
            out = out.replace("%" + k + "%", v);
        }
        return out;
    }
}
