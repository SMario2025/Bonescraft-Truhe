package de.bonescraft.boneschest;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BonesChestProtectPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private File dataFile;
    private FileConfiguration data;

    private final Set<Material> protectedMaterials = EnumSet.noneOf(Material.class);

    // playerUUID -> session
    private final Map<UUID, ViewSession> viewSessions = new ConcurrentHashMap<>();

    private String msgNoPerm;
    private String msgNotYours;
    private String msgViewOnly;
    private String msgTrustedAdded;
    private String msgTrustedRemoved;
    private String msgInfo;

    private String permOpenAll;
    private String permTakeAll;
    private String permAdmin;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadFromConfig();

        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create data.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        Bukkit.getPluginManager().registerEvents(this, this);

        var cmd = getCommand("bcp");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        getLogger().info("BonesChestProtect enabled.");
    }

    @Override
    public void onDisable() {
        saveData();
        viewSessions.clear();
    }

    private void loadFromConfig() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        protectedMaterials.clear();
        for (String s : cfg.getStringList("protected-blocks")) {
            try {
                protectedMaterials.add(Material.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Unknown material in protected-blocks: " + s);
            }
        }

        msgNoPerm = color(cfg.getString("messages.no-permission", "&cNo permission"));
        msgNotYours = color(cfg.getString("messages.not-your-container", "&cNot yours"));
        msgViewOnly = color(cfg.getString("messages.view-only", "&eView only"));
        msgTrustedAdded = color(cfg.getString("messages.trusted-added", "&aTrusted"));
        msgTrustedRemoved = color(cfg.getString("messages.trusted-removed", "&aUntrusted"));
        msgInfo = color(cfg.getString("messages.info", "&7Owner: %owner% | Trusted: %trusted%"));

        permOpenAll = cfg.getString("perm-open-all", "boneschestprotect.openall");
        permTakeAll = cfg.getString("perm-take-all", "boneschestprotect.takeall");
        permAdmin = cfg.getString("perm-admin", "boneschestprotect.admin");
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private boolean isProtectedMaterial(Material mat) {
        return protectedMaterials.contains(mat);
    }

    private boolean isProtectedBlock(Block block) {
        if (block == null) return false;
        return isProtectedMaterial(block.getType()) && (block.getState() instanceof Container);
    }

    private String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private Location locFromKey(String key) {
        String[] p = key.split(":");
        if (p.length != 4) return null;
        var w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            int x = Integer.parseInt(p[1]);
            int y = Integer.parseInt(p[2]);
            int z = Integer.parseInt(p[3]);
            return new Location(w, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean hasOwner(String k) {
        return data.contains("containers." + k + ".owner");
    }

    private UUID getOwner(String k) {
        String s = data.getString("containers." + k + ".owner");
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Set<UUID> getTrusted(String k) {
        List<String> list = data.getStringList("containers." + k + ".trusted");
        Set<UUID> out = new LinkedHashSet<>();
        for (String s : list) {
            try {
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    private void setOwnerIfAbsent(String k, UUID owner) {
        if (!hasOwner(k)) {
            data.set("containers." + k + ".owner", owner.toString());
            data.set("containers." + k + ".trusted", new ArrayList<String>());
            saveData();
        }
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    private boolean isAdmin(Player p) {
        return p.isOp() || p.hasPermission(permAdmin);
    }

    private boolean canOpen(Player p, String k) {
        if (isAdmin(p)) return true;
        UUID owner = getOwner(k);
        if (owner != null && owner.equals(p.getUniqueId())) return true;
        if (getTrusted(k).contains(p.getUniqueId())) return true;
        return p.hasPermission(permOpenAll);
    }

    private boolean canTake(Player p, String k) {
        if (isAdmin(p)) return true;
        UUID owner = getOwner(k);
        if (owner != null && owner.equals(p.getUniqueId())) return true;
        if (getTrusted(k).contains(p.getUniqueId())) return true;
        return p.hasPermission(permTakeAll);
    }

    private String ownerName(UUID uuid) {
        if (uuid == null) return "none";
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String n = op.getName();
        return (n == null || n.isEmpty()) ? uuid.toString() : n;
    }

    private String trustedNames(String k) {
        Set<UUID> t = getTrusted(k);
        if (t.isEmpty()) return "none";
        List<String> names = new ArrayList<>();
        for (UUID u : t) names.add(ownerName(u));
        return String.join(", ", names);
    }

    private String getKeyFromInventory(Inventory inv) {
        if (inv == null) return null;
        InventoryHolder holder = inv.getHolder();
        if (holder instanceof Container c) {
            return key(c.getLocation());
        }
        if (inv instanceof DoubleChestInventory dci) {
            InventoryHolder dh = dci.getHolder();
            if (dh instanceof org.bukkit.block.DoubleChest dc) {
                Location l = dc.getLocation();
                if (l != null) return key(l);
            }
        }
        return null;
    }

    // --- Events ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!isProtectedBlock(b)) return;
        setOwnerIfAbsent(key(b.getLocation()), e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isProtectedBlock(b)) return;

        String k = key(b.getLocation());
        if (!hasOwner(k)) {
            // Not yet claimed -> claim to breaker and allow break
            setOwnerIfAbsent(k, e.getPlayer().getUniqueId());
            return;
        }

        Player p = e.getPlayer();
        if (isAdmin(p)) return;
        UUID owner = getOwner(k);
        if (owner != null && owner.equals(p.getUniqueId())) return;
        if (getTrusted(k).contains(p.getUniqueId())) return;

        e.setCancelled(true);
        p.sendMessage(msgNotYours);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (!isProtectedBlock(b)) return;

        Player p = e.getPlayer();
        String k = key(b.getLocation());

        // if it's a new (old) container without owner -> claim to placer on first interact
        if (!hasOwner(k)) {
            setOwnerIfAbsent(k, p.getUniqueId());
        }

        if (!canOpen(p, k)) {
            // default behaviour: allow open (view-only) unless you want to block open entirely.
            // Here: still allow open but view-only.
            viewSessions.put(p.getUniqueId(), new ViewSession(k, true));
            p.sendMessage(msgViewOnly);
            return;
        }

        boolean viewOnly = !canTake(p, k);
        if (viewOnly) {
            viewSessions.put(p.getUniqueId(), new ViewSession(k, true));
            p.sendMessage(msgViewOnly);
        } else {
            viewSessions.put(p.getUniqueId(), new ViewSession(k, false));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        // If they opened a different inventory, clear session.
        String invKey = getKeyFromInventory(e.getInventory());
        ViewSession s = viewSessions.get(p.getUniqueId());
        if (s == null) return;
        if (invKey == null || !invKey.equals(s.containerKey)) {
            // not our protected container
            viewSessions.remove(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ViewSession s = viewSessions.get(p.getUniqueId());
        if (s == null || !s.viewOnly) return;

        InventoryView view = e.getView();
        String topKey = getKeyFromInventory(view.getTopInventory());
        if (topKey == null || !topKey.equals(s.containerKey)) return;

        // Block ALL modifications while still allowing browsing.
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ViewSession s = viewSessions.get(p.getUniqueId());
        if (s == null || !s.viewOnly) return;

        String topKey = getKeyFromInventory(e.getView().getTopInventory());
        if (topKey == null || !topKey.equals(s.containerKey)) return;

        e.setCancelled(true);
    }

    // --- Commands ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /bcp <info|trust|untrust|claim|reload>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!isAdmin(p)) {
                p.sendMessage(msgNoPerm);
                return true;
            }
            loadFromConfig();
            p.sendMessage(ChatColor.GREEN + "BonesChestProtect reloaded.");
            return true;
        }

        Block target = p.getTargetBlockExact(6);
        if (target == null || !isProtectedBlock(target)) {
            p.sendMessage(ChatColor.RED + "Schau auf eine Truhe/Barrel (max 6 Blöcke)." );
            return true;
        }

        String k = key(target.getLocation());
        if (!hasOwner(k)) setOwnerIfAbsent(k, p.getUniqueId());

        if (sub.equals("info")) {
            UUID owner = getOwner(k);
            String ownerStr = ownerName(owner);
            String trustedStr = trustedNames(k);
            p.sendMessage(msgInfo.replace("%owner%", ownerStr).replace("%trusted%", trustedStr));
            return true;
        }

        if (sub.equals("claim")) {
            // claim only if unowned or admin
            if (hasOwner(k) && !isAdmin(p)) {
                UUID owner = getOwner(k);
                if (owner != null && !owner.equals(p.getUniqueId())) {
                    p.sendMessage(msgNotYours);
                    return true;
                }
            }
            data.set("containers." + k + ".owner", p.getUniqueId().toString());
            if (!data.contains("containers." + k + ".trusted")) {
                data.set("containers." + k + ".trusted", new ArrayList<String>());
            }
            saveData();
            p.sendMessage(ChatColor.GREEN + "Geclaimed.");
            return true;
        }

        // trust/untrust requires being owner/trusted/admin
        if (!isAdmin(p)) {
            UUID owner = getOwner(k);
            if (owner == null || (!owner.equals(p.getUniqueId()) && !getTrusted(k).contains(p.getUniqueId()))) {
                p.sendMessage(msgNotYours);
                return true;
            }
        }

        if (sub.equals("trust") || sub.equals("untrust")) {
            if (args.length < 2) {
                p.sendMessage(ChatColor.YELLOW + "Usage: /bcp " + sub + " <Spielername>");
                return true;
            }

            Player targetPlayer = Bukkit.getPlayerExact(args[1]);
            if (targetPlayer == null) {
                p.sendMessage(ChatColor.RED + "Spieler muss online sein: " + args[1]);
                return true;
            }

            Set<UUID> t = getTrusted(k);
            if (sub.equals("trust")) {
                t.add(targetPlayer.getUniqueId());
                p.sendMessage(msgTrustedAdded);
            } else {
                t.remove(targetPlayer.getUniqueId());
                p.sendMessage(msgTrustedRemoved);
            }
            List<String> out = new ArrayList<>();
            for (UUID u : t) out.add(u.toString());
            data.set("containers." + k + ".trusted", out);
            saveData();
            return true;
        }

        p.sendMessage(ChatColor.YELLOW + "Unknown subcommand.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("info", "trust", "untrust", "claim", "reload");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        return Collections.emptyList();
    }

    private static class ViewSession {
        final String containerKey;
        final boolean viewOnly;

        ViewSession(String containerKey, boolean viewOnly) {
            this.containerKey = containerKey;
            this.viewOnly = viewOnly;
        }
    }
}
