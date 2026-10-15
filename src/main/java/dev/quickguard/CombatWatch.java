package dev.quickguard;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Fighting checks: reach, fake criticals, spam-clicking, kill aura and fast bow.
 *
 * Nothing here punishes on a single event. Each check needs a pattern (several bad hits out of
 * the last few, or several strikes inside a time window), and all of them skip staff, Bedrock
 * players, high-ping players and moments when the server was lagging.
 */
public final class CombatWatch implements Listener {

    private static final class State {
        final ArrayDeque<Boolean> reach = new ArrayDeque<>();
        final ArrayDeque<Boolean> aim = new ArrayDeque<>();
        final Rules.Strikes crit = new Rules.Strikes();
        final Rules.Strikes multi = new Rules.Strikes();
        final Rules.Strikes bow = new Rules.Strikes();
        int swings;        // arm swings this second
        int playerHits;    // hits on players this second
        int clickStrikes;
        long lastHitMs;
        UUID lastVictim;
        long lastBowMs;
    }

    private final QuickGuard plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private long lastRunNanos = 0L;

    public CombatWatch(QuickGuard plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    private boolean enabled(String check) {
        return plugin.getConfig().getBoolean("checks." + check + ".enabled", true);
    }

    /** Staff, creative/spectator and Bedrock players are never checked. */
    private boolean skip(Player p) {
        GameMode gm = p.getGameMode();
        return plugin.isStaff(p) || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.skipBedrock(p);
    }

    // ---------- arm swings (for the spam-click check) ----------

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("deprecation")
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) state(e.getPlayer()).swings++;
    }

    private void tick() {
        long nowNanos = System.nanoTime();
        double elapsed = lastRunNanos == 0L ? 0.0 : (nowNanos - lastRunNanos) / 1_000_000_000.0;
        lastRunNanos = nowNanos;
        boolean steady = elapsed > 0.85 && elapsed < 1.2;

        FileConfiguration cfg = plugin.getConfig();
        boolean on = enabled("autoclicker");
        int limit = cfg.getInt("checks.autoclicker.clicks-per-second", 25);
        int needed = cfg.getInt("checks.autoclicker.strikes", 3);

        for (Player p : Bukkit.getOnlinePlayers()) {
            State st = states.get(p.getUniqueId());
            if (st == null) continue;
            int swings = st.swings;
            int hits = st.playerHits;
            st.swings = 0;
            st.playerHits = 0;

            if (!on || !steady || skip(p) || p.getPing() > 400) {
                st.clickStrikes = 0;
                continue;
            }
            // only counts when they actually hit a player during that second
            if (swings >= limit && hits >= 1) st.clickStrikes++;
            else st.clickStrikes = Math.max(0, st.clickStrikes - 1);

            if (st.clickStrikes >= needed) {
                st.clickStrikes = 0;
                plugin.punish(p, "autoclicker", swings + " swings in one second while hitting players (limit " + limit + ")");
            }
        }
    }

    // ---------- hits between players ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player att) || !(e.getEntity() instanceof Player vic)) return;
        if (att.equals(vic) || skip(att)) return;

        State st = state(att);
        long now = System.currentTimeMillis();
        st.playerHits++;

        FileConfiguration cfg = plugin.getConfig();
        boolean calm = plugin.movement().isSteady()
                && att.getPing() <= 350 && vic.getPing() <= 350
                && !plugin.movement().recentlyDisturbed(att)
                && !plugin.movement().recentlyDisturbed(vic)
                && !vic.isInsideVehicle() && !vic.isGliding();

        double dist = distanceToHitbox(att, vic);

        // --- reach ---
        if (calm && enabled("reach")) {
            double allowed = Rules.reachLimit(cfg.getDouble("checks.reach.max-distance", 3.0),
                    cfg.getDouble("checks.reach.leniency", 0.35), att.getPing(), vic.getPing());
            boolean tooFar = dist > allowed;
            int size = cfg.getInt("checks.reach.hits-window", 8);
            int needed = cfg.getInt("checks.reach.hits-needed", 5);
            if (Rules.slide(st.reach, tooFar, size, needed)) {
                plugin.punish(att, "reach", String.format(Locale.ROOT,
                        "%d of their last %d hits landed too far away (latest %.2f blocks, allowed %.2f)",
                        needed, size, dist, allowed));
            }
        }

        // --- fake criticals ---
        if (calm && enabled("criticals") && fakeCritSignature(att)) {
            long windowMs = cfg.getInt("checks.criticals.window-seconds", 30) * 1000L;
            if (st.crit.add(now, windowMs, cfg.getInt("checks.criticals.strikes", 3))) {
                plugin.punish(att, "criticals", "hit a player while \"falling\" but standing flat on the ground (no real jump)");
            }
        }

        // --- kill aura: looking nowhere near the target, or hitting two players at once ---
        if (enabled("killaura")) {
            if (calm && dist >= 1.0) {
                Location eye = att.getEyeLocation();
                Vector toTarget = vic.getBoundingBox().getCenter().subtract(eye.toVector());
                if (toTarget.lengthSquared() > 0.0001) {
                    double angle = Math.toDegrees(eye.getDirection().angle(toTarget));
                    boolean off = angle > cfg.getDouble("checks.killaura.max-angle", 75.0);
                    int size = cfg.getInt("checks.killaura.hits-window", 6);
                    int needed = cfg.getInt("checks.killaura.hits-needed", 4);
                    if (Rules.slide(st.aim, off, size, needed)) {
                        plugin.punish(att, "killaura", String.format(Locale.ROOT,
                                "%d of their last %d hits were on players they weren't looking at (latest %.0f degrees off)",
                                needed, size, angle));
                    }
                }
            }
            if (st.lastVictim != null && !st.lastVictim.equals(vic.getUniqueId())
                    && now - st.lastHitMs < 60L && !MovementWatch.holdsSpear(att)) {
                long windowMs = cfg.getInt("checks.killaura.multi-target-window-seconds", 30) * 1000L;
                if (st.multi.add(now, windowMs, cfg.getInt("checks.killaura.multi-target-strikes", 3))) {
                    plugin.punish(att, "killaura", "hit two different players in the same tick");
                }
            }
        }
        st.lastVictim = vic.getUniqueId();
        st.lastHitMs = now;
    }

    /** Distance from the attacker's eyes to the nearest point of the victim's hitbox. */
    private static double distanceToHitbox(Player att, Player vic) {
        Location eye = att.getEyeLocation();
        BoundingBox box = vic.getBoundingBox();
        double cx = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double cy = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double cz = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        double dx = eye.getX() - cx;
        double dy = eye.getY() - cy;
        double dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * "Criticals" hacks fake a tiny fall so the hit counts as a crit without jumping. The server then sees a
     * player with fall distance who claims to be in the air but is standing flat on top of a solid block,
     * which doesn't happen with a real jump or fall.
     */
    @SuppressWarnings("deprecation")
    private static boolean fakeCritSignature(Player p) {
        if (p.getFallDistance() <= 0.0f) return false;
        if (p.isOnGround()) return false;
        if (p.isInsideVehicle() || p.isGliding() || p.isInWater() || p.isSwimming() || p.getAllowFlight()) return false;

        Location l = p.getLocation();
        if (Tag.CLIMBABLE.isTagged(l.getBlock().getType())) return false;
        double frac = l.getY() - Math.floor(l.getY());
        if (frac > 0.001) return false;

        Block under = l.clone().subtract(0, 0.01, 0).getBlock();
        return !under.isPassable() && !under.isLiquid();
    }

    // ---------- fast bow ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack bow = e.getBow();
        if (bow == null || bow.getType() != Material.BOW) return;
        if (!enabled("fast-bow") || skip(p)) return;

        State st = state(p);
        long now = System.currentTimeMillis();
        long gap = now - st.lastBowMs;
        st.lastBowMs = now;

        // a bow needs a full second to charge, so full-power shots less than 0.7s apart aren't possible
        if (e.getForce() >= 0.95f && gap < 700L && p.getPing() <= 350 && plugin.movement().isSteady()) {
            FileConfiguration cfg = plugin.getConfig();
            long windowMs = cfg.getInt("checks.fast-bow.window-seconds", 30) * 1000L;
            if (st.bow.add(now, windowMs, cfg.getInt("checks.fast-bow.strikes", 3))) {
                plugin.punish(p, "fast-bow", "full-power bow shots only " + gap + "ms apart");
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }
}
