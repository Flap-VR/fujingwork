package dev.quickguard;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The decision-making parts of QuickGuard, with no Bukkit in them so they can be tested on their own.
 * Everything that decides "is this suspicious?" or "how long is this?" lives here.
 */
public final class Rules {

    private Rules() {}

    private static final Pattern GM_COMMAND = Pattern.compile(
            "^/?(?:[a-z0-9_]+:)?(?:e?gamemode|e?gm|e?gmc|e?gms|e?gma|e?gmsp|creative|survival|adventure|spectator|defaultgamemode)(?:\\s.*)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhdw])");

    // ---------- gamemode ----------

    /**
     * Should a player who is now in (or switching to) newMode be punished?
     *
     * @param exempt          op, or has quickguard.bypass
     * @param newMode         the gamemode name, like CREATIVE
     * @param blocked         the blocked gamemode names (upper case)
     * @param inLoginGrace    they logged in a moment ago (the game applies a saved gamemode then)
     * @param allowedByStaff  a staff gamemode command just did this AND the config allows that
     */
    public static boolean gamemodeViolation(boolean exempt, String newMode, Collection<String> blocked,
                                            boolean inLoginGrace, boolean allowedByStaff) {
        if (exempt) return false;
        if (newMode == null || !blocked.contains(newMode.toUpperCase(Locale.ROOT))) return false;
        if (inLoginGrace) return false;
        return !allowedByStaff;
    }

    /** Does this command text change someone's gamemode? (/gamemode, /gmc, /minecraft:gamemode, /egm ...) */
    public static boolean isGamemodeCommand(String command) {
        return command != null && GM_COMMAND.matcher(command.trim()).matches();
    }

    /** The words after the command name, lower case: "/gamemode creative Steve" -> [creative, steve]. */
    public static java.util.List<String> commandArgs(String command) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (command == null) return out;
        String[] parts = command.trim().split("\\s+");
        for (int i = 1; i < parts.length; i++) out.add(parts[i].toLowerCase(Locale.ROOT));
        return out;
    }

    /** Does a gamemode command with these arguments apply to this player? (their name, or a selector like @a) */
    public static boolean commandCovers(java.util.List<String> args, String playerName) {
        if (playerName == null) return false;
        String name = playerName.toLowerCase(Locale.ROOT);
        for (String a : args) {
            if (a.equals(name) || a.startsWith("@")) return true;
        }
        return false;
    }

    // ---------- speed + reach ----------

    /** Blocks per second a player may move before it counts as too fast. speedAmplifier is -1 for no Speed potion. */
    public static double allowedSpeed(double base, double walkSpeed, int speedAmplifier) {
        double allowed = base * Math.max(1.0, walkSpeed / 0.2);
        if (speedAmplifier >= 0) allowed *= 1.0 + 0.2 * (speedAmplifier + 1);
        return allowed;
    }

    /** How far (eyes to hitbox) a hit may land: the normal reach, a little slack, and an allowance for lag. */
    public static double reachLimit(double max, double leniency, int attackerPing, int victimPing) {
        int a = Math.min(Math.max(attackerPing, 0), 250);
        int v = Math.min(Math.max(victimPing, 0), 250);
        return max + leniency + (a + v) / 1000.0 * 1.5;
    }

    // ---------- patterns over time ----------

    /** Adds a result to a rolling window; true (and clears) when enough of the recent results were bad. */
    public static boolean slide(ArrayDeque<Boolean> q, boolean bad, int size, int needed) {
        q.addLast(bad);
        while (q.size() > size) q.removeFirst();
        int count = 0;
        for (boolean b : q) if (b) count++;
        if (count >= needed) {
            q.clear();
            return true;
        }
        return false;
    }

    /** Counts strikes inside a time window; true when the needed number is reached. */
    public static final class Strikes {
        int count;
        long start;

        public boolean add(long now, long windowMs, int needed) {
            if (count == 0 || now - start > windowMs) {
                count = 0;
                start = now;
            }
            count++;
            if (count >= needed) {
                count = 0;
                return true;
            }
            return false;
        }
    }

    // ---------- times ----------

    /** "30m", "12h", "7d", "1d12h" -> milliseconds, or -1 if it doesn't look like a time. */
    public static long parseDuration(String s) {
        if (s == null || s.isEmpty()) return -1L;
        Matcher m = DURATION_PART.matcher(s.toLowerCase(Locale.ROOT));
        long total = 0L;
        int end = 0;
        try {
            while (m.find()) {
                if (m.start() != end) return -1L;
                long n = Long.parseLong(m.group(1));
                long unit = switch (m.group(2)) {
                    case "s" -> 1_000L;
                    case "m" -> 60_000L;
                    case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L;
                    default -> 604_800_000L;
                };
                total = Math.addExact(total, Math.multiplyExact(n, unit));
                end = m.end();
            }
        } catch (NumberFormatException | ArithmeticException e) {
            return -1L;
        }
        return end == s.length() && total > 0 ? total : -1L;
    }

    /** 93784000 -> "1d 2h 3m" */
    public static String remaining(long ms) {
        long s = Math.max(0L, ms) / 1000L;
        long d = s / 86_400L;
        long h = (s % 86_400L) / 3_600L;
        long m = (s % 3_600L) / 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0 || sb.isEmpty()) sb.append(m).append("m");
        return sb.toString().trim();
    }
}
