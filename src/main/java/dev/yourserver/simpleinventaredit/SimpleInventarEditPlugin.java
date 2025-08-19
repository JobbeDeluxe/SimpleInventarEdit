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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.*;
import java.util.logging.Level;

public class SimpleInventarEditPlugin extends JavaPlugin implements Listener {

    private static final int GUI_SIZE = 54; // 6x9

    // Config
    private boolean enablePalette = true;
    private boolean backOnClose   = true;
    private List<Material> paletteItems = null;

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
    private String titlePalette(Player p, String targetName) {
        Map<String,String> ph = new HashMap<>();
        ph.put("player", targetName);
        return Lang.tr(p, "ui.palette_title", ph);
    }

    // Ziel -> Admin-Viewer (zum Schließen bei Logout)
    private final Map<UUID, Set<UUID>> viewersByTarget = new HashMap<>();
    // Admin -> aktuell betrachtetes Ziel (Armor/Palette)
    private final Map<UUID, UUID> armorGuiTargetByViewer = new HashMap<>();
    private final Map<UUID, UUID> paletteTargetByViewer  = new HashMap<>();
    // Admin -> letzte Spielerliste-Seite (für "Zurück")
    private final Map<UUID, Integer> lastListPageByViewer = new HashMap<>();
    // Admin, der natives Ziel-Inventar/Endertruhe geöffnet hat (für "backOnClose")
    private final Set<UUID> viewingTargetInventory = new HashSet<>();
    private final Set<UUID> viewingTargetEnder     = new HashSet<>();
    // Admins im Löschmodus
    private final Set<UUID> deleteMode = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSieConfig();

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
        getLogger().info("SimpleInventarEdit ready.");
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

    private void addViewer(Player admin, Player target) {
        viewersByTarget.computeIfAbsent(target.getUniqueId(), k -> new HashSet<>()).add(admin.getUniqueId());
    }

    /* ====== Hilfe: Echtes Buch – Page 1 gesplittet (1/2 & 2/2), Back-Link auf jeder Seite ====== */

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

			// Seite 1/2: Navigation – bis VOR Q, dann direkt der Zurück-Link
			BaseComponent[] page1 = new ComponentBuilder()
					.append(Lang.tr(p,"ui.players_title") + " (1/2)").bold(true).append("\n\n").bold(false)
					.append("• ").append(Lang.tr(p,"help.click_left_inv")).append("\n\n")
					.append("• ").append(Lang.tr(p,"help.click_right_armor")).append("\n\n")
					.append("• ").append(Lang.tr(p,"help.shift_click_ender"))
					.append(backLink.get()) // <-- Hier statt Q
					.create();

			// Seite 2/2: Rest der Navigation – Q/Palette + Middle-Click
			ComponentBuilder page2Builder = new ComponentBuilder()
					.append(Lang.tr(p,"ui.players_title") + " (2/2)").bold(true).append("\n\n").bold(false);
			if (enablePalette) {
				page2Builder.append("• ").append(Lang.tr(p,"help.q_palette")).append("\n\n");
			}
			BaseComponent[] page2 = page2Builder
					.append(Lang.tr(p,"help.middle_creative"))
					.append(backLink.get())
					.create();

			// Seite 3: Armor & Offhand
			BaseComponent[] page3 = new ComponentBuilder()
					.append(Lang.tr(p,"help.armor_title")).bold(true).append("\n\n").bold(false)
					.append("• ").append(Lang.tr(p,"help.armor_put_take")).append("\n\n")
					.append("• ").append(Lang.tr(p,"help.armor_offhand")).append("\n\n")
					.append("• ").append(Lang.tr(p,"help.armor_types"))
					.append(backLink.get())
					.create();

			// Seite 4: Delete-Mode
			BaseComponent[] page4 = new ComponentBuilder()
					.append(Lang.tr(p,"help.delete_mode_title")).bold(true).append("\n\n").bold(false)
					.append(Lang.tr(p,"help.delete_mode_long1")).append("\n\n")
					.append(Lang.tr(p,"help.delete_mode_long2")).append("\n\n")
					.append(Lang.tr(p,"help.delete_mode_long3"))
					.append(backLink.get())
					.create();

			meta.spigot().addPage(page1, page2, page3, page4);
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

        if (end < online.size()) inv.setItem(53, named(Material.ARROW, ChatColor.AQUA + Lang.tr(admin, "ui.next")));

        admin.openInventory(inv);
        admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    /* ====== Events ====== */

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        HumanEntity who = e.getWhoClicked();
        if (!(who instanceof Player admin)) return;

        String title = e.getView().getTitle();
        UUID adminId = admin.getUniqueId();

        // ---- Spielerliste ----
        if (title.equals(titlePlayers(admin))) {
            // Unten (Admin-Inventar) frei
            if (e.getClickedInventory() == e.getView().getBottomInventory()) return;

            int raw = e.getRawSlot();
            ItemStack cur = e.getCurrentItem();
            if (cur == null || cur.getType() == Material.AIR) return;

            // Steuerleiste (Back/Close/Toggle/Help/Next)
            if (raw == 45) { e.setCancelled(true); openPlayerList(admin, 0); return; }
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

            e.setCancelled(true);
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
            if (clicked == null || clicked.getType() == Material.AIR) return;

            // Barrier = Zurück
            if (raw == 49 && clicked.getType() == Material.BARRIER) {
                int page = lastListPageByViewer.getOrDefault(adminId, 0);
                openPlayerList(admin, page);
                return;
            }

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
        boolean inDelete = deleteMode.contains(adminId);
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
        boolean closedArmor = closedTitle.startsWith(ChatColor.stripColor(Lang.tr(admin, "ui.armor_title_prefix")));
        boolean closedPalette = closedTitle.startsWith(Lang.tr(admin, "ui.palette_title_prefix"));

        if (closedArmor) armorGuiTargetByViewer.remove(uid);
        if (closedPalette) paletteTargetByViewer.remove(uid);

        if (backOnClose && (closedArmor || closedPalette)) {
            int page = lastListPageByViewer.getOrDefault(uid, 0);
            Bukkit.getScheduler().runTask(this, () -> openPlayerList(admin, page));
            return;
        }

        // native Ziel-Inventare / Enderchests
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
    }

    /* ====== Öffnen ====== */

    private void openTargetInventory(Player admin, Player target) {
        admin.openInventory(target.getInventory());
        addViewer(admin, target);
        Bukkit.getScheduler().runTask(this, () -> viewingTargetInventory.add(admin.getUniqueId()));
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

        inv.setItem(0, safeClone(pi.getHelmet(),        named(Material.LEATHER_HELMET,     ChatColor.GRAY + Lang.tr(target, "armor.empty.helmet"))));
        inv.setItem(1, safeClone(pi.getChestplate(),    named(Material.LEATHER_CHESTPLATE, ChatColor.GRAY + Lang.tr(target, "armor.empty.chest"))));
        inv.setItem(2, safeClone(pi.getLeggings(),      named(Material.LEATHER_LEGGINGS,   ChatColor.GRAY + Lang.tr(target, "armor.empty.legs"))));
        inv.setItem(3, safeClone(pi.getBoots(),         named(Material.LEATHER_BOOTS,      ChatColor.GRAY + Lang.tr(target, "armor.empty.boots"))));
        inv.setItem(4, safeClone(pi.getItemInOffHand(), named(Material.SHIELD,             ChatColor.GRAY + Lang.tr(target, "armor.empty.offhand"))));

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
        inv.setItem(49, named(Material.BARRIER, ChatColor.AQUA + Lang.tr(admin, "ui.back")));

        for (int i = 0; i < Math.min(45, paletteItems.size()); i++) {
            inv.setItem(i, new ItemStack(paletteItems.get(i)));
        }

        admin.openInventory(inv);
    }
}
