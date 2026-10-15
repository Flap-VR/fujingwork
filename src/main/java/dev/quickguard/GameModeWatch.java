package dev.quickguard;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bans anyone who isn't an op (and doesn't have quickguard.bypass) for ending up in creative or spectator.
 * Works the same for Java and Bedrock players, and it doesn't matter who or what caused the change.
 *
 * Three layers, so nothing slips through:
 *  1. The change event: the switch is cancelled and the player is punished.
 *  2. A once-a-second sweep: anyone found in a blocked mode (even if the event never fired) is punished.
 *  3. Logging in: someone who logs in already in creative is put back to survival (not banned).
 *
 * By default a gamemode command from the console or an op does NOT make it allowed. If you want builders to be
 * given creative on purpose, either give them quickguard.bypass or set allow-staff-commands: true in the config.
 */
public final class GameModeWatch implements Listener {

    private static final String BASE = "checks.gamemode.";

    private final QuickGuard plugin;
    private final Map<UUID, Long> joinedAt = new HashMap<>();
    // players staff put in a blocked mode on purpose (only used when allow-staff-commands is true)
    private final Set<UUID> staffAllowed = new HashSet<>();
    private long lastStaffGamemodeCommand = 0L;
    private List<String> lastStaffCommandArgs = List.of();

    public GameModeWatch(QuickGuard plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 40L, 20L);
    }

    // ---------- config ----------

    private boolean enabled() {
        return plugin.getConfig().getBoolean(BASE + "enabled", true);
    }

    private Set<String> blockedModes() {
        Set<String> set = new HashSet<>();
        for (String s : plugin.getConfig().getStringList(BASE + "modes")) set.add(s.toUpperCase(Locale.ROOT));
        if (set.isEmpty()) {
            set.add("CREATIVE");
            set.add("SPECTATOR");
        }
        return set;
    }

    private boolean allowStaffCommands() {
        return plugin.getConfig().getBoolean(BASE + "allow-staff-commands", false);
    }

    private boolean inLoginGrace(UUID id) {
        Long joined = joinedAt.get(id);
        long graceMs = plugin.getConfig().getInt(BASE + "login-grace-seconds", 10) * 1000L;
        return joined != null && System.currentTimeMillis() - joined < graceMs;
    }

    /** A staff gamemode command ran in the last 3 seconds AND it named this player (or used a selector like @a). */
    private boolean staffCommandCovers(Player p) {
        return System.currentTimeMillis() - lastStaffGamemodeCommand < 3000L
                && Rules.commandCovers(lastStaffCommandArgs, p.getName());
    }

    // ---------- remembering staff gamemode commands (only matters if allow-staff-commands is on) ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (plugin.isStaff(e.getPlayer()) && Rules.isGamemodeCommand(e.getMessage())) {
            lastStaffGamemodeCommand = System.currentTimeMillis();
            lastStaffCommandArgs = Rules.commandArgs(e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsoleCommand(ServerCommandEvent e) {
        if (Rules.isGamemodeCommand(e.getCommand())) {
            lastStaffGamemodeCommand = System.currentTimeMillis();
            lastStaffCommandArgs = Rules.commandArgs(e.getCommand());
        }
    }

    // ---------- layer 1: the change itself ----------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChange(PlayerGameModeChangeEvent e) {
        if (!enabled()) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        String mode = e.getNewGameMode().name();
        Set<String> blocked = blockedModes();

        if (!blocked.contains(mode)) {
            staffAllowed.remove(id);   // back to a normal mode: any staff permission ends
            return;
        }

        boolean exempt = plugin.isStaff(p);
        boolean byStaff = allowStaffCommands() && staffCommandCovers(p);
        if (!Rules.gamemodeViolation(exempt, mode, blocked, inLoginGrace(id), byStaff)) {
            if (byStaff && !exempt) staffAllowed.add(id);
            return;
        }

        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) plugin.punish(p, "gamemode", "switched to " + mode + " without permission");
        });
    }

    // ---------- layer 2: the sweep ----------

    private void sweep() {
        if (!enabled()) return;
        Set<String> blocked = blockedModes();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            String mode = p.getGameMode().name();
            if (!blocked.contains(mode)) {
                staffAllowed.remove(id);
                continue;
            }
            if (staffAllowed.contains(id)) continue;
            if (Rules.gamemodeViolation(plugin.isStaff(p), mode, blocked, inLoginGrace(id), false)) {
                plugin.punish(p, "gamemode", "was found in " + mode + " mode without permission");
            }
        }
    }

    // ---------- layer 3: logging in ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        joinedAt.put(p.getUniqueId(), System.currentTimeMillis());

        if (!plugin.getConfig().getBoolean("fix-gamemode-on-join", true)) return;
        // Not a ban: someone left in creative earlier gets put back to survival.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || plugin.isStaff(p) || !enabled()) return;
            if (staffAllowed.contains(p.getUniqueId())) return;
            GameMode now = p.getGameMode();
            if (blockedModes().contains(now.name())) {
                p.setGameMode(GameMode.SURVIVAL);
                plugin.alert(p, "gamemode", "joined in " + now.name() + ", set back to SURVIVAL", "RESET");
                plugin.log(p.getName() + " | gamemode | RESET | joined in " + now.name());
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        joinedAt.remove(id);
        staffAllowed.remove(id);
    }
}
