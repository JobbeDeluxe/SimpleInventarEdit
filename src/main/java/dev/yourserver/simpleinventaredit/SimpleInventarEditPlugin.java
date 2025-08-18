package dev.yourserver.simpleinventaredit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Level;

public class SimpleInventarEditPlugin extends JavaPlugin implements Listener {

    private static final int GUI_SIZE = 54; // 6x9

    // ==== Config-Flags ====
    private boolean enablePalette = true;
    private boolean backOnClose   = true;
    private List<Material> paletteItems = null; // aus config oder Default

    // Ziel -> Admin-Viewer (zum Schließen bei Logout)
    private final Map<UUID, Set<UUID>> viewersByTarget = new HashMap<>();
    // Admin -> aktuell betrachtetes Ziel (Armor/Palette)
    private final Map<UUID, UUID> armorGuiTargetByViewer = new HashMap<>();
    private final Map<UUID, UUID> paletteTargetByViewer  = new HashMap<>();
    // Admin -> letzte Spielerliste-Seite (für "Zurück")
    private final Map<UUID, Integer> lastListPageByViewer = new HashMap<>();
    // Admin, der gerade natives Ziel-Inventar/Endertruhe geöffnet hat (für "backOnClose")
    private final Set<UUID> viewingTargetInventory = new HashSet<>();
    private final Set<UUID> viewingTargetEnder     = new HashSet<>();

    /* ====== Titel-Helfer (sprachabhängig) ====== */
    private String titlePlayers(Player viewer) {
        return Lang.tr(viewer, "gui.title.players");
    }
    private String titleArmorPrefix(Player viewer) {
        return Lang.tr(viewer, "gui.title.armor_prefix"); // mit Leerzeichen/Separator am Ende
    }
    private String titlePalettePrefix(Player viewer) {
        return Lang.tr(viewer, "gui.title.palette_prefix"); // mit Leerzeichen/Separator am Ende
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Lang.init(this);      // <--- Sprache initialisieren
        loadSieConfig();

        if (getCommand("sie") != null) {
            getCommand("sie").setExecutor((sender, cmd, label, args) -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Lang.tr(null, "messages.cmd_ingame_only"));
                    return true;
                }
                if (!p.hasPermission("sie.use")) {
                    p.sendMessage(Lang.tr(p, "messages.no_permission"));
                    return true;
                }
                openPlayerList(p, 0);
                return true;
            });
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("SimpleInventarEdit ready. Use /sie");
    }

    private void loadSieConfig() {
        var cfg = getConfig();
        this.enablePalette = cfg.getBoolean("palette.enabled", true);
        this.backOnClose   = cfg.getBoolean("navigation.backOnClose", true);

        List<String> names = cfg.getStringList("palette.items");
        if (names != null && !names.isEmpty()) {
            List<Material> parsed = new ArrayList<>();
            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                try {
                    Material m = Material.valueOf(n.trim().toUpperCase(Locale.ROOT));
                    if (m.isItem()) parsed.add(m);
                    else getLogger().log(Level.WARNING, "palette.items: {0} ist kein Item, übersprungen.", n);
                } catch (IllegalArgumentException ex) {
                    getLogger().log(Level.WARNING, "palette.items: Unbekanntes Material: {0}", n);
                }
            }
            if (!parsed.isEmpty()) {
                this.paletteItems = parsed;
            }
        }
        if (this.paletteItems == null) {
            this.paletteItems = defaultPaletteItems();
        }
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

    /* ====== Utils ====== */

    private ItemStack named(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    @SuppressWarnings("deprecation")
    private ItemStack headButton(Player viewer, String name, UUID uuid, String... lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + name);
            if (lore != null && lore.length > 0) meta.setLore(Arrays.asList(lore));
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack safeClone(ItemStack stack, ItemStack fallbackIfAir) {
        if (stack == null || stack.getType() == Material.AIR) return fallbackIfAir;
        return stack.clone();
    }

    private void addViewer(Player admin, Player target) {
        viewersByTarget.computeIfAbsent(target.getUniqueId(), k -> new HashSet<>()).add(admin.getUniqueId());
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
            lore.add(Lang.tr(admin, "gui.lore.open_inventory"));
            lore.add(Lang.tr(admin, "gui.lore.armor_offhand_readonly"));
            lore.add(Lang.tr(admin, "gui.lore.ender_chest"));
            if (enablePalette) {
                lore.add(Lang.tr(admin, "gui.lore.palette_hint_stack"));
                lore.add(Lang.tr(admin, "gui.lore.palette_mmb_note"));
            }
            inv.setItem(i - start, headButton(admin, target.getName(), target.getUniqueId(),
                    lore.toArray(new String[0])));
        }

        if (page > 0) inv.setItem(45, named(Material.ARROW, Lang.tr(admin, "gui.button.back")));
        inv.setItem(49, named(Material.BARRIER, Lang.tr(admin, "gui.button.close")));
        if (end < online.size()) inv.setItem(53, named(Material.ARROW, Lang.tr(admin, "gui.button.next")));

        admin.openInventory(inv);
        admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    /* ====== Events ====== */

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        HumanEntity who = e.getWhoClicked();
        if (!(who instanceof Player admin)) return;

        String title = e.getView().getTitle();

        // Spielerliste
        if (title.equals(titlePlayers(admin))) {
            e.setCancelled(true);
            int raw = e.getRawSlot();
            ItemStack cur = e.getCurrentItem();
            if (cur == null || cur.getType() == Material.AIR) return;

            if (raw >= 0 && raw < 45) {
                String name = (cur.hasItemMeta() && cur.getItemMeta().hasDisplayName())
                        ? ChatColor.stripColor(cur.getItemMeta().getDisplayName())
                        : null;
                if (name == null || name.isBlank()) return;

                Player target = Bukkit.getPlayerExact(name);
                if (target == null || !target.isOnline()) {
                    admin.sendMessage(Lang.tr(admin, "messages.target_offline"));
                    admin.closeInventory();
                    return;
                }

                if (e.getClick().isShiftClick()) {
                    openTargetEnderChest(admin, target);
                } else if (e.getClick().isRightClick()) {
                    openArmorGui(admin, target);
                } else if (enablePalette &&
                        (e.getClick() == ClickType.MIDDLE
                                || e.getClick() == ClickType.DROP
                                || e.getClick() == ClickType.CONTROL_DROP)) {
                    openPaletteGui(admin, target, 0);
                } else {
                    openTargetInventory(admin, target);
                }
                return;
            }

            if (raw == 45) { // zurück Seite 0
                openPlayerList(admin, 0);
                return;
            } else if (raw == 49) { // schließen
                admin.closeInventory();
                return;
            } else if (raw == 53) { // weiter Seite 1
                openPlayerList(admin, 1);
                return;
            }
        }

        // Armor-GUI (nur ansehen, aber mit "Zurück")
        if (title.startsWith(titleArmorPrefix(admin))) {
            e.setCancelled(true); // keine Edits in Armor-Ansicht

            if (e.getRawSlot() == 8) { // Barrier = Zurück
                int page = lastListPageByViewer.getOrDefault(admin.getUniqueId(), 0);
                openPlayerList(admin, page);
                return;
            }
            return;
        }

        // Palette
        if (title.startsWith(titlePalettePrefix(admin))) {
            e.setCancelled(true);
            UUID targetId = paletteTargetByViewer.get(admin.getUniqueId());
            if (targetId == null) return;
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                admin.sendMessage(Lang.tr(admin, "messages.palette_target_offline"));
                admin.closeInventory();
                return;
            }

            int raw = e.getRawSlot();
            if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) return;
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            // Barrier = Zurück
            if (raw == 49 && clicked.getType() == Material.BARRIER) {
                int page = lastListPageByViewer.getOrDefault(admin.getUniqueId(), 0);
                openPlayerList(admin, page);
                return;
            }

            // Nur echte Items geben (Pane/BARRIER ignorieren)
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

                Map<String,String> ph = Lang.ph("amount", String.valueOf(give.getAmount()));
                ph.put("item", clicked.getType().name());
                ph.put("player", target.getName());
                admin.sendMessage(Lang.tr(admin, "messages.give_confirm", ph));
            }
            return;
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player admin)) return;
        String title = e.getView().getTitle();

        // Armor/Palette schließen -> ggf. zurück zur Spielerliste
        if (title.startsWith(titleArmorPrefix(admin))) {
            armorGuiTargetByViewer.remove(admin.getUniqueId());
            if (backOnClose) {
                int page = lastListPageByViewer.getOrDefault(admin.getUniqueId(), 0);
                Bukkit.getScheduler().runTask(this, () -> openPlayerList(admin, page));
            }
            return;
        }
        if (title.startsWith(titlePalettePrefix(admin))) {
            paletteTargetByViewer.remove(admin.getUniqueId());
            if (backOnClose) {
                int page = lastListPageByViewer.getOrDefault(admin.getUniqueId(), 0);
                Bukkit.getScheduler().runTask(this, () -> openPlayerList(admin, page));
            }
            return;
        }

        // Native Ziel-Inventare (Inventar/Endertruhe) geschlossen -> optional zurück
        UUID uid = admin.getUniqueId();
        boolean wasTargetInv = viewingTargetInventory.remove(uid);
        boolean wasTargetEnd = viewingTargetEnder.remove(uid);
        if (backOnClose && (wasTargetInv || wasTargetEnd)) {
            int page = lastListPageByViewer.getOrDefault(uid, 0);
            Bukkit.getScheduler().runTask(this, () -> openPlayerList(admin, page));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID targetId = e.getPlayer().getUniqueId();
        Set<UUID> viewers = viewersByTarget.remove(targetId);
        if (viewers == null) return;
        for (UUID v : viewers) {
            Player admin = Bukkit.getPlayer(v);
            if (admin != null && admin.isOnline()) {
                admin.closeInventory();
                admin.sendMessage(ChatColor.GRAY + "[SIE] " + Lang.tr(admin, "messages.target_left_closed"));
            }
        }
    }

    /* ====== Öffnen ====== */

    private void openTargetInventory(Player admin, Player target) {
        admin.openInventory(target.getInventory());

        admin.sendMessage(Lang.tr(admin, "messages.opened_inventory_of",
                Lang.ph("player", target.getName())));
        addViewer(admin, target);
        Bukkit.getScheduler().runTask(this, () -> viewingTargetInventory.add(admin.getUniqueId()));
    }

    private void openTargetEnderChest(Player admin, Player target) {
        admin.openInventory(target.getEnderChest());
        admin.sendMessage(Lang.tr(admin, "messages.opened_ender_of",
                Lang.ph("player", target.getName())));
        addViewer(admin, target);
        Bukkit.getScheduler().runTask(this, () -> viewingTargetEnder.add(admin.getUniqueId()));
    }

    /* ====== Armor GUI (nur ansehen, mit Zurück) ====== */

    private void openArmorGui(Player admin, Player target) {
        Inventory gui = Bukkit.createInventory(admin, 9, titleArmorPrefix(admin) + target.getName());
        armorGuiTargetByViewer.put(admin.getUniqueId(), target.getUniqueId());
        addViewer(admin, target);
        fillArmorGui(admin, gui, target);
        admin.openInventory(gui);
        admin.sendMessage(Lang.tr(admin, "messages.armor_view_open_of",
                Lang.ph("player", target.getName())));
    }

    private void fillArmorGui(Player viewer, Inventory inv, Player target) {
        inv.clear();
        PlayerInventory pi = target.getInventory();

        inv.setItem(0, safeClone(pi.getHelmet(),        named(Material.LEATHER_HELMET,     Lang.tr(viewer, "gui.armor.empty.helmet"))));
        inv.setItem(1, safeClone(pi.getChestplate(),    named(Material.LEATHER_CHESTPLATE, Lang.tr(viewer, "gui.armor.empty.chest"))));
        inv.setItem(2, safeClone(pi.getLeggings(),      named(Material.LEATHER_LEGGINGS,   Lang.tr(viewer, "gui.armor.empty.legs"))));
        inv.setItem(3, safeClone(pi.getBoots(),         named(Material.LEATHER_BOOTS,      Lang.tr(viewer, "gui.armor.empty.boots"))));
        inv.setItem(4, safeClone(pi.getItemInOffHand(), named(Material.SHIELD,             Lang.tr(viewer, "gui.armor.empty.offhand"))));

        for (int i = 5; i <= 7; i++) {
            inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " "));
        }
        inv.setItem(8, named(Material.BARRIER, Lang.tr(viewer, "gui.button.back")));
    }

    /* ====== Palette ====== */

    private void openPaletteGui(Player admin, Player target, int page) {
        if (!enablePalette) return;
        Inventory inv = Bukkit.createInventory(admin, GUI_SIZE, titlePalettePrefix(admin) + target.getName());
        paletteTargetByViewer.put(admin.getUniqueId(), target.getUniqueId());
        addViewer(admin, target);

        for (int i = 45; i < 54; i++) {
            inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " "));
        }
        inv.setItem(49, named(Material.BARRIER, Lang.tr(admin, "gui.button.back")));

        // bis zu 45 Items auf Seite 0
        for (int i = 0; i < Math.min(45, paletteItems.size()); i++) {
            inv.setItem(i, new ItemStack(paletteItems.get(i)));
        }

        Map<String,String> ph = Lang.ph("player", target.getName());
        admin.sendMessage(Lang.tr(admin, "messages.palette_for", ph) + " " + Lang.tr(admin, "messages.palette_hint"));
        admin.openInventory(inv);
    }
}
