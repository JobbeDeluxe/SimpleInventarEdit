package dev.yourserver.simpleinventaredit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SimpleInventarEditPlugin extends JavaPlugin implements Listener {

    private static final int GUI_SIZE = 54; // 6x9

    // Config
    private boolean enablePalette = true;
    private boolean paletteEditable = true;
    private boolean backOnClose   = true;
    private List<Material> paletteItems = null;
    private final Set<UUID> paletteEditMode = new HashSet<>();

    // ---- Armor whitelist (version-stable, no org.bukkit.Tag needed) ----
    private static final Set<Material> HELMETS = EnumSet.of(
            Material.LEATHER_HELMET, Material.CHAINMAIL_HELMET, Material.IRON_HELMET,
            Material.GOLDEN_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET,
            Material.TURTLE_HELMET, Material.CARVED_PUMPKIN,
            Material.PLAYER_HEAD, Material.ZOMBIE_HEAD, Material.SKELETON_SKULL,
            Material.CREEPER_HEAD, Material.DRAGON_HEAD, Material.WITHER_SKELETON_SKULL,
            Material.PIGLIN_HEAD
    );
    private static final Set<Material> CHESTPLATES = EnumSet.of(
            Material.LEATHER_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE, Material.IRON_CHESTPLATE,
            Material.GOLDEN_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE,
            Material.ELYTRA
    );
    private static final Set<Material> LEGGINGS = EnumSet.of(
            Material.LEATHER_LEGGINGS, Material.CHAINMAIL_LEGGINGS, Material.IRON_LEGGINGS,
            Material.GOLDEN_LEGGINGS, Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS
    );
    private static final Set<Material> BOOTS = EnumSet.of(
            Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS, Material.IRON_BOOTS,
            Material.GOLDEN_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS
    );

    private static final Map<EquipmentSlot, Set<String>> LEGACY_PLACEHOLDER_NAMES = Map.of(
            EquipmentSlot.HEAD, Set.of("Helm (leer)", "Helmet (empty)"),
            EquipmentSlot.CHEST, Set.of("Brust (leer)", "Chest (empty)"),
            EquipmentSlot.LEGS, Set.of("Beine (leer)", "Legs (empty)"),
            EquipmentSlot.FEET, Set.of("Stiefel (leer)", "Boots (empty)"),
            EquipmentSlot.OFF_HAND, Set.of("Offhand (leer)", "Offhand (empty)")
    );

    private static boolean isAllowedInArmorSlot(Material type, EquipmentSlot slot) {
        if (type == null) return false;
        switch (slot) {
            case HEAD:  return HELMETS.contains(type);
            case CHEST: return CHESTPLATES.contains(type);
            case LEGS:  return LEGGINGS.contains(type);
            case FEET:  return BOOTS.contains(type);
            case OFF_HAND: return true; // Offhand darf alles
            default: return false;
        }
    }

    // Titel (nur Anzeige)
    private String titlePlayers(Player p) { return Lang.tr(p, "ui.players_title"); }
    private String titleArmor(Player p, String targetName) {
        Map<String,String> ph = new HashMap<>();
        ph.put("player", targetName);
        return Lang.tr(p, "ui.armor_title", ph);
    }
    private String titleInventory(Player p, String targetName) {
        Map<String,String> ph = new HashMap<>();
        ph.put("player", targetName);
        return Lang.tr(p, "ui.inventory_title", ph);
    }
    private String titlePalette(Player p, String targetName) {
        Map<String,String> ph = new HashMap<>();
        ph.put("player", targetName);
        return Lang.tr(p, "ui.palette_title", ph);
    }
    private String titleOfflinePlayers(Player p) { return Lang.tr(p, "ui.offline_players_title"); }
    private String titleOfflineInventory(Player p, String targetName) {
        Map<String,String> ph = new HashMap<>();
        ph.put("player", targetName);
        return Lang.tr(p, "ui.offline_inventory_title", ph);
    }
    private String titleOfflineEnder(Player p, String targetName) {
        Map<String,String> ph = new HashMap<>();
        ph.put("player", targetName);
        return Lang.tr(p, "ui.offline_ender_title", ph);
    }

    // Ziel -> Admin-Viewer (zum Schließen bei Logout)
    private final Map<UUID, Set<UUID>> viewersByTarget = new HashMap<>();
    // Admin -> aktuell betrachtetes Ziel (Armor/Palette)
    private final Map<UUID, UUID> armorGuiTargetByViewer = new HashMap<>();
    private final Map<UUID, UUID> paletteTargetByViewer  = new HashMap<>();
    private final Map<UUID, UUID> onlineInventoryTargetByViewer = new HashMap<>();
    // Admin -> letzte Spielerliste-Seite (für "Zurück")
    private final Map<UUID, Integer> lastListPageByViewer = new HashMap<>();
    private final Map<UUID, Integer> offlineListPageByViewer = new HashMap<>();
    // Admin, der natives Ziel-Inventar/Endertruhe geöffnet hat (für "backOnClose")
    private final Set<UUID> viewingTargetInventory = new HashSet<>();
    private final Set<UUID> viewingTargetEnder     = new HashSet<>();
    // Admins im Löschmodus
    private final Set<UUID> deleteMode = new HashSet<>();
    private final Map<UUID, UUID> offlineInventoryTargetByViewer = new HashMap<>();
    private final Map<UUID, UUID> offlineEnderTargetByViewer = new HashMap<>();

    private final Map<UUID, OfflinePlayerData> offlineData = new HashMap<>();
    private File offlineDataFile;
    private NamespacedKey placeholderKey;

    // Reflection helper for reading vanilla playerdata files on demand
    private boolean nbtAccessInitialized = false;
    private boolean nbtAccessAvailable  = false;
    private Method nbtReadCompressedMethod;
    private Method nbtCompoundGetListMethod;
    private Method nbtCompoundGetByteMethod;
    private Method nbtListSizeMethod;
    private Method nbtListGetCompoundMethod;
    private Method nmsItemStackFromTagMethod;
    private Method craftItemStackAsBukkitCopyMethod;
    private Class<?> nbtCompoundClass;
    private Class<?> nbtListClass;
    private Class<?> nmsItemStackClass;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }
        this.offlineDataFile = new File(getDataFolder(), "offline-data.yml");
        this.placeholderKey = new NamespacedKey(this, "gui-placeholder");
        loadSieConfig();
        loadOfflineData();
        populateOfflineIndex();

        // i18n
        Lang.init(this);

        if (getCommand("sie") != null) {
            getCommand("sie").setExecutor((sender, cmd, label, args) -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(ChatColor.RED + "Ingame only.");
                    return true;
                }
                if (!p.hasPermission("sie.use")) {
                    p.sendMessage(Lang.tr(p, "error.no_permission"));
                    return true;
                }
                if (args != null && args.length > 0) {
                    if (args[0].equalsIgnoreCase("help")) {
                        openHelpBook(p);
                        return true;
                    }
                    if (args[0].equalsIgnoreCase("back")) {
                        int page = lastListPageByViewer.getOrDefault(p.getUniqueId(), 0);
                        openPlayerList(p, page);
                        return true;
                    }
                }
                openPlayerList(p, 0);
                return true;
            });
        }
        if (getCommand("siehelp") != null) {
            getCommand("siehelp").setExecutor((sender, cmd, lbl, args) -> {
                if (sender instanceof Player p) openHelpBook(p);
                return true;
            });
        }

        getServer().getPluginManager().registerEvents(this, this);
        for (Player online : Bukkit.getOnlinePlayers()) {
            storeOfflineData(online);
        }
        getLogger().info("SimpleInventarEdit ready.");
    }

    @Override
    public void onDisable() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            storeOfflineData(online);
        }
        saveOfflineData();
    }

    private void loadSieConfig() {
        var cfg = getConfig();
        this.enablePalette = cfg.getBoolean("palette.enabled", true);
        this.paletteEditable = cfg.getBoolean("palette.allowIngameEditing", true);
        this.backOnClose   = cfg.getBoolean("navigation.backOnClose", true);

        List<String> names = cfg.getStringList("palette.items");
        if (names != null && !names.isEmpty()) {
            List<Material> parsed = new ArrayList<>();
            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                try {
                    Material m = Material.valueOf(n.trim().toUpperCase(Locale.ROOT));
                    if (m.isItem()) parsed.add(m);
                    else getLogger().log(Level.WARNING, "palette.items: {0} is not an item, skipped.", n);
                } catch (IllegalArgumentException ex) {
                    getLogger().log(Level.WARNING, "palette.items: Unknown material: {0}", n);
                }
            }
            if (!parsed.isEmpty()) this.paletteItems = parsed;
        }
        if (this.paletteItems == null) this.paletteItems = defaultPaletteItems();
    }

    private List<Material> defaultPaletteItems() {
        return Arrays.asList(
                Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE,
                Material.OAK_LOG, Material.OAK_PLANKS, Material.CRAFTING_TABLE, Material.FURNACE,
                Material.CHEST, Material.ENDER_CHEST, Material.SHULKER_BOX, Material.BARREL,
                Material.TORCH, Material.LANTERN, Material.WHITE_BED,
                Material.BREAD, Material.COOKED_BEEF, Material.GOLDEN_CARROT,
                Material.WATER_BUCKET, Material.LAVA_BUCKET, Material.BUCKET,
                Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.NETHERITE_INGOT,
                Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE,
                Material.IRON_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE,
                Material.IRON_SHOVEL, Material.DIAMOND_SHOVEL, Material.NETHERITE_SHOVEL,
                Material.BOW, Material.CROSSBOW, Material.ARROW, Material.TOTEM_OF_UNDYING,
                Material.SPYGLASS, Material.MAP, Material.SHIELD,
                Material.FLINT_AND_STEEL, Material.SHEARS,
                Material.ELYTRA, Material.FIREWORK_ROCKET,
                Material.ENCHANTING_TABLE, Material.ANVIL, Material.GRINDSTONE, Material.SMITHING_TABLE
        );
    }

    private void persistPaletteItems() {
        List<String> names = paletteItems.stream()
                .map(mat -> mat != null ? mat.name() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        getConfig().set("palette.items", names);
        try {
            saveConfig();
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Could not save palette items", ex);
        }
    }

    /* ====== Utils ====== */

    private ItemStack named(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }
    private ItemStack named(Material mat, String name) { return named(mat, name, Collections.emptyList()); }

    @SuppressWarnings("deprecation")
    private ItemStack headButton(Player viewer, String name, UUID uuid, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + name);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack safeClone(ItemStack stack, ItemStack fallbackIfAir) {
        if (stack == null || stack.getType() == Material.AIR) return fallbackIfAir;
        return stack.clone();
    }

    private boolean isPlaceholder(ItemStack item, EquipmentSlot slot) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && placeholderKey != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc.has(placeholderKey, PersistentDataType.STRING)) {
                String tag = pdc.get(placeholderKey, PersistentDataType.STRING);
                if (slot == null) return true;
                if (tag != null && tag.equals(slot.name())) return true;
            }
        }
        if (meta != null && meta.hasDisplayName()) {
            String stripped = ChatColor.stripColor(meta.getDisplayName());
            if (slot != null) {
                Set<String> legacy = LEGACY_PLACEHOLDER_NAMES.get(slot);
                if (legacy != null && stripped != null && legacy.contains(stripped)) return true;
            } else {
                for (Set<String> legacy : LEGACY_PLACEHOLDER_NAMES.values()) {
                    if (legacy.contains(stripped)) return true;
                }
            }
        }
        return false;
    }

    private ItemStack sanitizeArmorSlot(ItemStack stack, EquipmentSlot slot) {
        if (isPlaceholder(stack, slot)) return null;
        return cloneOrNull(stack);
    }

    private void cleanupOfflinePlaceholders(OfflinePlayerData data) {
        if (data == null || data.inventory == null) return;
        data.inventory[39] = sanitizeArmorSlot(data.inventory[39], EquipmentSlot.HEAD);
        data.inventory[38] = sanitizeArmorSlot(data.inventory[38], EquipmentSlot.CHEST);
        data.inventory[37] = sanitizeArmorSlot(data.inventory[37], EquipmentSlot.LEGS);
        data.inventory[36] = sanitizeArmorSlot(data.inventory[36], EquipmentSlot.FEET);
        data.inventory[40] = sanitizeArmorSlot(data.inventory[40], EquipmentSlot.OFF_HAND);
    }

    private void addViewer(Player admin, Player target) {
        viewersByTarget.computeIfAbsent(target.getUniqueId(), k -> new HashSet<>()).add(admin.getUniqueId());
    }

    private ItemStack cloneOrNull(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return null;
        if (isPlaceholder(stack)) return null;
        return stack.clone();
    }

    private ItemStack armorPlaceholder(Player viewer, EquipmentSlot slot) {
        Material mat;
        String key;
        switch (slot) {
            case HEAD -> {
                mat = Material.LEATHER_HELMET;
                key = "armor.empty.helmet";
            }
            case CHEST -> {
                mat = Material.LEATHER_CHESTPLATE;
                key = "armor.empty.chest";
            }
            case LEGS -> {
                mat = Material.LEATHER_LEGGINGS;
                key = "armor.empty.legs";
            }
            case FEET -> {
                mat = Material.LEATHER_BOOTS;
                key = "armor.empty.boots";
            }
            case OFF_HAND -> {
                mat = Material.SHIELD;
                key = "armor.empty.offhand";
            }
            default -> {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                key = "armor.empty.helmet";
            }
        }
        ItemStack base = named(mat, ChatColor.GRAY + Lang.tr(viewer, key));
        return markPlaceholder(base, slot);
    }

    private ItemStack markPlaceholder(ItemStack stack, EquipmentSlot slot) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && placeholderKey != null) {
            meta.getPersistentDataContainer().set(placeholderKey, PersistentDataType.STRING, slot.name());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private boolean isPlaceholder(ItemStack stack) {
        if (stack == null) return false;
        if (isLegacyPlaceholder(stack)) return true;
        if (placeholderKey == null) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(placeholderKey, PersistentDataType.STRING);
    }

    private boolean isLegacyPlaceholder(ItemStack stack) {
        if (stack == null || stack.getAmount() != 1) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName() || meta.hasLore()) return false;
        Material type = stack.getType();
        EquipmentSlot slot = switch (type) {
            case LEATHER_HELMET -> EquipmentSlot.HEAD;
            case LEATHER_CHESTPLATE -> EquipmentSlot.CHEST;
            case LEATHER_LEGGINGS -> EquipmentSlot.LEGS;
            case LEATHER_BOOTS -> EquipmentSlot.FEET;
            case SHIELD -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
        if (slot == null) return false;
        String stripped = ChatColor.stripColor(meta.getDisplayName());
        Set<String> legacy = LEGACY_PLACEHOLDER_NAMES.get(slot);
        return legacy != null && legacy.contains(stripped);
    }

    private void decorateInventoryControls(Inventory inv, Player viewer) {
        for (int i = 41; i <= 44; i++) {
            inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " "));
        }
        for (int i = 46; i < 54; i++) {
            inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " "));
        }
        inv.setItem(45, named(Material.ARROW, ChatColor.AQUA + Lang.tr(viewer, "ui.back")));
        inv.setItem(47, named(Material.WRITTEN_BOOK, ChatColor.GOLD + Lang.tr(viewer, "ui.help")));
        inv.setItem(49, named(Material.BARRIER, ChatColor.RED + Lang.tr(viewer, "ui.close")));
    }

    private void ensureInventoryPlaceholders(Inventory inv, Player viewer) {
        if (inv == null || viewer == null || inv.getSize() < 41) return;
        if (inv.getItem(36) == null || inv.getItem(36).getType() == Material.AIR || isPlaceholder(inv.getItem(36))) {
            inv.setItem(36, armorPlaceholder(viewer, EquipmentSlot.HEAD));
        }
        if (inv.getItem(37) == null || inv.getItem(37).getType() == Material.AIR || isPlaceholder(inv.getItem(37))) {
            inv.setItem(37, armorPlaceholder(viewer, EquipmentSlot.CHEST));
        }
        if (inv.getItem(38) == null || inv.getItem(38).getType() == Material.AIR || isPlaceholder(inv.getItem(38))) {
            inv.setItem(38, armorPlaceholder(viewer, EquipmentSlot.LEGS));
        }
        if (inv.getItem(39) == null || inv.getItem(39).getType() == Material.AIR || isPlaceholder(inv.getItem(39))) {
            inv.setItem(39, armorPlaceholder(viewer, EquipmentSlot.FEET));
        }
        if (inv.getItem(40) == null || inv.getItem(40).getType() == Material.AIR || isPlaceholder(inv.getItem(40))) {
            inv.setItem(40, armorPlaceholder(viewer, EquipmentSlot.OFF_HAND));
        }
    }

    private ItemStack[] cloneArray(ItemStack[] src, int size) {
        ItemStack[] out = new ItemStack[size];
        if (src == null) return out;
        int len = Math.min(size, src.length);
        for (int i = 0; i < len; i++) {
            out[i] = cloneOrNull(src[i]);
        }
        return out;
    }

    private ItemStack[] deserializeItems(Object raw, int size) {
        ItemStack[] out = new ItemStack[size];
        if (raw == null) return out;

        if (raw instanceof List<?> list) {
            int len = Math.min(size, list.size());
            for (int i = 0; i < len; i++) {
                out[i] = deserializeItem(list.get(i));
            }
            return out;
        }

        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                int slot = parseSlotIndex(entry.getKey());
                if (slot < 0 || slot >= size) continue;
                out[slot] = deserializeItem(entry.getValue());
            }
            return out;
        }

        if (raw instanceof ConfigurationSection section) {
            for (String key : section.getKeys(false)) {
                int slot = parseSlotIndex(key);
                if (slot < 0 || slot >= size) continue;
                out[slot] = deserializeItem(section.get(key));
            }
        }

        return out;
    }

    private ItemStack deserializeItem(Object obj) {
        if (obj instanceof ItemStack item) {
            return cloneOrNull(item);
        }
        if (obj instanceof ConfigurationSection section) {
            return deserializeItem(sectionToMap(section));
        }
        if (obj instanceof Map<?, ?>) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) obj;
                return cloneOrNull(ItemStack.deserialize(map));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void ensureNbtAccess() {
        if (nbtAccessInitialized) return;
        nbtAccessInitialized = true;

        String version = null;
        try {
            String pkg = getServer().getClass().getPackage().getName();
            int idx = pkg.lastIndexOf('.');
            if (idx >= 0 && idx + 1 < pkg.length()) {
                version = pkg.substring(idx + 1);
            }
        } catch (Exception ignored) {
        }

        Class<?> compound = findClass(
                "net.minecraft.nbt.CompoundTag",
                "net.minecraft.nbt.NBTTagCompound",
                version != null ? "net.minecraft.server." + version + ".NBTTagCompound" : null
        );
        Class<?> list = findClass(
                "net.minecraft.nbt.ListTag",
                "net.minecraft.nbt.NBTTagList",
                version != null ? "net.minecraft.server." + version + ".NBTTagList" : null
        );
        Class<?> nmsStack = findClass(
                "net.minecraft.world.item.ItemStack",
                version != null ? "net.minecraft.server." + version + ".ItemStack" : null
        );
        Class<?> nbtIo = findClass(
                "net.minecraft.nbt.NbtIo",
                "net.minecraft.nbt.NBTCompressedStreamTools",
                version != null ? "net.minecraft.server." + version + ".NBTCompressedStreamTools" : null
        );

        if (compound == null || list == null || nmsStack == null || nbtIo == null) {
            return;
        }

        Method readCompressed = tryMethod(nbtIo, "readCompressed", InputStream.class);
        if (readCompressed == null) {
            readCompressed = tryMethod(nbtIo, "a", InputStream.class);
        }
        Method getList = tryMethod(compound, "getList", String.class, int.class);
        Method getByte = tryMethod(compound, "getByte", String.class);
        Method size = tryMethod(list, "size");
        Method getCompound = tryMethod(list, "getCompound", int.class);
        if (getCompound == null) {
            getCompound = tryMethod(list, "a", int.class);
        }
        Method fromTag = tryMethod(nmsStack, "of", compound);
        if (fromTag == null) {
            fromTag = tryMethod(nmsStack, "a", compound);
        }

        if (readCompressed == null || getList == null || getByte == null || size == null || getCompound == null || fromTag == null) {
            return;
        }

        try {
            readCompressed.setAccessible(true);
            getList.setAccessible(true);
            getByte.setAccessible(true);
            size.setAccessible(true);
            getCompound.setAccessible(true);
            fromTag.setAccessible(true);
        } catch (Exception ignored) {
        }

        Method asBukkitCopy = null;
        if (version != null) {
            try {
                Class<?> craft = Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
                asBukkitCopy = tryMethod(craft, "asBukkitCopy", nmsStack);
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (asBukkitCopy == null) {
            try {
                Class<?> craft = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
                asBukkitCopy = tryMethod(craft, "asBukkitCopy", nmsStack);
            } catch (ClassNotFoundException ignored) {
            }
        }

        if (asBukkitCopy == null) {
            return;
        }

        nbtAccessAvailable = true;
        nbtCompoundClass = compound;
        nbtListClass = list;
        nmsItemStackClass = nmsStack;
        nbtReadCompressedMethod = readCompressed;
        nbtCompoundGetListMethod = getList;
        nbtCompoundGetByteMethod = getByte;
        nbtListSizeMethod = size;
        nbtListGetCompoundMethod = getCompound;
        nmsItemStackFromTagMethod = fromTag;
        craftItemStackAsBukkitCopyMethod = asBukkitCopy;
    }

    private Class<?> findClass(String... names) {
        if (names == null) return null;
        for (String name : names) {
            if (name == null) continue;
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private Method tryMethod(Class<?> type, String name, Class<?>... params) {
        if (type == null || name == null) return null;
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method m = type.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    private Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                map.put(key, sectionToMap(nested));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    private int parseSlotIndex(Object raw) {
        if (raw instanceof Number num) {
            return num.intValue();
        }
        if (raw instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private void loadOfflineData() {
        offlineData.clear();
        if (offlineDataFile == null || !offlineDataFile.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(offlineDataFile);
        if (!cfg.isConfigurationSection("players")) return;

        Map<String, UUID> knownNames = buildKnownNameIndex();

        for (String key : cfg.getConfigurationSection("players").getKeys(false)) {
            String base = "players." + key;
            String storedName = cfg.getString(base + ".name");
            UUID uuid = resolveOfflineDataKey(key, storedName, knownNames);
            if (uuid == null) {
                getLogger().log(Level.WARNING, "Skipping offline data entry with unknown key: " + key);
                continue;
            }
            String name = storedName;
            if (name == null || name.isBlank() || isLikelyUuidName(name)) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                if (offline != null) {
                    String offlineName = offline.getName();
                    if (offlineName != null && !offlineName.isBlank()) {
                        name = offlineName;
                    }
                }
            }
            if (name == null || name.isBlank()) {
                name = uuid.toString();
            }
            ItemStack[] inv = deserializeItems(cfg.get(base + ".inventory"), 41);
            ItemStack[] ender = deserializeItems(cfg.get(base + ".ender"), 27);
            OfflinePlayerData data = new OfflinePlayerData(name, inv, ender);
            cleanupOfflinePlaceholders(data);
            OfflinePlayerData existing = offlineData.get(uuid);
            if (existing != null) {
                mergeOfflineData(existing, data);
            } else {
                offlineData.put(uuid, data);
            }
        }
    }

    private OfflinePlayerData ensureOfflineDataLoaded(UUID uuid) {
        OfflinePlayerData data = offlineData.get(uuid);
        if (hasOfflineData(data)) {
            return data;
        }

        OfflinePlayerData loaded = loadOfflineDataFromPlayerFile(uuid);
        if (loaded != null) {
            offlineData.put(uuid, loaded);
            saveOfflineData();
            return loaded;
        }
        return data;
    }

    private boolean hasOfflineData(OfflinePlayerData data) {
        if (data == null) return false;
        if (data.inventory != null) {
            for (ItemStack stack : data.inventory) {
                if (stack != null && stack.getType() != Material.AIR) {
                    return true;
                }
            }
        }
        if (data.ender != null) {
            for (ItemStack stack : data.ender) {
                if (stack != null && stack.getType() != Material.AIR) {
                    return true;
                }
            }
        }
        return false;
    }

    private OfflinePlayerData loadOfflineDataFromPlayerFile(UUID uuid) {
        if (uuid == null) return null;
        ensureNbtAccess();
        if (!nbtAccessAvailable) {
            return null;
        }

        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null) return null;
        File playerDir = new File(world.getWorldFolder(), "playerdata");
        File file = new File(playerDir, uuid.toString() + ".dat");
        if (!file.isFile()) {
            return null;
        }

        try (InputStream in = new FileInputStream(file)) {
            Object root = nbtReadCompressedMethod.invoke(null, in);
            if (root == null || !nbtCompoundClass.isInstance(root)) {
                return null;
            }

            ItemStack[] inventory = new ItemStack[41];
            ItemStack[] ender = new ItemStack[27];
            readPlayerInventoryFromNbt(root, "Inventory", inventory, true);
            readPlayerInventoryFromNbt(root, "EnderItems", ender, false);

            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            String name = offline != null ? offline.getName() : null;
            if (name == null || name.isBlank()) {
                name = uuid.toString();
            }
            OfflinePlayerData data = new OfflinePlayerData(name, inventory, ender);
            cleanupOfflinePlaceholders(data);
            return data;
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Could not read offline data from player file for " + uuid, ex);
            return null;
        }
    }

    private void readPlayerInventoryFromNbt(Object rootCompound, String key, ItemStack[] target, boolean playerInventory) throws Exception {
        if (rootCompound == null || target == null) return;
        Object list = nbtCompoundGetListMethod.invoke(rootCompound, key, 10);
        if (list == null || !nbtListClass.isInstance(list)) return;
        int size = ((Number) nbtListSizeMethod.invoke(list)).intValue();
        for (int i = 0; i < size; i++) {
            Object entry = nbtListGetCompoundMethod.invoke(list, i);
            if (entry == null || !nbtCompoundClass.isInstance(entry)) continue;
            int rawSlot = ((Number) nbtCompoundGetByteMethod.invoke(entry, "Slot")).intValue();
            int slot = playerInventory ? mapPlayerInventorySlot(rawSlot) : rawSlot;
            if (slot < 0 || slot >= target.length) continue;
            Object nmsStack = nmsItemStackFromTagMethod.invoke(null, entry);
            if (nmsStack == null || !nmsItemStackClass.isInstance(nmsStack)) continue;
            ItemStack bukkit = (ItemStack) craftItemStackAsBukkitCopyMethod.invoke(null, nmsStack);
            if (bukkit == null || bukkit.getType() == Material.AIR) continue;
            target[slot] = cloneOrNull(bukkit);
        }
    }

    private int mapPlayerInventorySlot(int rawSlot) {
        if (rawSlot >= 0 && rawSlot < 36) return rawSlot;
        int masked = rawSlot & 0xFF;
        switch (masked) {
            case 100: return 36; // boots
            case 101: return 37; // leggings
            case 102: return 38; // chestplate
            case 103: return 39; // helmet
            case 150: return 40; // offhand
            default: return -1;
        }
    }

    private Map<String, UUID> buildKnownNameIndex() {
        Map<String, UUID> index = new HashMap<>();

        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op == null) continue;
            String name = op.getName();
            if (name != null && !name.isBlank()) {
                index.putIfAbsent(name.toLowerCase(Locale.ROOT), op.getUniqueId());
            }
        }

        Map<UUID, String> fromCache = readKnownPlayersFromUsercache();
        for (Map.Entry<UUID, String> entry : fromCache.entrySet()) {
            String name = entry.getValue();
            if (name != null && !name.isBlank()) {
                index.putIfAbsent(name.toLowerCase(Locale.ROOT), entry.getKey());
            }
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online == null) continue;
            String name = online.getName();
            if (name != null && !name.isBlank()) {
                index.put(name.toLowerCase(Locale.ROOT), online.getUniqueId());
            }
        }

        return index;
    }

    private UUID resolveOfflineDataKey(String rawKey, String storedName, Map<String, UUID> knownNames) {
        try {
            return UUID.fromString(rawKey);
        } catch (IllegalArgumentException ignored) {
        }

        if (storedName != null && !storedName.isBlank()) {
            UUID resolved = resolveOfflineDataKeyByName(storedName, knownNames);
            if (resolved != null) {
                return resolved;
            }
        }

        if (rawKey != null && !rawKey.isBlank()) {
            return resolveOfflineDataKeyByName(rawKey, knownNames);
        }
        return null;
    }

    private UUID resolveOfflineDataKeyByName(String name, Map<String, UUID> knownNames) {
        if (name == null) return null;
        UUID cached = knownNames.get(name.toLowerCase(Locale.ROOT));
        if (cached != null) {
            return cached;
        }

        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (offline != null) {
                UUID uuid = offline.getUniqueId();
                if (uuid != null) {
                    return uuid;
                }
            }
        } catch (Exception ignored) {
        }

        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        return null;
    }

    private void mergeOfflineData(OfflinePlayerData target, OfflinePlayerData source) {
        if (target == null || source == null) return;

        if ((target.name == null || target.name.isBlank() || isLikelyUuidName(target.name))
                && source.name != null && !source.name.isBlank()) {
            target.name = source.name;
        }

        for (int i = 0; i < Math.min(target.inventory.length, source.inventory.length); i++) {
            if (target.inventory[i] == null && source.inventory[i] != null) {
                target.inventory[i] = cloneOrNull(source.inventory[i]);
            }
        }

        for (int i = 0; i < Math.min(target.ender.length, source.ender.length); i++) {
            if (target.ender[i] == null && source.ender[i] != null) {
                target.ender[i] = cloneOrNull(source.ender[i]);
            }
        }
    }

    private boolean isLikelyUuidName(String name) {
        if (name == null) return false;
        try {
            UUID.fromString(name);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void populateOfflineIndex() {
        Map<UUID, String> known = new HashMap<>();
        for (Map.Entry<UUID, OfflinePlayerData> entry : offlineData.entrySet()) {
            OfflinePlayerData data = entry.getValue();
            if (data != null && data.name != null && !data.name.isBlank()) {
                known.put(entry.getKey(), data.name);
            }
        }

        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op == null) continue;
            UUID uuid = op.getUniqueId();
            String name = op.getName();
            if (name != null && !name.isBlank()) {
                known.put(uuid, name);
            } else if (!known.containsKey(uuid)) {
                known.put(uuid, uuid.toString());
            }
        }

        known.putAll(readKnownPlayersFromUsercache());

        boolean changed = false;
        for (Map.Entry<UUID, String> entry : known.entrySet()) {
            UUID uuid = entry.getKey();
            String name = entry.getValue();
            if (name == null || name.isBlank()) {
                name = uuid.toString();
            }
            OfflinePlayerData data = offlineData.get(uuid);
            if (data == null) {
                offlineData.put(uuid, new OfflinePlayerData(name, new ItemStack[41], new ItemStack[27]));
                changed = true;
            } else if (data.name == null || data.name.isBlank()) {
                data.name = name;
                changed = true;
            }
        }

        if (changed) {
            saveOfflineData();
        }
    }

    private Map<UUID, String> readKnownPlayersFromUsercache() {
        Map<UUID, String> known = new HashMap<>();
        File container = getServer().getWorldContainer();
        if (container == null) return known;
        File cache = new File(container, "usercache.json");
        if (!cache.isFile()) return known;

        Pattern entryPattern = Pattern.compile("\\{([^}]*)\\}");
        Pattern uuidPattern = Pattern.compile("\"uuid\"\\s*:\\s*\"([^\"]+)\"");
        Pattern namePattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(cache), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            Matcher entryMatcher = entryPattern.matcher(sb.toString());
            while (entryMatcher.find()) {
                String body = entryMatcher.group(1);
                Matcher uuidMatcher = uuidPattern.matcher(body);
                Matcher nameMatcher = namePattern.matcher(body);
                if (uuidMatcher.find() && nameMatcher.find()) {
                    try {
                        UUID uuid = UUID.fromString(uuidMatcher.group(1));
                        String name = nameMatcher.group(1);
                        if (name != null && !name.isBlank()) {
                            known.putIfAbsent(uuid, name);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Could not read usercache.json", ex);
        }
        return known;
    }

    private void saveOfflineData() {
        if (offlineDataFile == null) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, OfflinePlayerData> entry : offlineData.entrySet()) {
            UUID uuid = entry.getKey();
            OfflinePlayerData data = entry.getValue();
            String base = "players." + uuid;
            cfg.set(base + ".name", data.name);
            cfg.set(base + ".inventory", Arrays.stream(data.inventory)
                    .map(this::cloneOrNull)
                    .collect(Collectors.toList()));
            cfg.set(base + ".ender", Arrays.stream(data.ender)
                    .map(this::cloneOrNull)
                    .collect(Collectors.toList()));
        }
        try {
            cfg.save(offlineDataFile);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not save offline data", e);
        }
    }

    private void storeOfflineData(Player player) {
        if (player == null) return;
        OfflinePlayerData data = new OfflinePlayerData(player.getName(),
                cloneArray(player.getInventory().getContents(), 41),
                cloneArray(player.getEnderChest().getContents(), 27));
        offlineData.put(player.getUniqueId(), data);
        saveOfflineData();
    }

    private void syncOfflineInventoryFromGui(Player admin, Inventory inv) {
        if (admin == null || inv == null) return;
        UUID viewerId = admin.getUniqueId();
        UUID targetId = offlineInventoryTargetByViewer.get(viewerId);
        if (targetId == null) return;
        OfflinePlayerData data = offlineData.get(targetId);
        if (data == null) return;

        for (int slot = 0; slot < 36; slot++) {
            data.inventory[slot] = cloneOrNull(inv.getItem(slot));
        }
        // Armor/offhand mapping to player inventory indexes
        data.inventory[39] = sanitizeArmorSlot(inv.getItem(36), EquipmentSlot.HEAD); // helmet
        data.inventory[38] = sanitizeArmorSlot(inv.getItem(37), EquipmentSlot.CHEST); // chestplate
        data.inventory[37] = sanitizeArmorSlot(inv.getItem(38), EquipmentSlot.LEGS); // leggings
        data.inventory[36] = sanitizeArmorSlot(inv.getItem(39), EquipmentSlot.FEET); // boots
        data.inventory[40] = sanitizeArmorSlot(inv.getItem(40), EquipmentSlot.OFF_HAND); // offhand

        saveOfflineData();
        ensureInventoryPlaceholders(inv, admin);
    }

    private void syncOfflineEnderFromGui(Player admin, Inventory inv) {
        if (admin == null || inv == null) return;
        UUID viewerId = admin.getUniqueId();
        UUID targetId = offlineEnderTargetByViewer.get(viewerId);
        if (targetId == null) return;
        OfflinePlayerData data = offlineData.get(targetId);
        if (data == null) return;
        for (int i = 0; i < Math.min(27, inv.getSize()); i++) {
            data.ender[i] = cloneOrNull(inv.getItem(i));
        }
        saveOfflineData();
    }

    private void syncOnlineInventoryFromGui(Player admin, Inventory inv) {
        if (admin == null || inv == null) return;
        UUID viewerId = admin.getUniqueId();
        UUID targetId = onlineInventoryTargetByViewer.get(viewerId);
        if (targetId == null) return;
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            onlineInventoryTargetByViewer.remove(viewerId);
            admin.closeInventory();
            admin.sendMessage(ChatColor.RED + Lang.tr(admin, "error.target_offline"));
            return;
        }

        PlayerInventory pi = target.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            pi.setItem(slot, cloneOrNull(inv.getItem(slot)));
        }
        pi.setHelmet(cloneOrNull(inv.getItem(36)));
        pi.setChestplate(cloneOrNull(inv.getItem(37)));
        pi.setLeggings(cloneOrNull(inv.getItem(38)));
        pi.setBoots(cloneOrNull(inv.getItem(39)));
        pi.setItemInOffHand(cloneOrNull(inv.getItem(40)));

        target.updateInventory();
        storeOfflineData(target);
        ensureInventoryPlaceholders(inv, admin);
    }

    private void syncPaletteFromGui(Player admin, Inventory top) {
        if (admin == null || top == null) return;
        List<Material> updated = new ArrayList<>();
        for (int i = 0; i < Math.min(45, top.getSize()); i++) {
            ItemStack item = top.getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;
            updated.add(item.getType());
        }
        this.paletteItems = updated;
        persistPaletteItems();
    }

    /* ====== Hilfe: Echtes Buch – mehrfach gesplittete Seiten, Back-Link auf jeder Seite ====== */

        private void openHelpBook(Player p) {
                ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
                BookMeta meta = (BookMeta) book.getItemMeta();
                if (meta != null) {
                        meta.setTitle("SimpleInventarEdit");
                        meta.setAuthor("SIE");

                        // Back-Link Builder (RUN_COMMAND /sie back)
                        java.util.function.Supplier<BaseComponent[]> backLink = () -> {
                                TextComponent link = new TextComponent("[" + Lang.tr(p, "ui.back") + " → " + Lang.tr(p, "ui.players_title") + "]");
                                link.setColor(net.md_5.bungee.api.ChatColor.AQUA);
                                link.setBold(true);
                                link.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sie back"));
                                link.setHoverEvent(new HoverEvent(
                                                HoverEvent.Action.SHOW_TEXT,
                                                new ComponentBuilder(Lang.tr(p, "ui.back"))
                                                                .color(net.md_5.bungee.api.ChatColor.YELLOW)
                                                                .create()
                                ));
                                return new ComponentBuilder("\n\n").append(link).create(); // Abstand + Link
                        };

                        List<BaseComponent[]> pages = new ArrayList<>();

                        // Seite 1: Spielerliste – Grundlagen
                        BaseComponent[] page1 = new ComponentBuilder()
                                        .append(Lang.tr(p,"help.page_players_basic_title")).bold(true).append("\n\n").bold(false)
                                        .append("• ").append(Lang.tr(p,"help.click_left_inv")).append("\n\n")
                                        .append("• ").append(Lang.tr(p,"help.click_right_armor")).append("\n\n")
                                        .append("• ").append(Lang.tr(p,"help.shift_click_ender"))
                                        .append(backLink.get())
                                        .create();
                        pages.add(page1);

                        if (enablePalette) {
                                // Seite 2: Spielerliste – Palette & Shortcuts (1/2)
                                BaseComponent[] pagePalette1 = new ComponentBuilder()
                                                .append(Lang.tr(p,"help.page_players_palette_title_part1")).bold(true).append("\n\n").bold(false)
                                                .append("• ").append(Lang.tr(p,"help.q_palette")).append("\n\n")
                                                .append("• ").append(Lang.tr(p,"help.palette_edit_toggle"))
                                                .append(backLink.get())
                                                .create();
                                pages.add(pagePalette1);

                                // Seite 3: Spielerliste – Palette & Shortcuts (2/2)
                                BaseComponent[] pagePalette2 = new ComponentBuilder()
                                                .append(Lang.tr(p,"help.page_players_palette_title_part2")).bold(true).append("\n\n").bold(false)
                                                .append("• ").append(Lang.tr(p,"help.palette_edit_auto")).append("\n\n")
                                                .append("• ").append(Lang.tr(p,"help.middle_creative"))
                                                .append(backLink.get())
                                                .create();
                                pages.add(pagePalette2);
                        }

                        // Nächste Seiten unabhängig von Palette-Einstellung
                        BaseComponent[] pageArmor = new ComponentBuilder()
                                        .append(Lang.tr(p,"help.armor_title")).bold(true).append("\n\n").bold(false)
                                        .append("• ").append(Lang.tr(p,"help.armor_put_take")).append("\n\n")
                                        .append("• ").append(Lang.tr(p,"help.armor_offhand")).append("\n\n")
                                        .append("• ").append(Lang.tr(p,"help.armor_types"))
                                        .append(backLink.get())
                                        .create();
                        pages.add(pageArmor);

                        BaseComponent[] pageDeleteMode = new ComponentBuilder()
                                        .append(Lang.tr(p,"help.delete_mode_title")).bold(true).append("\n\n").bold(false)
                                        .append(Lang.tr(p,"help.delete_mode_long1")).append("\n\n")
                                        .append(Lang.tr(p,"help.delete_mode_long2")).append("\n\n")
                                        .append(Lang.tr(p,"help.delete_mode_long3"))
                                        .append(backLink.get())
                                        .create();
                        pages.add(pageDeleteMode);

                        BaseComponent[] pageOffline1 = new ComponentBuilder()
                                        .append(Lang.tr(p,"help.offline_title_part1")).bold(true).append("\n\n").bold(false)
                                        .append("• ").append(Lang.tr(p,"help.offline_browse")).append("\n\n")
                                        .append("• ").append(Lang.tr(p,"help.offline_inventory"))
                                        .append(backLink.get())
                                        .create();
                        pages.add(pageOffline1);

                        BaseComponent[] pageOffline2 = new ComponentBuilder()
                                        .append(Lang.tr(p,"help.offline_title_part2")).bold(true).append("\n\n").bold(false)
                                        .append("• ").append(Lang.tr(p,"help.offline_ender")).append("\n\n")
                                        .append("• ").append(Lang.tr(p,"help.offline_apply"))
                                        .append(backLink.get())
                                        .create();
                        pages.add(pageOffline2);

                        meta.spigot().addPage(pages.toArray(new BaseComponent[0][]));
                        book.setItemMeta(meta);
                }
                p.openBook(book);
        }


    /* ====== Spielerliste ====== */

    private void openPlayerList(Player admin, int page) {
        lastListPageByViewer.put(admin.getUniqueId(), page);

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        Inventory inv = Bukkit.createInventory(admin, GUI_SIZE, titlePlayers(admin));
        int start = page * 45; // 5 Reihen für Spieler
        int end = Math.min(start + 45, online.size());
        for (int i = start; i < end; i++) {
            Player target = online.get(i);

            List<String> lore = new ArrayList<>();
            lore.add("§e" + Lang.tr(admin, "help.click_left_inv"));
            lore.add("§e" + Lang.tr(admin, "help.click_right_armor"));
            lore.add("§e" + Lang.tr(admin, "help.shift_click_ender"));
            if (enablePalette) {
                lore.add("§e" + Lang.tr(admin, "help.q_palette"));
                lore.add("§8" + Lang.tr(admin, "help.middle_creative"));
            }
            inv.setItem(i - start, headButton(admin, target.getName(), target.getUniqueId(), lore));
        }

        // Navigation & Tools
        if (page > 0) inv.setItem(45, named(Material.ARROW, ChatColor.AQUA + Lang.tr(admin, "ui.back")));
        inv.setItem(49, named(Material.BARRIER, ChatColor.RED + Lang.tr(admin, "ui.close")));

        // Delete-mode toggle (slot 48) – kurze Tooltips
        boolean on = deleteMode.contains(admin.getUniqueId());
        Material toggleMat = on ? Material.LAVA_BUCKET : Material.BUCKET;
        String name = on ? "§c" + Lang.tr(admin, "ui.delete_mode_on")
                         : "§7" + Lang.tr(admin, "ui.delete_mode_off");
        List<String> toggleLore = Arrays.asList(
                "§7" + Lang.tr(admin, "ui.delete_mode_hint1"),
                "§7" + Lang.tr(admin, "ui.delete_mode_hint2")
        );
        inv.setItem(48, named(toggleMat, name, toggleLore));

        // Hilfe-Button
        inv.setItem(50, named(Material.WRITTEN_BOOK, ChatColor.GOLD + Lang.tr(admin, "ui.help")));

        // Offline-Liste öffnen
        List<String> offlineLore = Arrays.asList(
                "§7" + Lang.tr(admin, "ui.offline_toggle_hint1"),
                "§7" + Lang.tr(admin, "ui.offline_toggle_hint2")
        );
        inv.setItem(47, named(Material.COMPASS, ChatColor.GOLD + Lang.tr(admin, "ui.offline_toggle"), offlineLore));

        if (end < online.size()) inv.setItem(53, named(Material.ARROW, ChatColor.AQUA + Lang.tr(admin, "ui.next")));

        admin.openInventory(inv);
        admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    private void openOfflineList(Player admin, int page) {
        offlineListPageByViewer.put(admin.getUniqueId(), page);

        List<Map.Entry<UUID, OfflinePlayerData>> offline = offlineData.entrySet().stream()
                .filter(entry -> {
                    Player online = Bukkit.getPlayer(entry.getKey());
                    return online == null || !online.isOnline();
                })
                .sorted(Comparator.comparing(entry -> {
                    OfflinePlayerData data = entry.getValue();
                    String name = data != null ? data.name : null;
                    if (name == null || name.isBlank()) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(entry.getKey());
                        name = op != null ? op.getName() : entry.getKey().toString();
                        if (data != null) data.name = name;
                    }
                    return name.toLowerCase(Locale.ROOT);
                }))
                .collect(Collectors.toList());

        Inventory inv = Bukkit.createInventory(admin, GUI_SIZE, titleOfflinePlayers(admin));

        int start = page * 45;
        if (start >= offline.size() && page > 0) {
            page = 0;
            start = 0;
            offlineListPageByViewer.put(admin.getUniqueId(), 0);
        }
        int end = Math.min(start + 45, offline.size());
        for (int i = start; i < end; i++) {
            Map.Entry<UUID, OfflinePlayerData> entry = offline.get(i);
            UUID uuid = entry.getKey();
            OfflinePlayerData data = entry.getValue();
            String name = data != null ? data.name : null;
            if (name == null || name.isBlank()) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                name = op != null ? op.getName() : uuid.toString();
                if (data != null) data.name = name;
            }
            List<String> lore = new ArrayList<>();
            lore.add("§e" + Lang.tr(admin, "ui.offline_inventory_hint"));
            lore.add("§e" + Lang.tr(admin, "ui.offline_ender_hint"));
            inv.setItem(i - start, headButton(admin, name, uuid, lore));
        }

        if (page > 0) {
            inv.setItem(45, named(Material.ARROW, ChatColor.AQUA + Lang.tr(admin, "ui.back")));
        }
        inv.setItem(48, named(Material.COMPASS, ChatColor.GOLD + Lang.tr(admin, "ui.offline_back")));
        inv.setItem(49, named(Material.BARRIER, ChatColor.RED + Lang.tr(admin, "ui.close")));
        inv.setItem(50, named(Material.WRITTEN_BOOK, ChatColor.GOLD + Lang.tr(admin, "ui.help")));

        if (end < offline.size()) {
            inv.setItem(53, named(Material.ARROW, ChatColor.AQUA + Lang.tr(admin, "ui.next")));
        }

        admin.openInventory(inv);
        admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.1f);
    }

    private void openOfflineInventory(Player admin, UUID targetId) {
        OfflinePlayerData data = ensureOfflineDataLoaded(targetId);
        if (data == null) {
            admin.sendMessage(Lang.tr(admin, "ui.offline_no_data"));
            return;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetId);
        String name = data.name;
        if ((name == null || name.isBlank()) && offline != null) {
            name = offline.getName();
        }
        if (name == null || name.isBlank()) name = targetId.toString();
        data.name = name;

        Inventory inv = Bukkit.createInventory(admin, GUI_SIZE, titleOfflineInventory(admin, name));
        offlineInventoryTargetByViewer.put(admin.getUniqueId(), targetId);
        fillOfflineInventory(inv, data, admin);
        admin.openInventory(inv);
        admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.1f);
    }

    private void fillOfflineInventory(Inventory inv, OfflinePlayerData data, Player viewer) {
        inv.clear();
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, cloneOrNull(data.inventory[i]));
        }

        inv.setItem(36, safeClone(data.inventory[39], armorPlaceholder(viewer, EquipmentSlot.HEAD)));
        inv.setItem(37, safeClone(data.inventory[38], armorPlaceholder(viewer, EquipmentSlot.CHEST)));
        inv.setItem(38, safeClone(data.inventory[37], armorPlaceholder(viewer, EquipmentSlot.LEGS)));
        inv.setItem(39, safeClone(data.inventory[36], armorPlaceholder(viewer, EquipmentSlot.FEET)));
        inv.setItem(40, safeClone(data.inventory[40], armorPlaceholder(viewer, EquipmentSlot.OFF_HAND)));

        decorateInventoryControls(inv, viewer);
    }

    private void openOfflineEnderChest(Player admin, UUID targetId) {
        OfflinePlayerData data = ensureOfflineDataLoaded(targetId);
        if (data == null) {
            admin.sendMessage(Lang.tr(admin, "ui.offline_no_data"));
            return;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetId);
        String name = data.name;
        if ((name == null || name.isBlank()) && offline != null) {
            name = offline.getName();
        }
        if (name == null || name.isBlank()) name = targetId.toString();
        data.name = name;

        Inventory inv = Bukkit.createInventory(admin, 36, titleOfflineEnder(admin, name));
        offlineEnderTargetByViewer.put(admin.getUniqueId(), targetId);

        for (int i = 0; i < 27; i++) {
            inv.setItem(i, cloneOrNull(data.ender[i]));
        }

        for (int i = 27; i < 36; i++) {
            inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " "));
        }
        inv.setItem(27, named(Material.ARROW, ChatColor.AQUA + Lang.tr(admin, "ui.back")));
        inv.setItem(31, named(Material.BARRIER, ChatColor.RED + Lang.tr(admin, "ui.close")));

        admin.openInventory(inv);
        admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.1f);
    }

    /* ====== Events ====== */

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        HumanEntity who = e.getWhoClicked();
        if (!(who instanceof Player admin)) return;

        String title = e.getView().getTitle();
        UUID adminId = admin.getUniqueId();
        boolean inDelete = deleteMode.contains(adminId);

        // ---- Spielerliste ----
        if (title.equals(titlePlayers(admin))) {
            // Unten (Admin-Inventar) frei
            if (e.getClickedInventory() == e.getView().getBottomInventory()) return;

            int raw = e.getRawSlot();
            ItemStack cur = e.getCurrentItem();
            if (cur == null || cur.getType() == Material.AIR) return;

            // Steuerleiste (Back/Close/Toggle/Help/Next)
            if (raw == 45) { e.setCancelled(true); openPlayerList(admin, 0); return; }
            if (raw == 47) {
                e.setCancelled(true);
                int offPage = offlineListPageByViewer.getOrDefault(adminId, 0);
                openOfflineList(admin, offPage);
                return;
            }
            if (raw == 49) { e.setCancelled(true); admin.closeInventory(); return; }
            if (raw == 48) {
                e.setCancelled(true);
                if (deleteMode.contains(adminId)) deleteMode.remove(adminId); else deleteMode.add(adminId);
                int page = lastListPageByViewer.getOrDefault(adminId, 0);
                openPlayerList(admin, page);
                return;
            }
            if (raw == 50) { e.setCancelled(true); openHelpBook(admin); return; }
            if (raw == 53) { e.setCancelled(true); openPlayerList(admin, 1); return; }

            // Spieler-Köpfe (0..44)
            if (raw >= 0 && raw < 45) {
                String name = (cur.hasItemMeta() && cur.getItemMeta().hasDisplayName())
                        ? ChatColor.stripColor(cur.getItemMeta().getDisplayName())
                        : null;
                if (name == null || name.isBlank()) return;

                Player target = Bukkit.getPlayerExact(name);
                if (target == null || !target.isOnline()) {
                    e.setCancelled(true);
                    admin.sendMessage(ChatColor.RED + Lang.tr(admin, "error.player_offline"));
                    admin.closeInventory();
                    return;
                }

                // Erst nach Entscheidung canceln -> DROP/CONTROL_DROP/RIGHT korrekt erkannt
                if (e.getClick().isShiftClick()) {
                    e.setCancelled(true);
                    openTargetEnderChest(admin, target);
                } else if (e.getClick().isRightClick()) {
                    e.setCancelled(true);
                    openArmorGui(admin, target);
                } else if (enablePalette &&
                        (e.getClick() == ClickType.MIDDLE
                                || e.getClick() == ClickType.DROP
                                || e.getClick() == ClickType.CONTROL_DROP)) {
                    e.setCancelled(true);
                    openPaletteGui(admin, target, 0);
                } else {
                    e.setCancelled(true);
                    openTargetInventory(admin, target);
                }
                return;
            }
            return;
        }

        // ---- Offline-Spielerliste ----
        if (title.equals(titleOfflinePlayers(admin))) {
            if (e.getClickedInventory() == e.getView().getBottomInventory()) return;

            e.setCancelled(true);
            int page = offlineListPageByViewer.getOrDefault(adminId, 0);
            int raw = e.getRawSlot();
            ItemStack cur = e.getCurrentItem();

            if (raw == 45 && page > 0) { openOfflineList(admin, page - 1); return; }
            if (raw == 48) { openPlayerList(admin, lastListPageByViewer.getOrDefault(adminId, 0)); return; }
            if (raw == 49) { admin.closeInventory(); return; }
            if (raw == 50) { openHelpBook(admin); return; }
            if (raw == 53) { openOfflineList(admin, page + 1); return; }

            if (raw < 0 || raw >= 45 || cur == null || cur.getType() == Material.AIR) return;

            UUID targetId = null;
            if (cur.getItemMeta() instanceof SkullMeta skull && skull.getOwningPlayer() != null) {
                targetId = skull.getOwningPlayer().getUniqueId();
            }
            if (targetId == null) {
                admin.sendMessage(Lang.tr(admin, "ui.offline_no_data"));
                return;
            }
            if (!offlineData.containsKey(targetId)) {
                admin.sendMessage(Lang.tr(admin, "ui.offline_no_data"));
                return;
            }

            if (e.getClick().isRightClick()) {
                openOfflineEnderChest(admin, targetId);
            } else {
                openOfflineInventory(admin, targetId);
            }
            return;
        }

        // ---- Online-Inventar (GUI) ----
        if (onlineInventoryTargetByViewer.containsKey(adminId)) {
            Inventory top = e.getView().getTopInventory();
            if (e.getClickedInventory() == e.getView().getBottomInventory()) {
                Inventory topInv = top;
                Bukkit.getScheduler().runTask(this, () -> syncOnlineInventoryFromGui(admin, topInv));
                return;
            }

            int raw = e.getRawSlot();
            if (raw < 0 || raw >= top.getSize()) return;

            if (raw == 45) {
                e.setCancelled(true);
                int page = lastListPageByViewer.getOrDefault(adminId, 0);
                openPlayerList(admin, page);
                return;
            }
            if (raw == 47) { e.setCancelled(true); openHelpBook(admin); return; }
            if (raw == 49) { e.setCancelled(true); admin.closeInventory(); return; }

            if ((raw >= 41 && raw <= 44) || raw >= 46) {
                e.setCancelled(true);
                return;
            }

            ItemStack clicked = e.getCurrentItem();
            ItemStack cursor = e.getCursor();
            boolean cursorEmpty = cursor == null || cursor.getType() == Material.AIR;

            if (raw >= 36 && raw <= 40) {
                EquipmentSlot slot = switch (raw) {
                    case 36 -> EquipmentSlot.HEAD;
                    case 37 -> EquipmentSlot.CHEST;
                    case 38 -> EquipmentSlot.LEGS;
                    case 39 -> EquipmentSlot.FEET;
                    case 40 -> EquipmentSlot.OFF_HAND;
                    default -> EquipmentSlot.HAND;
                };

                if (cursorEmpty) {
                    if (isPlaceholder(clicked, slot)) {
                        e.setCancelled(true);
                        return;
                    }
                } else if (cursor != null) {
                    if (!isAllowedInArmorSlot(cursor.getType(), slot)) {
                        e.setCancelled(true);
                        admin.playSound(admin.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
                        admin.sendMessage(ChatColor.RED + Lang.tr(admin, "error.invalid_armor_type"));
                        return;
                    }
                    if (slot != EquipmentSlot.OFF_HAND && cursor.getAmount() > 1) {
                        e.setCancelled(true);
                        admin.playSound(admin.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
                        admin.sendMessage(ChatColor.RED + Lang.tr(admin, "error.armor_stack"));
                        return;
                    }

                    if (isPlaceholder(clicked, slot)) {
                        e.setCancelled(true);
                        ItemStack toPlace = cursor.clone();
                        if (slot != EquipmentSlot.OFF_HAND) {
                            toPlace.setAmount(1);
                        }
                        top.setItem(raw, toPlace);
                        e.setCursor(new ItemStack(Material.AIR));
                        Inventory topInv = top;
                        Bukkit.getScheduler().runTask(this, () -> syncOnlineInventoryFromGui(admin, topInv));
                        return;
                    }
                }
            }

            if (inDelete && clicked != null && clicked.getType() != Material.AIR) {
                if (isPlaceholder(clicked)) {
                    e.setCancelled(true);
                    return;
                }
                if (e.getClickedInventory() == top) {
                    e.setCancelled(true);
                    if (e.getClick().isRightClick()) {
                        ItemStack mod = clicked.clone();
                        mod.setAmount(Math.max(0, mod.getAmount() - 1));
                        if (mod.getAmount() <= 0) {
                            e.setCurrentItem(new ItemStack(Material.AIR));
                        } else {
                            e.setCurrentItem(mod);
                        }
                    } else {
                        e.setCurrentItem(new ItemStack(Material.AIR));
                    }
                    admin.playSound(admin.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.6f, 1.5f);
                    Inventory topInv = top;
                    Bukkit.getScheduler().runTask(this, () -> syncOnlineInventoryFromGui(admin, topInv));
                    return;
                }
            }

            e.setCancelled(false);
            Inventory topInv = top;
            Bukkit.getScheduler().runTask(this, () -> syncOnlineInventoryFromGui(admin, topInv));
            return;
        }

        // ---- Offline-Inventar ----
        if (offlineInventoryTargetByViewer.containsKey(adminId)) {
            Inventory top = e.getView().getTopInventory();
            if (e.getClickedInventory() == e.getView().getBottomInventory()) {
                Inventory topInv = top;
                Bukkit.getScheduler().runTask(this, () -> syncOfflineInventoryFromGui(admin, topInv));
                return;
            }
            int raw = e.getRawSlot();
            if (raw < 0 || raw >= top.getSize()) return;

            if (raw == 45) {
                e.setCancelled(true);
                int page = offlineListPageByViewer.getOrDefault(adminId, 0);
                openOfflineList(admin, page);
                return;
            }
            if (raw == 47) { e.setCancelled(true); openHelpBook(admin); return; }
            if (raw == 49) { e.setCancelled(true); admin.closeInventory(); return; }

            if ((raw >= 41 && raw <= 44) || raw >= 46) {
                e.setCancelled(true);
                return;
            }

            ItemStack clicked = e.getCurrentItem();
            ItemStack cursor = e.getCursor();
            boolean cursorEmpty = cursor == null || cursor.getType() == Material.AIR;

            if (raw >= 36 && raw <= 40) {
                EquipmentSlot slot = switch (raw) {
                    case 36 -> EquipmentSlot.HEAD;
                    case 37 -> EquipmentSlot.CHEST;
                    case 38 -> EquipmentSlot.LEGS;
                    case 39 -> EquipmentSlot.FEET;
                    case 40 -> EquipmentSlot.OFF_HAND;
                    default -> EquipmentSlot.HAND;
                };

                ItemStack current = top.getItem(raw);
                if ((e.getCursor() == null || e.getCursor().getType() == Material.AIR) && isPlaceholder(current, slot)) {
                    e.setCancelled(true);
                    return;
                }

                if (cursor != null && cursor.getType() != Material.AIR) {
                    if (!isAllowedInArmorSlot(cursor.getType(), slot)) {
                        e.setCancelled(true);
                        admin.playSound(admin.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
                        admin.sendMessage(ChatColor.RED + Lang.tr(admin, "error.invalid_armor_type"));
                        return;
                    }
                    if (slot != EquipmentSlot.OFF_HAND && cursor.getAmount() > 1) {
                        e.setCancelled(true);
                        admin.playSound(admin.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
                        admin.sendMessage(ChatColor.RED + Lang.tr(admin, "error.armor_stack"));
                        return;
                    }

                    if (isPlaceholder(current, slot)) {
                        e.setCancelled(true);
                        ItemStack toPlace = cursor.clone();
                        if (slot != EquipmentSlot.OFF_HAND) {
                            toPlace.setAmount(1);
                        }
                        top.setItem(raw, toPlace);
                        e.setCursor(new ItemStack(Material.AIR));
                        Inventory topInv = top;
                        Bukkit.getScheduler().runTask(this, () -> syncOfflineInventoryFromGui(admin, topInv));
                        return;
                    }
                }
            }

            if (raw == 40) {
                if (cursorEmpty && isPlaceholder(clicked)) {
                    e.setCancelled(true);
                    return;
                }
            }

            if (inDelete && clicked != null && clicked.getType() != Material.AIR) {
                if (isPlaceholder(clicked)) {
                    e.setCancelled(true);
                    return;
                }
                if (e.getClickedInventory() == top) {
                    e.setCancelled(true);
                    if (e.getClick().isRightClick()) {
                        ItemStack mod = clicked.clone();
                        mod.setAmount(Math.max(0, mod.getAmount() - 1));
                        if (mod.getAmount() <= 0) {
                            e.setCurrentItem(new ItemStack(Material.AIR));
                        } else {
                            e.setCurrentItem(mod);
                        }
                    } else {
                        e.setCurrentItem(new ItemStack(Material.AIR));
                    }
                    admin.playSound(admin.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.6f, 1.5f);
                    Inventory topInv = top;
                    Bukkit.getScheduler().runTask(this, () -> syncOfflineInventoryFromGui(admin, topInv));
                    return;
                }
            }

            e.setCancelled(false);
            Inventory topInv = top;
            Bukkit.getScheduler().runTask(this, () -> syncOfflineInventoryFromGui(admin, topInv));
            return;
        }

        // ---- Offline-Endertruhe ----
        if (offlineEnderTargetByViewer.containsKey(adminId)) {
            if (e.getClickedInventory() == e.getView().getBottomInventory()) return;

            Inventory top = e.getView().getTopInventory();
            int raw = e.getRawSlot();
            if (raw < 0 || raw >= top.getSize()) return;

            if (raw == 27) {
                e.setCancelled(true);
                int page = offlineListPageByViewer.getOrDefault(adminId, 0);
                openOfflineList(admin, page);
                return;
            }
            if (raw == 31) { e.setCancelled(true); admin.closeInventory(); return; }

            if (raw >= 27) {
                e.setCancelled(true);
                return;
            }

            e.setCancelled(false);
            Inventory topInv = top;
            Bukkit.getScheduler().runTask(this, () -> syncOfflineEnderFromGui(admin, topInv));
            return;
        }

        // ---- Armor-GUI ----
        if (armorGuiTargetByViewer.containsKey(adminId)) {
            // unten (Admin-Inventar) frei lassen
            if (e.getClickedInventory() == e.getView().getBottomInventory()) return;

            e.setCancelled(true);

            int raw = e.getRawSlot();
            if (raw == 8) { // Barrier = Zurück
                int page = lastListPageByViewer.getOrDefault(adminId, 0);
                openPlayerList(admin, page);
                return;
            }
            if (raw < 0 || raw > 4) return;

            UUID targetId = armorGuiTargetByViewer.get(adminId);
            if (targetId == null) return;
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                admin.closeInventory();
                return;
            }

            ItemStack cursor = e.getCursor();
            PlayerInventory pi = target.getInventory();

            ItemStack current = switch (raw) {
                case 0 -> pi.getHelmet();
                case 1 -> pi.getChestplate();
                case 2 -> pi.getLeggings();
                case 3 -> pi.getBoots();
                case 4 -> pi.getItemInOffHand();
                default -> null;
            };

            // Platzieren
            if (cursor != null && cursor.getType() != Material.AIR) {
                EquipmentSlot slot = switch (raw) {
                    case 0 -> EquipmentSlot.HEAD;
                    case 1 -> EquipmentSlot.CHEST;
                    case 2 -> EquipmentSlot.LEGS;
                    case 3 -> EquipmentSlot.FEET;
                    case 4 -> EquipmentSlot.OFF_HAND;
                    default -> EquipmentSlot.HAND;
                };
                if (!isAllowedInArmorSlot(cursor.getType(), slot)) {
                    admin.playSound(admin.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
                    admin.sendMessage(ChatColor.RED + Lang.tr(admin, "error.invalid_armor_type"));
                    return;
                }
                ItemStack toPlace = cursor.clone();
                if (raw != 4) toPlace.setAmount(1); // Armor = 1

                // Swap in Cursor
                if (current != null && current.getType() != Material.AIR) {
                    e.setCursor(current.clone());
                } else {
                    e.setCursor(new ItemStack(Material.AIR));
                }

                switch (raw) {
                    case 0 -> pi.setHelmet(toPlace);
                    case 1 -> pi.setChestplate(toPlace);
                    case 2 -> pi.setLeggings(toPlace);
                    case 3 -> pi.setBoots(toPlace);
                    case 4 -> pi.setItemInOffHand(toPlace);
                }
                fillArmorGui(e.getView().getTopInventory(), target);
                admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                return;
            }

            // Aufnehmen
            if (cursor == null || cursor.getType() == Material.AIR) {
                if (current != null && current.getType() != Material.AIR) {
                    e.setCursor(current.clone());
                    switch (raw) {
                        case 0 -> pi.setHelmet(null);
                        case 1 -> pi.setChestplate(null);
                        case 2 -> pi.setLeggings(null);
                        case 3 -> pi.setBoots(null);
                        case 4 -> pi.setItemInOffHand(null);
                    }
                    fillArmorGui(e.getView().getTopInventory(), target);
                    admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.1f);
                }
                return;
            }
            return;
        }

        // ---- Palette ----
        if (paletteTargetByViewer.containsKey(adminId)) {
            // unten (Admin-Inventar) frei lassen
            if (e.getClickedInventory() == e.getView().getBottomInventory()) return;

            UUID targetId = paletteTargetByViewer.get(adminId);
            if (targetId == null) return;
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                admin.closeInventory();
                return;
            }

            int raw = e.getRawSlot();
            if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) return;
            ItemStack clicked = e.getCurrentItem();

            boolean editing = paletteEditable && paletteEditMode.contains(adminId);

            // Toggle-Button (Slot 48)
            if (paletteEditable && raw == 48) {
                e.setCancelled(true);
                Inventory topInv = e.getView().getTopInventory();
                if (editing) {
                    syncPaletteFromGui(admin, topInv);
                    paletteEditMode.remove(adminId);
                    refillPaletteItems(topInv);
                    topInv.setItem(48, paletteToggleItem(admin, false));
                } else {
                    paletteEditMode.add(adminId);
                    topInv.setItem(48, paletteToggleItem(admin, true));
                }
                admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                return;
            }

            // Barrier = Zurück
            if (raw == 49 && clicked != null && clicked.getType() == Material.BARRIER) {
                e.setCancelled(true);
                paletteEditMode.remove(adminId);
                int page = lastListPageByViewer.getOrDefault(adminId, 0);
                openPlayerList(admin, page);
                return;
            }

            if (editing) {
                if (raw >= 45) {
                    e.setCancelled(true);
                    return;
                }
                e.setCancelled(false);
                Inventory topInv = e.getView().getTopInventory();
                Bukkit.getScheduler().runTask(this, () -> syncPaletteFromGui(admin, topInv));
                return;
            }

            e.setCancelled(true);
            if (clicked == null || clicked.getType() == Material.AIR) return;

            // Nur echte Items geben
            if (clicked.getType() != Material.GRAY_STAINED_GLASS_PANE && clicked.getType() != Material.BARRIER) {
                int addAmount = clicked.getMaxStackSize(); // Standard: voller Stack
                if (e.getClick().isRightClick()) addAmount = 1; // Rechtsklick = 1 Stück
                ItemStack give = clicked.clone();
                give.setAmount(Math.max(1, Math.min(addAmount, clicked.getMaxStackSize())));
                Map<Integer, ItemStack> leftover = target.getInventory().addItem(give);
                if (!leftover.isEmpty()) {
                    leftover.values().forEach(rest ->
                            target.getWorld().dropItemNaturally(target.getLocation(), rest));
                }
                admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            }
            return;
        }

        // ---- Löschmodus in nativen Ziel-Inventaren / Enderchests ----
        if (inDelete && (viewingTargetInventory.contains(adminId) || viewingTargetEnder.contains(adminId))) {
            // Nur oberes (Ziel-)Inventar behandeln
            if (e.getClickedInventory() != null && e.getClickedInventory() == e.getView().getTopInventory()) {
                ItemStack clicked = e.getCurrentItem();
                if (clicked != null && clicked.getType() != Material.AIR) {
                    e.setCancelled(true);
                    if (e.getClick().isRightClick()) {
                        int amt = clicked.getAmount();
                        amt -= 1;
                        if (amt <= 0) e.setCurrentItem(new ItemStack(Material.AIR));
                        else { clicked.setAmount(amt); e.setCurrentItem(clicked); }
                    } else {
                        e.setCurrentItem(new ItemStack(Material.AIR));
                    }
                    admin.playSound(admin.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.6f, 1.5f);
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player admin)) return;

        UUID uid = admin.getUniqueId();
        String closedTitle = e.getView().getTitle();

        // Armor/Palette-GUIs: nur reagieren, wenn DIESE GUIs wirklich geschlossen wurden
        String strippedTitle = ChatColor.stripColor(closedTitle);
        boolean closedArmor = strippedTitle.startsWith(ChatColor.stripColor(Lang.tr(admin, "ui.armor_title_prefix")));
        boolean closedPalette = strippedTitle.startsWith(ChatColor.stripColor(Lang.tr(admin, "ui.palette_title_prefix")));
        boolean closedOnlineInv = strippedTitle.startsWith(ChatColor.stripColor(Lang.tr(admin, "ui.inventory_prefix")));
        boolean closedOfflineInv = strippedTitle.startsWith(ChatColor.stripColor(Lang.tr(admin, "ui.offline_inventory_prefix")));
        boolean closedOfflineEnder = strippedTitle.startsWith(ChatColor.stripColor(Lang.tr(admin, "ui.offline_ender_prefix")));

        if (closedOnlineInv && onlineInventoryTargetByViewer.containsKey(uid)) {
            syncOnlineInventoryFromGui(admin, e.getInventory());
            onlineInventoryTargetByViewer.remove(uid);
        }

        if (closedArmor) armorGuiTargetByViewer.remove(uid);
        if (closedPalette) {
            paletteTargetByViewer.remove(uid);
            paletteEditMode.remove(uid);
            syncPaletteFromGui(admin, e.getInventory());
        }
        if (closedOfflineInv) {
            syncOfflineInventoryFromGui(admin, e.getInventory());
            offlineInventoryTargetByViewer.remove(uid);
        }
        if (closedOfflineEnder) {
            syncOfflineEnderFromGui(admin, e.getInventory());
            offlineEnderTargetByViewer.remove(uid);
        }

        boolean wasTargetInv = viewingTargetInventory.remove(uid);
        boolean wasTargetEnd = viewingTargetEnder.remove(uid);

        if (backOnClose) {
            if (closedOfflineInv || closedOfflineEnder) {
                int page = offlineListPageByViewer.getOrDefault(uid, 0);
                Bukkit.getScheduler().runTask(this, () -> openOfflineList(admin, page));
                return;
            }

            if (closedArmor || closedPalette || closedOnlineInv || wasTargetInv || wasTargetEnd) {
                int page = lastListPageByViewer.getOrDefault(uid, 0);
                Bukkit.getScheduler().runTask(this, () -> openPlayerList(admin, page));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID uid = player.getUniqueId();
        OfflinePlayerData data = offlineData.remove(uid);
        if (data != null) {
            player.getInventory().setContents(cloneArray(data.inventory, 41));
            player.getEnderChest().setContents(cloneArray(data.ender, 27));
            saveOfflineData();
        }
        storeOfflineData(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID targetId = e.getPlayer().getUniqueId();
        storeOfflineData(e.getPlayer());
        Set<UUID> viewers = viewersByTarget.remove(targetId);
        if (viewers != null) {
            for (UUID v : viewers) {
                Player admin = Bukkit.getPlayer(v);
                if (admin != null && admin.isOnline()) {
                    admin.closeInventory();
                    admin.sendMessage(ChatColor.YELLOW + Lang.tr(admin, "info.target_left"));
                }
            }
        }
        deleteMode.remove(e.getPlayer().getUniqueId());
        paletteEditMode.remove(e.getPlayer().getUniqueId());
        offlineInventoryTargetByViewer.remove(e.getPlayer().getUniqueId());
        offlineEnderTargetByViewer.remove(e.getPlayer().getUniqueId());
        onlineInventoryTargetByViewer.remove(e.getPlayer().getUniqueId());
    }

    /* ====== Öffnen ====== */

    private void openTargetInventory(Player admin, Player target) {
        Inventory gui = Bukkit.createInventory(admin, GUI_SIZE, titleInventory(admin, target.getName()));
        onlineInventoryTargetByViewer.put(admin.getUniqueId(), target.getUniqueId());
        addViewer(admin, target);
        fillOnlineInventory(gui, target, admin);
        admin.openInventory(gui);
        admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.1f);
    }

    private void fillOnlineInventory(Inventory inv, Player target, Player viewer) {
        inv.clear();
        PlayerInventory pi = target.getInventory();
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, cloneOrNull(pi.getItem(i)));
        }
        inv.setItem(36, safeClone(pi.getHelmet(), armorPlaceholder(viewer, EquipmentSlot.HEAD)));
        inv.setItem(37, safeClone(pi.getChestplate(), armorPlaceholder(viewer, EquipmentSlot.CHEST)));
        inv.setItem(38, safeClone(pi.getLeggings(), armorPlaceholder(viewer, EquipmentSlot.LEGS)));
        inv.setItem(39, safeClone(pi.getBoots(), armorPlaceholder(viewer, EquipmentSlot.FEET)));
        inv.setItem(40, safeClone(pi.getItemInOffHand(), armorPlaceholder(viewer, EquipmentSlot.OFF_HAND)));
        decorateInventoryControls(inv, viewer);
    }

    private void openTargetEnderChest(Player admin, Player target) {
        admin.openInventory(target.getEnderChest());
        addViewer(admin, target);
        Bukkit.getScheduler().runTask(this, () -> viewingTargetEnder.add(admin.getUniqueId()));
    }

    /* ====== Armor GUI (editierbar) ====== */

    private void openArmorGui(Player admin, Player target) {
        Inventory gui = Bukkit.createInventory(admin, 9, titleArmor(admin, target.getName()));
        armorGuiTargetByViewer.put(admin.getUniqueId(), target.getUniqueId());
        addViewer(admin, target);
        fillArmorGui(gui, target);
        admin.openInventory(gui);
    }

    private void fillArmorGui(Inventory inv, Player target) {
        inv.clear();
        PlayerInventory pi = target.getInventory();

        inv.setItem(0, safeClone(pi.getHelmet(),        armorPlaceholder(target, EquipmentSlot.HEAD)));
        inv.setItem(1, safeClone(pi.getChestplate(),    armorPlaceholder(target, EquipmentSlot.CHEST)));
        inv.setItem(2, safeClone(pi.getLeggings(),      armorPlaceholder(target, EquipmentSlot.LEGS)));
        inv.setItem(3, safeClone(pi.getBoots(),         armorPlaceholder(target, EquipmentSlot.FEET)));
        inv.setItem(4, safeClone(pi.getItemInOffHand(), armorPlaceholder(target, EquipmentSlot.OFF_HAND)));

        for (int i = 5; i <= 7; i++) {
            inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " "));
        }
        inv.setItem(8, named(Material.BARRIER, ChatColor.AQUA + Lang.tr(target, "ui.back")));
    }

    private boolean isValidForArmorSlot(int slot, Material type) {
        EquipmentSlot es = switch (slot) {
            case 0 -> EquipmentSlot.HEAD;
            case 1 -> EquipmentSlot.CHEST;
            case 2 -> EquipmentSlot.LEGS;
            case 3 -> EquipmentSlot.FEET;
            case 4 -> EquipmentSlot.OFF_HAND;
            default -> EquipmentSlot.HAND;
        };
        return isAllowedInArmorSlot(type, es);
    }

    /* ====== Palette ====== */

    private void openPaletteGui(Player admin, Player target, int page) {
        if (!enablePalette) return;
        Inventory inv = Bukkit.createInventory(admin, GUI_SIZE, titlePalette(admin, target.getName()));
        paletteTargetByViewer.put(admin.getUniqueId(), target.getUniqueId());
        addViewer(admin, target);

        for (int i = 45; i < 54; i++) {
            inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " "));
        }
        if (paletteEditable) {
            inv.setItem(48, paletteToggleItem(admin, paletteEditMode.contains(admin.getUniqueId())));
        }
        inv.setItem(49, named(Material.BARRIER, ChatColor.AQUA + Lang.tr(admin, "ui.back")));

        refillPaletteItems(inv);

        admin.openInventory(inv);
    }

    private ItemStack paletteToggleItem(Player admin, boolean editing) {
        Material toggleMat = editing ? Material.LIME_DYE : Material.RED_DYE;
        String title = editing ? ChatColor.GREEN + Lang.tr(admin, "ui.palette_edit_on")
                               : ChatColor.GRAY + Lang.tr(admin, "ui.palette_edit_off");
        List<String> lore = Arrays.asList(
                "§7" + Lang.tr(admin, "ui.palette_edit_tooltip1"),
                "§7" + Lang.tr(admin, "ui.palette_edit_tooltip2"),
                "§8" + Lang.tr(admin, "ui.palette_edit_hint1"),
                "§8" + Lang.tr(admin, "ui.palette_edit_hint2")
        );
        return named(toggleMat, title, lore);
    }

    private void refillPaletteItems(Inventory inv) {
        for (int i = 0; i < 45; i++) {
            ItemStack stack = i < paletteItems.size() ? new ItemStack(paletteItems.get(i)) : null;
            inv.setItem(i, stack);
        }
    }

    private static class OfflinePlayerData {
        private final ItemStack[] inventory;
        private final ItemStack[] ender;
        private String name;

        private OfflinePlayerData(String name, ItemStack[] inventory, ItemStack[] ender) {
            this.name = name;
            this.inventory = inventory != null ? inventory : new ItemStack[41];
            this.ender = ender != null ? ender : new ItemStack[27];
        }

        private OfflinePlayerData copy() {
            ItemStack[] inv = new ItemStack[inventory.length];
            for (int i = 0; i < inventory.length; i++) {
                inv[i] = inventory[i] != null ? inventory[i].clone() : null;
            }
            ItemStack[] ec = new ItemStack[ender.length];
            for (int i = 0; i < ender.length; i++) {
                ec[i] = ender[i] != null ? ender[i].clone() : null;
            }
            return new OfflinePlayerData(name, inv, ec);
        }
    }
}
