package dev.quickguard;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Two block-based checks:
 *  - x-ray: several rare-ore VEINS in a row with no other ore mined in between
 *  - fast-break: how many blocks a player breaks each second (read by MovementWatch)
 *
 * To avoid false flags, an ore only counts if it was hidden: ores that were already open to
 * air/water/lava (caves) are ignored, and ores a player placed themselves are ignored.
 */
public final class BlockWatch implements Listener {

    private record BlockKey(UUID world, int x, int y, int z) {}

    private static final class Streak {
        int count;
        long start;
        final List<Location> anchors = new ArrayList<>();
    }

    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final long OWN_DIGGING_MS = 20_000L;

    private final QuickGuard plugin;
    private final Map<UUID, Streak> streaks = new HashMap<>();
    private final Map<UUID, Map<BlockKey, Long>> recentBreaks = new HashMap<>();
    private final Map<UUID, Integer> breakCounts = new HashMap<>();
    private final Set<BlockKey> placedOres = new HashSet<>();
    private Set<String> rareOres = new HashSet<>();

    public BlockWatch(QuickGuard plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        Set<String> set = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("checks.xray.ores")) {
            set.add(s.toUpperCase(Locale.ROOT));
        }
        rareOres = set;
    }

    /** Blocks broken since the last call (used by the fast-break check once a second). */
    public int takeBreaks(UUID id) {
        Integer n = breakCounts.remove(id);
        return n == null ? 0 : n;
    }

    private static boolean isOre(Material m) {
        String n = m.name();
        return n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS");
    }

    private static BlockKey key(Block b) {
        return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isOre(e.getBlockPlaced().getType())) {
            if (placedOres.size() > 20_000) placedOres.clear();
            placedOres.add(key(e.getBlockPlaced()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        GameMode gm = p.getGameMode();
        if (plugin.isStaff(p) || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Block b = e.getBlock();
        Material type = b.getType();
        BlockKey k = key(b);

        breakCounts.merge(id, 1, Integer::sum);

        // remember what this player dug recently (used to tell "I dug to it" from "it was already open")
        Map<BlockKey, Long> recent = recentBreaks.computeIfAbsent(id, x -> new HashMap<>());
        if (recent.size() > 400) recent.values().removeIf(t -> now - t > OWN_DIGGING_MS);
        if (recent.size() > 5000) recent.clear();
        boolean wasPlacedOre = placedOres.remove(k);

        if (!plugin.getConfig().getBoolean("checks.xray.enabled", true)) {
            recent.put(k, now);
            return;
        }

        if (wasPlacedOre || !isOre(type)) {
            recent.put(k, now);
            return;
        }

        // any other ore resets the streak
        if (!rareOres.contains(type.name())) {
            recent.put(k, now);
            streaks.remove(id);
            return;
        }

        // a rare ore that was already open to a cave/lava/water isn't suspicious: ignore it
        boolean hidden = true;
        for (BlockFace f : FACES) {
            Block n = b.getRelative(f);
            if (!n.getType().isOccluding()) {
                Long dug = recent.get(key(n));
                if (dug == null || now - dug > OWN_DIGGING_MS) {
                    hidden = false;
                    break;
                }
            }
        }
        recent.put(k, now);
        if (!hidden) return;

        int needed = plugin.getConfig().getInt("checks.xray.streak", 5);
        double radius = plugin.getConfig().getDouble("checks.xray.vein-radius", 6.0);
        long windowMs = plugin.getConfig().getInt("checks.xray.window-minutes", 30) * 60_000L;

        Streak s = streaks.computeIfAbsent(id, x -> new Streak());
        if (s.count > 0 && now - s.start > windowMs) {
            s.count = 0;
            s.anchors.clear();
        }

        // part of a vein that's already counted? then it's the same find
        Location here = b.getLocation();
        for (Location a : s.anchors) {
            if (a.getWorld() == here.getWorld() && a.distanceSquared(here) <= radius * radius) return;
        }

        if (s.count == 0) s.start = now;
        s.count++;
        s.anchors.add(here);

        if (s.count >= needed) {
            int found = s.count;
            streaks.remove(id);
            plugin.punish(p, "xray", found + " hidden rare-ore veins in a row with no other ore mined");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        streaks.remove(id);
        recentBreaks.remove(id);
        breakCounts.remove(id);
    }
}
