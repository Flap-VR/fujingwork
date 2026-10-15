package dev.quickguard;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Speed, fly, water-walking and fast-break checks, sampled once a second.
 *
 * Built to avoid false flags: nothing is checked right after a teleport, knockback, explosion,
 * riptide or elytra flight, while riding, holding a spear, wearing an elytra, or when the server
 * itself is lagging. A player also has to stay over the limit for several seconds in a row.
 */
public final class MovementWatch implements Listener {

    private static final class State {
        Location last;
        long exemptUntil;   // ms: skip speed + fly + water-walking
        long iceUntil;      // ms: skip speed only
        int speedStrikes;
        int flyStrikes;
        int jesusStrikes;
        int nukerStrikes;
    }

    private static final Set<Material> ICE = Set.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);
    private static final Set<Material> SPECIAL = Set.of(
            Material.COBWEB, Material.POWDER_SNOW, Material.HONEY_BLOCK, Material.SLIME_BLOCK,
            Material.BUBBLE_COLUMN, Material.SWEET_BERRY_BUSH);

    private final QuickGuard plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private long lastRunNanos = 0L;
    private boolean steady = true;

    public MovementWatch(QuickGuard plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    /** False when the server was lagging during the last second (other checks skip then too). */
    public boolean isSteady() {
        return steady;
    }

    /** True for a few seconds after a teleport, knockback, riptide, elytra flight and similar. */
    public boolean recentlyDisturbed(Player p) {
        State s = states.get(p.getUniqueId());
        return s != null && System.currentTimeMillis() < s.exemptUntil;
    }

    // ---------- events that make the next few seconds exempt ----------

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    private void exempt(Player p, long ms) {
        State s = state(p);
        s.exemptUntil = Math.max(s.exemptUntil, System.currentTimeMillis() + ms);
    }

    private void resetBaseline(Player p, long ms) {
        State s = state(p);
        s.last = null;
        s.speedStrikes = 0;
        s.flyStrikes = 0;
        s.jesusStrikes = 0;
        s.exemptUntil = Math.max(s.exemptUntil, System.currentTimeMillis() + ms);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        resetBaseline(e.getPlayer(), 8000L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        resetBaseline(e.getPlayer(), 5000L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        resetBaseline(e.getPlayer(), 6000L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        resetBaseline(e.getPlayer(), 6000L);
    }

    // knockback, explosions, wind charges, jump pads...
    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        exempt(e.getPlayer(), 4000L);
    }

    // riptide tridents launch you forward; the event fires the moment you throw, so it's never missed
    @EventHandler
    public void onRiptide(PlayerRiptideEvent e) {
        exempt(e.getPlayer(), 7000L);
    }

    // starting or stopping an elytra glide (landing keeps momentum for a moment)
    @EventHandler
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p) exempt(p, 5000L);
    }

    // ---------- the once-a-second check ----------

    private void tick() {
        long nowNanos = System.nanoTime();
        double elapsed = lastRunNanos == 0L ? 0.0 : (nowNanos - lastRunNanos) / 1_000_000_000.0;
        lastRunNanos = nowNanos;
        // if this second didn't take about a second, the server was lagging: skip it
        steady = elapsed > 0.85 && elapsed < 1.2;

        long nowMs = System.currentTimeMillis();
        FileConfiguration cfg = plugin.getConfig();

        for (Player p : Bukkit.getOnlinePlayers()) {
            State st = state(p);
            Location cur = p.getLocation();
            Location prev = st.last;
            st.last = cur;
            int breaks = plugin.blocks().takeBreaks(p.getUniqueId());

            GameMode gm = p.getGameMode();
            if (plugin.isStaff(p) || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.isDead() || plugin.isFrozen(p)) {
                st.speedStrikes = 0;
                st.flyStrikes = 0;
                st.jesusStrikes = 0;
                st.nukerStrikes = 0;
                continue;
            }
            if (!steady) continue;

            checkFastBreak(p, st, breaks, cfg);

            if (prev == null || prev.getWorld() != cur.getWorld()) continue;
            if (plugin.skipBedrock(p) || p.getPing() > 400) continue;

            updateExemptions(p, st, cur, nowMs);
            if (nowMs < st.exemptUntil) {
                st.speedStrikes = 0;
                st.flyStrikes = 0;
                st.jesusStrikes = 0;
                continue;
            }

            double dx = cur.getX() - prev.getX();
            double dz = cur.getZ() - prev.getZ();
            double dy = cur.getY() - prev.getY();

            checkSpeed(p, st, Math.sqrt(dx * dx + dz * dz) / elapsed, nowMs, cfg);
            checkFly(p, st, cur, dy, cfg);
            checkWaterWalking(p, st, cur, dy, cfg);
        }
    }

    private void updateExemptions(Player p, State st, Location cur, long nowMs) {
        long until = st.exemptUntil;
        if (p.isGliding() || wearsElytra(p)) until = Math.max(until, nowMs + 3000L);
        if (p.isRiptiding()) until = Math.max(until, nowMs + 7000L);
        if (p.isInsideVehicle()) until = Math.max(until, nowMs + 3000L);
        if (holdsSpear(p)) until = Math.max(until, nowMs + 3000L);
        if (p.getAllowFlight() || p.isFlying()) until = Math.max(until, nowMs + 3000L);
        st.exemptUntil = until;

        Block feet = cur.getBlock();
        if (ICE.contains(feet.getType()) || ICE.contains(feet.getRelative(BlockFace.DOWN).getType())) {
            st.iceUntil = nowMs + 2000L;
        }
    }

    private static boolean wearsElytra(Player p) {
        ItemStack chest = p.getInventory().getChestplate();
        return chest != null && chest.getType() == Material.ELYTRA;
    }

    static boolean holdsSpear(Player p) {
        return p.getInventory().getItemInMainHand().getType().name().endsWith("_SPEAR")
                || p.getInventory().getItemInOffHand().getType().name().endsWith("_SPEAR");
    }

    // ---------- speed ----------

    private void checkSpeed(Player p, State st, double speed, long nowMs, FileConfiguration cfg) {
        if (!cfg.getBoolean("checks.speed.enabled", true) || nowMs < st.iceUntil) {
            st.speedStrikes = 0;
            return;
        }
        PotionEffect eff = p.getPotionEffect(PotionEffectType.SPEED);
        double allowed = Rules.allowedSpeed(cfg.getDouble("checks.speed.max-speed", 20.0), p.getWalkSpeed(),
                eff == null ? -1 : eff.getAmplifier());

        if (speed > allowed) st.speedStrikes++;
        else st.speedStrikes = Math.max(0, st.speedStrikes - 1);

        int needed = cfg.getInt("checks.speed.strikes", 8);
        if (st.speedStrikes >= needed) {
            st.speedStrikes = 0;
            plugin.punish(p, "speed", String.format(Locale.ROOT,
                    "%.1f blocks/s (limit %.1f) for %d seconds, no spear/elytra/riptide", speed, allowed, needed));
        }
    }

    // ---------- fly ----------

    private void checkFly(Player p, State st, Location cur, double dy, FileConfiguration cfg) {
        if (!cfg.getBoolean("checks.fly.enabled", true)) {
            st.flyStrikes = 0;
            return;
        }
        boolean excused = p.getPotionEffect(PotionEffectType.LEVITATION) != null
                || p.getPotionEffect(PotionEffectType.SLOW_FALLING) != null;
        boolean hovering = !excused && dy > -1.0 && !hasSupport(p, cur);

        if (hovering) st.flyStrikes++;
        else st.flyStrikes = 0;

        int needed = cfg.getInt("checks.fly.seconds", 8);
        if (st.flyStrikes >= needed) {
            st.flyStrikes = 0;
            plugin.punish(p, "fly", "stayed in the air with nothing under them for " + needed + " seconds");
        }
    }

    /** True if there's any reason the player could be legitimately off the ground. */
    private boolean hasSupport(Player p, Location loc) {
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        // standing in water/lava, on a ladder/vine/scaffolding, in a web, etc.
        for (int up = 0; up <= 1; up++) {
            Block b = w.getBlockAt(x, y + up, z);
            Material t = b.getType();
            if (b.isLiquid() || Tag.CLIMBABLE.isTagged(t) || SPECIAL.contains(t)) return true;
        }
        // anything solid or liquid in the 3x3 area just below
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int down = 0; down <= 2; down++) {
                    Block b = w.getBlockAt(x + ox, y - down, z + oz);
                    if (!b.isPassable() || b.isLiquid()) return true;
                }
            }
        }
        return standingOnEntity(p);
    }

    /** Standing on a boat, minecart or shulker. */
    private static boolean standingOnEntity(Player p) {
        for (Entity e : p.getNearbyEntities(1.5, 3.0, 1.5)) {
            if (e instanceof Vehicle || e instanceof Shulker) return true;
        }
        return false;
    }

    // ---------- walking on water ----------

    private void checkWaterWalking(Player p, State st, Location cur, double dy, FileConfiguration cfg) {
        if (!cfg.getBoolean("checks.jesus.enabled", true)) {
            st.jesusStrikes = 0;
            return;
        }
        Block feet = cur.getBlock();
        Block below = feet.getRelative(BlockFace.DOWN);
        boolean onWater = below.getType() == Material.WATER
                && feet.getType().isAir()
                && Math.abs(dy) < 0.5
                && !p.isInWater()
                && !p.isSwimming()
                && p.getPotionEffect(PotionEffectType.LEVITATION) == null
                && p.getPotionEffect(PotionEffectType.SLOW_FALLING) == null
                && !standingOnEntity(p);

        if (onWater) st.jesusStrikes++;
        else st.jesusStrikes = 0;

        int needed = cfg.getInt("checks.jesus.seconds", 4);
        if (st.jesusStrikes >= needed) {
            st.jesusStrikes = 0;
            plugin.punish(p, "jesus", "stood on top of water for " + needed + " seconds");
        }
    }

    // ---------- fast-break ----------

    private void checkFastBreak(Player p, State st, int breaks, FileConfiguration cfg) {
        if (!cfg.getBoolean("checks.fast-break.enabled", true)) {
            st.nukerStrikes = 0;
            return;
        }
        int limit = cfg.getInt("checks.fast-break.blocks-per-second", 25);
        if (breaks >= limit) st.nukerStrikes++;
        else st.nukerStrikes = Math.max(0, st.nukerStrikes - 1);

        if (st.nukerStrikes >= cfg.getInt("checks.fast-break.strikes", 2)) {
            st.nukerStrikes = 0;
            plugin.punish(p, "fast-break", breaks + " blocks broken in one second");
        }
    }
}
