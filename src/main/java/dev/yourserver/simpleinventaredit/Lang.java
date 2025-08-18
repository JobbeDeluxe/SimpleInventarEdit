package dev.yourserver.simpleinventaredit;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Lang {

    private final JavaPlugin plugin;
    private final Map<String, YamlConfiguration> bundles = new HashMap<>();
    private final String defaultKey = "en_US";

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
        ensureDefaults();
    }

    private void ensureDefaults() {
        // legt die Default-Sprachdateien ab, falls im Plugin jar vorhanden
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
            // Fallback: aus JAR lesen
            try (InputStreamReader r = new InputStreamReader(
                    Objects.requireNonNull(plugin.getResource("lang/" + k + ".yml")),
                    StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(r);
            } catch (Exception e) {
                // Letzter Fallback: leere Config
                return new YamlConfiguration();
            }
        });
    }

    // ------- Locale-Ermittlung (Spigot/Paper kompatibel) -------

    private static Locale resolveLocale(Player p) {
        // 1) Paper (neuer): Player#locale() -> Locale
        try {
            var m = p.getClass().getMethod("locale");
            Object v = m.invoke(p);
            if (v instanceof Locale loc && !isEmpty(loc)) {
                return loc;
            }
        } catch (NoSuchMethodException ignored) {
            // Spigot hat locale() nicht – weiter zu getLocale()
        } catch (Throwable ignored) {
        }

        // 2) Spigot/Paper klassisch: Player#getLocale() -> String (z. B. "en_us")
        try {
            String tag = p.getLocale();
            if (tag != null && !tag.isBlank()) {
                return toLocale(tag);
            }
        } catch (Throwable ignored) {
        }

        // 3) Fallback
        return Locale.ENGLISH;
    }

    private static boolean isEmpty(Locale l) {
        return l == null || l.getLanguage() == null || l.getLanguage().isEmpty();
    }

    private static Locale toLocale(String tag) {
        String norm = tag.replace('_', '-'); // "en_us" -> "en-us"
        Locale loc = Locale.forLanguageTag(norm);
        return isEmpty(loc) ? Locale.ENGLISH : loc;
    }

    private String keyFor(Player p) {
        Locale loc = resolveLocale(p);
        String lang = loc.getLanguage() == null ? "" : loc.getLanguage().toLowerCase(Locale.ROOT);

        // mappe grob: alles "de*" -> de_DE, sonst en_US
        if ("de".equals(lang)) return "de_DE";
        return "en_US";
    }

    // ------- API -------

    public String t(Player p, String path, Object... args) {
        String key = keyFor(p);
        String val = loadBundle(key).getString(path);
        if (val == null) {
            val = loadBundle(defaultKey).getString(path, path);
        }
        if (args != null && args.length > 0) {
            try {
                val = String.format(val, args);
            } catch (IllegalFormatException ignored) {
            }
        }
        return val;
    }

    public String t(String path, Object... args) { // ohne Player -> Default-Sprache
        String val = loadBundle(defaultKey).getString(path, path);
        if (args != null && args.length > 0) {
            try {
                val = String.format(val, args);
            } catch (IllegalFormatException ignored) {
            }
        }
        return val;
    }
}
