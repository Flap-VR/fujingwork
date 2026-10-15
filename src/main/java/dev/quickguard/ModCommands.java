package dev.quickguard;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.profile.PlayerProfile;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Basic moderation: /ban /tempban /unban /kick /mute /unmute /warn /warnings /freeze.
 * Every ban made here uses the same ban screen as the automatic bans (with the appeal text).
 */
public final class ModCommands implements CommandExecutor, TabCompleter, Listener {

    private record Mute(long until, String reason, String by) {}

    private final QuickGuard plugin;
    private final File file;
    private YamlConfiguration data;
    private final Map<UUID, Mute> mutes = new ConcurrentHashMap<>();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    public ModCommands(QuickGuard plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "moderation.yml");
        load();
    }

    // ---------- saved data (mutes + warnings) ----------

    private void load() {
        data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = data.getConfigurationSection("mutes");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                mutes.put(id, new Mute(sec.getLong(key + ".until"), sec.getString(key + ".reason", ""),
                        sec.getString(key + ".by", "")));
            } catch (IllegalArgumentException ignored) {
                // bad key, skip it
            }
        }
    }

    private void save() {
        try {
            plugin.getDataFolder().mkdirs();
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save moderation.yml: " + e.getMessage());
        }
    }

    // ---------- helpers ----------

    private static void say(CommandSender to, String msg) {
        to.sendMessage(QuickGuard.color("&8[&cMod&8] &7" + msg));
    }

    private static String join(String[] a, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(a, from, a.length));
    }

    private String defaultReason() {
        return plugin.getConfig().getString("moderation.default-reason", "Breaking the rules");
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.hasPlayedBefore() ? off : null;
    }

    private static String nameOf(OfflinePlayer p, String fallback) {
        return p.getName() != null ? p.getName() : fallback;
    }

    /** Staff can't be punished by other staff; only the console can do that. */
    private boolean protectedFrom(CommandSender sender, OfflinePlayer target) {
        if (sender instanceof ConsoleCommandSender) return false;
        if (target.isOp()) return true;
        Player online = target.getPlayer();
        return online != null && (online.hasPermission("quickguard.mod") || online.hasPermission("quickguard.bypass"));
    }

    // ---------- commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "ban" -> ban(sender, args, false);
            case "tempban" -> ban(sender, args, true);
            case "unban" -> unban(sender, args);
            case "kick" -> kick(sender, args);
            case "mute" -> mute(sender, args);
            case "unmute" -> unmute(sender, args);
            case "warn" -> warn(sender, args);
            case "warnings" -> warnings(sender, args);
            case "freeze" -> freeze(sender, args);
            default -> { }
        }
        return true;
    }

    private void ban(CommandSender s, String[] a, boolean temp) {
        if (a.length < (temp ? 2 : 1)) {
            say(s, "Usage: &e" + (temp ? "/tempban <player> <time> [reason]" : "/ban <player> [reason]"));
            return;
        }
        OfflinePlayer t = find(a[0]);
        if (t == null) {
            say(s, "&cThat player has never joined this server.");
            return;
        }
        if (protectedFrom(s, t)) {
            say(s, "&cYou can't do that to staff. Use the console.");
            return;
        }
        Date expires = null;
        int reasonFrom = 1;
        if (temp) {
            long ms = Rules.parseDuration(a[1]);
            if (ms <= 0) {
                say(s, "&cTime should look like &e30m&c, &e12h&c, &e7d&c or &e1d12h&c.");
                return;
            }
            expires = new Date(System.currentTimeMillis() + ms);
            reasonFrom = 2;
        }
        String reason = reasonFrom < a.length ? join(a, reasonFrom) : defaultReason();
        plugin.ban(t, reason, expires, s.getName());
        plugin.log(s.getName() + " | " + (temp ? "tempban " + a[1] : "ban") + " | " + nameOf(t, a[0]) + " | " + reason);
        say(s, "&aBanned &b" + nameOf(t, a[0]) + (temp ? " &afor &e" + a[1] : "") + "&a: &f" + reason);
    }

    @SuppressWarnings("deprecation")
    private void unban(CommandSender s, String[] a) {
        if (a.length < 1) {
            say(s, "Usage: &e/unban <player>");
            return;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayer(a[0]);
        BanList<PlayerProfile> bans = Bukkit.getBanList(BanList.Type.PROFILE);
        PlayerProfile profile = t.getPlayerProfile();
        if (!bans.isBanned(profile)) {
            say(s, "&c" + a[0] + " isn't banned.");
            return;
        }
        bans.pardon(profile);
        plugin.log(s.getName() + " | unban | " + nameOf(t, a[0]));
        say(s, "&aUnbanned &b" + nameOf(t, a[0]) + "&a.");
    }

    private void kick(CommandSender s, String[] a) {
        if (a.length < 1) {
            say(s, "Usage: &e/kick <player> [reason]");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            say(s, "&c" + a[0] + " isn't online.");
            return;
        }
        if (protectedFrom(s, t)) {
            say(s, "&cYou can't do that to staff. Use the console.");
            return;
        }
        String reason = a.length > 1 ? join(a, 1) : defaultReason();
        frozen.remove(t.getUniqueId());
        t.kickPlayer(plugin.screenMessage("kick-message", reason));
        plugin.log(s.getName() + " | kick | " + t.getName() + " | " + reason);
        say(s, "&aKicked &b" + t.getName() + "&a: &f" + reason);
    }

    private void mute(CommandSender s, String[] a) {
        if (a.length < 1) {
            say(s, "Usage: &e/mute <player> [time] [reason]");
            return;
        }
        OfflinePlayer t = find(a[0]);
        if (t == null) {
            say(s, "&cThat player has never joined this server.");
            return;
        }
        if (protectedFrom(s, t)) {
            say(s, "&cYou can't do that to staff. Use the console.");
            return;
        }
        long until = 0L;
        int reasonFrom = 1;
        if (a.length > 1) {
            long ms = Rules.parseDuration(a[1]);
            if (ms > 0) {
                until = System.currentTimeMillis() + ms;
                reasonFrom = 2;
            }
        }
        String reason = reasonFrom < a.length ? join(a, reasonFrom) : defaultReason();
        Mute m = new Mute(until, reason, s.getName());
        UUID id = t.getUniqueId();
        mutes.put(id, m);
        data.set("mutes." + id + ".until", until);
        data.set("mutes." + id + ".reason", reason);
        data.set("mutes." + id + ".by", s.getName());
        save();

        Player online = t.getPlayer();
        if (online != null) online.sendMessage(mutedMessage(m));
        plugin.log(s.getName() + " | mute | " + nameOf(t, a[0]) + " | " + reason);
        say(s, "&aMuted &b" + nameOf(t, a[0]) + (until > 0 ? " &afor &e" + Rules.remaining(until - System.currentTimeMillis()) : "")
                + "&a: &f" + reason);
    }

    private void unmute(CommandSender s, String[] a) {
        if (a.length < 1) {
            say(s, "Usage: &e/unmute <player>");
            return;
        }
        OfflinePlayer t = find(a[0]);
        if (t == null || mutes.remove(t.getUniqueId()) == null) {
            say(s, "&c" + a[0] + " isn't muted.");
            return;
        }
        data.set("mutes." + t.getUniqueId(), null);
        save();
        Player online = t.getPlayer();
        if (online != null) online.sendMessage(QuickGuard.color("&aYou have been unmuted."));
        plugin.log(s.getName() + " | unmute | " + nameOf(t, a[0]));
        say(s, "&aUnmuted &b" + nameOf(t, a[0]) + "&a.");
    }

    private void warn(CommandSender s, String[] a) {
        if (a.length < 1) {
            say(s, "Usage: &e/warn <player> [reason]");
            return;
        }
        OfflinePlayer t = find(a[0]);
        if (t == null) {
            say(s, "&cThat player has never joined this server.");
            return;
        }
        String reason = a.length > 1 ? join(a, 1) : defaultReason();
        String path = "warnings." + t.getUniqueId();
        List<String> list = new ArrayList<>(data.getStringList(path));
        list.add(System.currentTimeMillis() + "|" + s.getName() + "|" + reason);
        data.set(path, list);
        save();

        Player online = t.getPlayer();
        if (online != null) {
            online.sendMessage(QuickGuard.color("&c&lWARNING &8» &7A staff member warned you: &f" + reason));
            online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.7f);
        }
        plugin.log(s.getName() + " | warn | " + nameOf(t, a[0]) + " | " + reason);
        say(s, "&aWarned &b" + nameOf(t, a[0]) + "&a (&e" + list.size() + "&a total): &f" + reason);
    }

    private void warnings(CommandSender s, String[] a) {
        if (a.length < 1) {
            say(s, "Usage: &e/warnings <player>");
            return;
        }
        OfflinePlayer t = find(a[0]);
        if (t == null) {
            say(s, "&cThat player has never joined this server.");
            return;
        }
        List<String> list = data.getStringList("warnings." + t.getUniqueId());
        if (list.isEmpty()) {
            say(s, "&b" + nameOf(t, a[0]) + " &7has no warnings.");
            return;
        }
        say(s, "&b" + nameOf(t, a[0]) + " &7has &e" + list.size() + " &7warning(s):");
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
        for (String entry : list) {
            String[] parts = entry.split("\\|", 3);
            if (parts.length < 3) continue;
            String when;
            try {
                when = fmt.format(new Date(Long.parseLong(parts[0])));
            } catch (NumberFormatException e) {
                when = "?";
            }
            s.sendMessage(QuickGuard.color("&8 - &7" + when + " &8by &7" + parts[1] + "&8: &f" + parts[2]));
        }
    }

    /** Owner only: locks a player in place so they can't act, and bans them if they log out while frozen. */
    private void freeze(CommandSender s, String[] a) {
        if (!(s instanceof ConsoleCommandSender) && !s.hasPermission("quickguard.owner")) {
            say(s, "&cOnly the server owner can use /freeze.");
            return;
        }
        if (a.length < 1) {
            say(s, "Usage: &e/freeze <player>");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            say(s, "&c" + a[0] + " isn't online.");
            return;
        }
        if (t.equals(s)) {
            say(s, "&cYou can't freeze yourself.");
            return;
        }
        UUID id = t.getUniqueId();
        if (frozen.remove(id)) {
            t.sendMessage(QuickGuard.color("&aYou have been unfrozen."));
            say(s, "&aUnfroze &b" + t.getName() + "&a.");
            plugin.log(s.getName() + " | unfreeze | " + t.getName() + " | " + id);
            return;
        }
        frozen.add(id);
        t.sendMessage(QuickGuard.color(frozenMessage()));
        t.sendTitle(QuickGuard.color(plugin.getConfig().getString("freeze.title", "&c&lFROZEN")),
                QuickGuard.color(plugin.getConfig().getString("freeze.subtitle", "&7Do not log out")), 5, 100, 20);
        say(s, "&aFroze &b" + t.getName() + " &8(" + id + ")&a. Ban them with &e/ban " + t.getName()
                + "&a, or run &e/freeze " + t.getName() + " &aagain to release them.");
        plugin.log(s.getName() + " | freeze | " + t.getName() + " | " + id);
    }

    private String frozenMessage() {
        return plugin.getConfig().getString("moderation.frozen-message",
                "&cYou have been frozen by staff. Don't log out!");
    }

    public boolean isFrozen(UUID id) {
        return frozen.contains(id);
    }

    /** Lets a player go without punishment (used right before staff kick or ban them). */
    public void release(UUID id) {
        frozen.remove(id);
    }

    /** Reminds frozen players every couple of seconds. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (frozen.isEmpty()) return;
            String text = QuickGuard.color(plugin.getConfig().getString("freeze.actionbar",
                    "&c&lFROZEN &7- logging out will get you banned"));
            for (UUID id : frozen) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
            }
        }, 40L, 40L);
    }

    // ---------- enforcing mutes + freezes ----------

    private String mutedMessage(Mute m) {
        String expiry = m.until() > 0 ? " for " + Rules.remaining(m.until() - System.currentTimeMillis()) : "";
        return QuickGuard.color(plugin.getConfig().getString("moderation.muted-message",
                        "&cYou are muted{expiry}. Reason: &f{reason}")
                .replace("{expiry}", expiry).replace("{reason}", m.reason()));
    }

    /** Returns the active mute, or null (expired mutes are cleaned up on the main thread). */
    private Mute activeMute(UUID id) {
        Mute m = mutes.get(id);
        if (m == null) return null;
        if (m.until() > 0 && System.currentTimeMillis() > m.until()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                mutes.remove(id);
                data.set("mutes." + id, null);
                save();
            });
            return null;
        }
        return m;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Mute m = activeMute(p.getUniqueId());
        if (m == null) return;
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(mutedMessage(m)));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        Mute m = activeMute(p.getUniqueId());
        if (m == null) return;
        String msg = e.getMessage().toLowerCase(Locale.ROOT);
        String first = msg.startsWith("/") ? msg.substring(1) : msg;
        int space = first.indexOf(' ');
        if (space >= 0) first = first.substring(0, space);
        int colon = first.indexOf(':');
        if (colon >= 0) first = first.substring(colon + 1);
        for (String blocked : plugin.getConfig().getStringList("moderation.muted-blocked-commands")) {
            if (blocked.equalsIgnoreCase(first)) {
                e.setCancelled(true);
                p.sendMessage(mutedMessage(m));
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (frozen.isEmpty() || !frozen.contains(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.getPlayer().sendMessage(QuickGuard.color(plugin.getConfig().getString("moderation.frozen-message",
                    "&cYou have been frozen by staff. Don't log out!")));
        }
    }

    // ---------- what a frozen player can't do ----------

    private boolean frozenNow(Player p) {
        return !frozen.isEmpty() && frozen.contains(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenInteract(PlayerInteractEvent e) {
        if (frozenNow(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenBreak(BlockBreakEvent e) {
        if (frozenNow(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenPlace(BlockPlaceEvent e) {
        if (frozenNow(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenDrop(PlayerDropItemEvent e) {
        if (frozenNow(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && frozenNow(p)) e.setCancelled(true);
    }

    // frozen players can't be hurt (so nobody kills them and they respawn elsewhere) and can't hurt anyone
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && frozenNow(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && frozenNow(p)) e.setCancelled(true);
    }

    // no commands while frozen (so /spawn, /home, /tpa etc. can't be used to get away); chat still works
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!frozenNow(p)) return;
        e.setCancelled(true);
        p.sendMessage(QuickGuard.color(frozenMessage()));
    }

    // leaving while frozen = banned. Staff kicks/bans release the player first, so those don't count.
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!frozen.remove(p.getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("freeze.ban-on-quit", true)) return;

        String reason = plugin.getConfig().getString("freeze.quit-reason", "Logging Out While Frozen");
        plugin.ban(p, reason, null, "QuickGuard (freeze)", false);
        plugin.log(p.getName() + " | freeze-logout | BAN | " + p.getUniqueId() + " | left the game while frozen");
        String msg = QuickGuard.color("&c&lGuard &8» &b" + p.getName() + " &7left the game while frozen and was banned");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("quickguard.alerts")) staff.sendMessage(msg);
        }
        plugin.getLogger().warning(p.getName() + " (" + p.getUniqueId() + ") left while frozen and was banned");
    }

    // ---------- tab complete ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (name.equals("unban")) {
                for (OfflinePlayer b : Bukkit.getBannedPlayers()) {
                    if (b.getName() != null && b.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(b.getName());
                }
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
                }
            }
        } else if (args.length == 2 && (name.equals("tempban") || name.equals("mute"))) {
            for (String d : List.of("30m", "1h", "12h", "1d", "7d", "30d")) {
                if (d.startsWith(prefix)) out.add(d);
            }
        }
        return out;
    }
}
