package dev.quickguard;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class QuickGuard extends JavaPlugin {

    private BlockWatch blocks;
    private MovementWatch movement;
    private CombatWatch combat;
    private ModCommands mod;
    private GameModeWatch gamemode;

    private static final List<String> CHECKS = List.of(
            "gamemode", "speed", "xray", "reach", "criticals", "autoclicker",
            "killaura", "fly", "jesus", "fast-break", "fast-bow");

    // Floodgate is looked up by name so QuickGuard works with or without it installed
    private Object floodgateApi;
    private java.lang.reflect.Method floodgateCheck;
    private long lastFloodgateLookup = 0L;
    private final Map<String, Long> alertCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        blocks = new BlockWatch(this);
        movement = new MovementWatch(this);
        combat = new CombatWatch(this);
        mod = new ModCommands(this);

        Bukkit.getPluginManager().registerEvents(blocks, this);
        Bukkit.getPluginManager().registerEvents(movement, this);
        Bukkit.getPluginManager().registerEvents(combat, this);
        gamemode = new GameModeWatch(this);
        Bukkit.getPluginManager().registerEvents(gamemode, this);
        Bukkit.getPluginManager().registerEvents(mod, this);

        for (String name : List.of("ban", "tempban", "unban", "kick", "mute", "unmute", "warn", "warnings", "freeze")) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(mod);
                c.setTabCompleter(mod);
            }
        }

        movement.start();
        combat.start();
        mod.start();
        gamemode.start();

        StringBuilder sb = new StringBuilder("QuickGuard is running. Checks: ");
        for (String c : CHECKS) sb.append(c).append('=').append(describe(c)).append("  ");
        getLogger().info(sb.toString().trim());
    }

    public BlockWatch blocks() {
        return blocks;
    }

    public MovementWatch movement() {
        return movement;
    }

    /** True while the owner has this player frozen (the cheat checks leave frozen players alone). */
    public boolean isFrozen(Player p) {
        return mod != null && mod.isFrozen(p.getUniqueId());
    }

    /**
     * Why this player is never checked, or null if they are checked.
     * Ops are exempt, and so is anyone with quickguard.bypass (a LuckPerms * wildcard counts as having it).
     */
    public String exemptReason(Player p) {
        if (p.isOp()) return "is an operator (op)";
        if (p.hasPermission("quickguard.bypass")) return "has the permission quickguard.bypass (a * wildcard counts)";
        return null;
    }

    /** Ops and anyone with quickguard.bypass are never checked. */
    public boolean isStaff(Player p) {
        return exemptReason(p) != null;
    }

    /** Bedrock player? Uses Floodgate when it's installed, plus the UUID pattern Floodgate gives unlinked players. */
    public boolean isBedrock(Player p) {
        UUID id = p.getUniqueId();
        if (id.getMostSignificantBits() == 0L) return true;

        long now = System.currentTimeMillis();
        if (floodgateCheck == null && now - lastFloodgateLookup > 60_000L) {
            lastFloodgateLookup = now;
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateApi = api.getMethod("getInstance").invoke(null);
                floodgateCheck = api.getMethod("isFloodgatePlayer", UUID.class);
            } catch (Throwable ignored) {
                // Floodgate isn't installed (or isn't ready yet)
            }
        }
        if (floodgateCheck == null || floodgateApi == null) return false;
        try {
            return Boolean.TRUE.equals(floodgateCheck.invoke(floodgateApi, id));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Movement and combat checks skip Bedrock players by default (they move and fight a little differently). */
    public boolean skipBedrock(Player p) {
        return getConfig().getBoolean("ignore-bedrock", true) && isBedrock(p);
    }

    /** "BAN", "KICK", "ALERT" or "off" for a check. */
    private String describe(String check) {
        if (!getConfig().getBoolean("checks." + check + ".enabled", true)) return "off";
        return getConfig().getString("checks." + check + ".action", "ALERT").toUpperCase(Locale.ROOT);
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ---------- punishing ----------

    /** Handles a flag from any check, using that check's configured action. */
    public void punish(Player p, String check, String detail) {
        if (!p.isOnline()) return;
        FileConfiguration cfg = getConfig();
        String base = "checks." + check + ".";
        String action = cfg.getString(base + "action", "ALERT").toUpperCase(Locale.ROOT);
        String reason = cfg.getString(base + "reason", check);

        if (action.equals("ALERT")) {
            // don't spam staff: one alert per player per check every 30 seconds
            String key = p.getUniqueId() + ":" + check;
            long now = System.currentTimeMillis();
            Long last = alertCooldowns.get(key);
            if (last != null && now - last < 30_000L) return;
            alertCooldowns.put(key, now);
        }

        Location l = p.getLocation();
        String where = l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
        log(p.getName() + " | " + check + " | " + action + " | " + detail + " | " + where);
        alert(p, check, detail, action);

        if (action.equals("BAN")) {
            ban(p, reason, autoBanExpiry(), "QuickGuard");
        } else if (action.equals("KICK")) {
            if (mod != null) mod.release(p.getUniqueId());
            p.kickPlayer(screenMessage("kick-message", reason));
        }
    }

    /** Tells staff + console + log file about something that isn't a punishment. */
    public void alert(Player p, String check, String detail, String action) {
        String fmt = getConfig().getString("alert-format",
                "&c&lGuard &8» &b{player} &7flagged &e{check} &8[&7{action}&8] &7{detail}");
        String msg = color(fmt
                .replace("{player}", p.getName())
                .replace("{check}", check)
                .replace("{detail}", detail)
                .replace("{action}", action));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("quickguard.alerts")) staff.sendMessage(msg);
        }
        getLogger().warning(ChatColor.stripColor(msg));
    }

    private Date autoBanExpiry() {
        int days = getConfig().getInt("ban-duration-days", 0);
        return days > 0 ? new Date(System.currentTimeMillis() + days * 86_400_000L) : null;
    }

    /** Bans a player (online or not) with the standard ban screen, and kicks them if they're online. */
    public void ban(OfflinePlayer target, String reason, Date expires, String source) {
        ban(target, reason, expires, source, true);
    }

    /** Same, but with the option to skip the kick (used when the player is already leaving). */
    @SuppressWarnings("deprecation")
    public void ban(OfflinePlayer target, String reason, Date expires, String source, boolean kick) {
        String msg = screenMessage("ban-message", reason);
        if (mod != null) mod.release(target.getUniqueId());
        try {
            BanList<PlayerProfile> bans = Bukkit.getBanList(BanList.Type.PROFILE);
            bans.addBan(target.getPlayerProfile(), msg, expires, source);
        } catch (Exception e) {
            getLogger().warning("Could not add ban for " + target.getName() + ": " + e.getMessage());
        }
        Player online = target.getPlayer();
        if (kick && online != null) online.kickPlayer(msg);
    }

    /** Builds a multi-line screen message (ban/kick) from a config list. */
    public String screenMessage(String path, String reason) {
        List<String> lines = getConfig().getStringList(path);
        String discord = getConfig().getString("discord", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i).replace("{reason}", reason).replace("{discord}", discord));
        }
        return color(sb.toString());
    }

    /** Appends a line to plugins/QuickGuard/flags.log (for checking appeals). */
    public void log(String line) {
        try {
            getDataFolder().mkdirs();
            Path file = getDataFolder().toPath().resolve("flags.log");
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            Files.writeString(file, "[" + stamp + "] " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // not worth crashing over
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("quickguard.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                blocks.reload();
                sender.sendMessage(ChatColor.GREEN + "QuickGuard config reloaded.");
            }
            case "status" -> {
                sender.sendMessage(color("&c&lQuickGuard &7v" + getDescription().getVersion() + " is running. Checks:"));
                for (String c : CHECKS) {
                    sender.sendMessage(color("&8 - &f" + c + "&8: &e" + describe(c)));
                }
                sender.sendMessage(color("&7Ban screen discord: &b" + getConfig().getString("discord", "")));
            }
            case "info" -> info(sender, args);
            default -> sender.sendMessage(ChatColor.GRAY + "Usage: /quickguard <reload|status|info <player>>");
        }
        return true;
    }

    /** Shows how QuickGuard sees a player, so it's obvious why a check did or didn't fire. */
    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "Usage: /quickguard info <player>");
            return;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            sender.sendMessage(ChatColor.RED + args[1] + " isn't online.");
            return;
        }
        String exempt = exemptReason(p);
        sender.sendMessage(color("&c&lQuickGuard &7info for &b" + p.getName() + " &8(" + p.getUniqueId() + ")"));
        sender.sendMessage(color("&8 - &fPlatform: &e" + (isBedrock(p) ? "Bedrock" : "Java")));
        sender.sendMessage(color("&8 - &fChecked by QuickGuard: " + (exempt == null ? "&ayes" : "&cno &7(" + exempt + ")")));
        sender.sendMessage(color("&8 - &fGamemode: &e" + p.getGameMode().name()));
        sender.sendMessage(color("&8 - &fPing: &e" + p.getPing() + "ms"));
        sender.sendMessage(color("&8 - &fFrozen: &e" + (isFrozen(p) ? "yes" : "no")));
        if (exempt != null) {
            sender.sendMessage(color("&7This player is skipped by every check. To test a check, use an account that isn't op "
                    + "and doesn't have quickguard.bypass (or a * wildcard)."));
        }
    }
}
