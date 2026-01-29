package Kinkin.aeternum.calendar;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.TimeSkipEvent.SkipReason;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class SeasonService implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;

    // ✅ Frost world name
    private static final String FROST_WORLD_NAME = "aeternum_frost";

    // ya no es final (se recarga)
    private int daysPerSeason = 28;

    private final boolean advanceOnSleep;          // solo si no seguimos reloj
    private final int realTimeMinutesPerDay;       // modo “tiempo real”
    private final boolean followOverworldTime;     // seguir reloj MC
    private final boolean requirePlayersOnServer;  // congelar si no hay nadie

    // ✅ NUEVO: mundo ancla para el reloj (evita EliteMobs/instancias)
    private final String timeAnchorWorldName;

    // ✅ NUEVO: límite anti-salto (si de pronto brinca 5000 días, NO lo aplicamos)
    private final int maxCatchupDays;

    private CalendarState state;

    // tareas
    private BukkitTask rtTask;       // tiempo real
    private BukkitTask worldClock;   // seguidor de reloj overworld

    // seguimiento de día del mundo (fullTime/24000)
    private long lastWorldDayIdx = Long.MIN_VALUE;

    // anti doble avance (para triggers manuales, no para recovery)
    private long lastDayAdvanceMs = 0L;

    // ✅ anti doble en Frost (para evitar 5->7)
    private long lastFrostManualAdvanceMs = 0L;

    public SeasonService(AeternumSeasonsPlugin plugin) {
        this.plugin = plugin;

        // leemos primero los flags (con rutas compatibles)
        this.advanceOnSleep        = readBool("advance.on_sleep", "calendar.advance.on_sleep", true);
        this.realTimeMinutesPerDay = readInt("advance.real_time_minutes_per_day", "calendar.advance.real_time_minutes_per_day", 0);
        this.followOverworldTime   = readBool("advance.follow_overworld_time", "calendar.advance.follow_overworld_time", true);

        this.requirePlayersOnServer = readBool(
                "advance.require_players_on_server",
                "calendar.advance.require_players_on_server",
                false
        );

        // ✅ NUEVO config
        this.timeAnchorWorldName = readString(
                "advance.time_anchor_world",
                "calendar.advance.time_anchor_world",
                "world"
        );

        this.maxCatchupDays = Math.max(0, readInt(
                "advance.max_catchup_days",
                "calendar.advance.max_catchup_days",
                2
        ));

        this.state = loadState();
        reloadCalendarSettings(); // carga days_per_season correcto
    }

    /**
     * Intenta leer primero sin prefijo; si no existe, usa "calendar.<path>".
     */
    private int readInt(String plainPath, String calendarPath, int def) {
        int v = plugin.cfg.calendar.getInt(plainPath, Integer.MIN_VALUE);
        if (v == Integer.MIN_VALUE) {
            v = plugin.cfg.calendar.getInt(calendarPath, def);
        }
        return v;
    }

    private boolean readBool(String plainPath, String calendarPath, boolean def) {
        if (plugin.cfg.calendar.contains(plainPath)) {
            return plugin.cfg.calendar.getBoolean(plainPath, def);
        }
        if (plugin.cfg.calendar.contains(calendarPath)) {
            return plugin.cfg.calendar.getBoolean(calendarPath, def);
        }
        return def;
    }

    private String readString(String plainPath, String calendarPath, String def) {
        if (plugin.cfg.calendar.contains(plainPath)) {
            return plugin.cfg.calendar.getString(plainPath, def);
        }
        if (plugin.cfg.calendar.contains(calendarPath)) {
            return plugin.cfg.calendar.getString(calendarPath, def);
        }
        return def;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // cancelar por seguridad (evita dobles tareas)
        if (rtTask != null) rtTask.cancel();
        if (worldClock != null) worldClock.cancel();
        rtTask = null;
        worldClock = null;

        // PRIORIDAD: real-time gana, NO corremos worldClock
        if (realTimeMinutesPerDay > 0) {
            long period = 20L * 60L * realTimeMinutesPerDay;
            this.rtTask = Bukkit.getScheduler().runTaskTimer(plugin, this, period, period);
            return;
        }

        // seguir reloj del mundo
        if (followOverworldTime) {
            lastWorldDayIdx = Long.MIN_VALUE; // reset tracking
            this.worldClock = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWorldClock, 40L, 10L);
        }
    }

    public void unregister() {
        if (rtTask != null) rtTask.cancel();
        if (worldClock != null) worldClock.cancel();
        rtTask = null;
        worldClock = null;

        HandlerList.unregisterAll(this);
    }

    /**
     * Vuelve a leer calendar.days_per_season desde config.
     * Llamar después de recargar la config.
     */
    public void reloadCalendarSettings() {
        int v = readInt("days_per_season", "calendar.days_per_season", 28);
        int newValue = Math.max(4, v);

        if (newValue != this.daysPerSeason) {
            this.daysPerSeason = newValue;

            if (state != null && state.day > daysPerSeason) {
                state.day = daysPerSeason;
                persistNow();
            }
        }
    }

    public int getDaysPerSeason() {
        return daysPerSeason;
    }

    /** Usado por el modo tiempo real exclusivamente. */
    @Override
    public void run() {
        if (realTimeMinutesPerDay <= 0) return;

        // Si el server exige jugadores y no hay nadie, congelar.
        if (requirePlayersOnServer && !hasAnyOnlinePlayer()) {
            return;
        }

        // En real-time queremos avanzar sí o sí sin bloquear por debounce
        nextDay(true);
    }

    /* ==================== Núcleo ==================== */

    /**
     * FIX EliteMobs:
     * - Ya NO escaneamos todos los mundos NORMAL.
     * - Solo usamos un mundo ANCLA (por config) para calcular el paso del día.
     * - Anti-salto: si brinca demasiado, reseteamos baseline sin avanzar cientos de años.
     */
    private void tickWorldClock() {
        World w = getAnchorWorld();
        if (w == null) return;

        long idx = w.getFullTime() / 24000L;

        // init
        if (lastWorldDayIdx == Long.MIN_VALUE) {
            lastWorldDayIdx = idx;
            return;
        }

        long daysElapsed = idx - lastWorldDayIdx;
        if (daysElapsed <= 0) return;

        // congelar sin backlog
        if (requirePlayersOnServer && !hasAnyOnlinePlayer()) {
            lastWorldDayIdx = idx;
            return;
        }

        // ✅ anti-salto absurdo (EliteMobs / mundos template con fullTime gigante)
        if (maxCatchupDays > 0 && daysElapsed > maxCatchupDays) {
            plugin.getLogger().warning("[AeternumSeasons] Big time jump detected on anchor world '" + w.getName()
                    + "': +" + daysElapsed + " days. Resetting baseline to prevent fast-forward.");
            lastWorldDayIdx = idx;
            return;
        }

        lastWorldDayIdx = idx;

        for (int i = 0; i < daysElapsed; i++) {
            nextDay(true);
        }
    }

    private World getAnchorWorld() {
        // 1) por nombre configurado
        World w = Bukkit.getWorld(timeAnchorWorldName);
        if (w != null && w.getEnvironment() == World.Environment.NORMAL && !w.getName().equalsIgnoreCase(FROST_WORLD_NAME)) {
            return w;
        }

        // 2) fallback: primer NORMAL que NO sea Frost
        return primaryOverworldSafe();
    }

    /** Mundo base: primer mundo NORMAL que NO sea Frost. Si no hay, usa el primero. */
    private World primaryOverworldSafe() {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return null;
        for (World ww : worlds) {
            if (ww.getEnvironment() != World.Environment.NORMAL) continue;
            if (ww.getName().equalsIgnoreCase(FROST_WORLD_NAME)) continue;
            return ww;
        }
        return worlds.get(0);
    }

    private boolean hasAnyOnlinePlayer() {
        return !Bukkit.getOnlinePlayers().isEmpty();
    }

    /* ==================== API ==================== */

    public synchronized void nextDay() {
        nextDay(false);
    }

    /**
     * @param bypassDebounce true para permitir avances múltiples en loops (recovery/real-time)
     */
    private synchronized void nextDay(boolean bypassDebounce) {
        long now = System.currentTimeMillis();

        if (!bypassDebounce) {
            // anti doble avance (sleep + otras fuentes manuales)
            if (now - lastDayAdvanceMs < 500L) return;
            lastDayAdvanceMs = now;
        }

        state.day++;
        if (state.day > daysPerSeason) {
            state.day = 1;
            state.season = nextSeason(state.season);
            if (state.season == Season.SPRING) state.year++;
        }

        persistNow();
        Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(this, getStateCopy(), true));
    }

    public synchronized void setSeason(Season s) {
        state.season = s;
        if (state.day > daysPerSeason) state.day = daysPerSeason;
        persistNow();
        Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(this, getStateCopy(), false));
    }

    /** /season day <n> */
    public synchronized void setDay(int day) {
        if (day < 1) day = 1;
        if (day > daysPerSeason) day = daysPerSeason;
        state.day = day;
        persistNow();
        Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(this, getStateCopy(), false));
    }

    public synchronized void setYear(int year) {
        if (year < 1) year = 1;
        state.year = year;
        persistNow();
        Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(this, getStateCopy(), false));
    }

    public synchronized CalendarState getStateCopy() {
        return new CalendarState(state.year, state.day, state.season);
    }

    private CalendarState loadState() {
        File f = new File(plugin.getDataFolder(), "data/calendar.yml");
        if (!f.exists()) return new CalendarState(1, 1, Season.SPRING);

        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        int year = y.getInt("year", 1);
        int day = y.getInt("day", 1);

        Season s = Season.SPRING;
        String raw = y.getString("season", "SPRING");
        try {
            s = Season.valueOf(raw);
        } catch (IllegalArgumentException ignored) {}

        if (year < 1) year = 1;
        if (day < 1) day = 1;

        return new CalendarState(year, day, s);
    }

    public void persistNow() {
        try {
            File f = new File(plugin.getDataFolder(), "data/calendar.yml");
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();

            YamlConfiguration y = new YamlConfiguration();
            y.set("year", state.year);
            y.set("day", state.day);
            y.set("season", state.season.name());
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar calendar.yml: " + e.getMessage());
        }
    }

    private Season nextSeason(Season s) {
        return switch (s) {
            case SPRING -> Season.SUMMER;
            case SUMMER -> Season.AUTUMN;
            case AUTUMN -> Season.WINTER;
            case WINTER -> Season.SPRING;
        };
    }

    /* ==================== Frost: /day y /night cuentan como 1 día ==================== */

    @EventHandler(ignoreCancelled = true)
    public void onTimeSkip(TimeSkipEvent e) {
        // solo Frost
        World w = e.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return;
        if (!w.getName().equalsIgnoreCase(FROST_WORLD_NAME)) return;

        // en modo real-time, no tocar
        if (realTimeMinutesPerDay > 0) return;

        // si require_players_on_server=true y no hay nadie, no avances
        if (requirePlayersOnServer && !hasAnyOnlinePlayer()) return;

        SkipReason reason = e.getSkipReason();

        // dormir (night skip real): cuenta 1 día (y marca timestamp para no duplicar en BedLeave)
        if (reason == SkipReason.NIGHT_SKIP) {
            lastFrostManualAdvanceMs = System.currentTimeMillis();
            nextDay();
            return;
        }

        // comandos (/day, /night) suelen llegar como COMMAND
        if (reason != SkipReason.COMMAND) return;

        long cur = w.getTime();
        long skip = e.getSkipAmount();
        long newTime = (cur + skip) % 24000L;
        if (newTime < 0) newTime += 24000L;

        boolean setToDay = (newTime <= 1000L);
        boolean setToNight = (newTime >= 12000L && newTime <= 14000L);

        if (!setToDay && !setToNight) return;

        lastFrostManualAdvanceMs = System.currentTimeMillis();
        nextDay();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerBedLeave(PlayerBedLeaveEvent e) {
        if (!advanceOnSleep) return;

        // Si estamos en modo real-time, el sueño no debe afectar
        if (realTimeMinutesPerDay > 0) return;

        final Player p = e.getPlayer();
        final World w = p.getWorld();

        // solo overworld
        if (w.getEnvironment() != World.Environment.NORMAL) return;

        // Si require_players_on_server=true y no hay nadie (edge-case), no avances
        if (requirePlayersOnServer && !hasAnyOnlinePlayer()) return;

        // ✅ Frost: dormir debe avanzar aunque followOverworldTime=true
        if (w.getName().equalsIgnoreCase(FROST_WORLD_NAME)) {
            long now = System.currentTimeMillis();

            // si ya contamos por TimeSkipEvent recientemente, no duplicar (evita 5->7)
            if (now - lastFrostManualAdvanceMs < 1200L) return;

            // solo si amaneció
            if (w.getTime() <= 1000L) {
                lastFrostManualAdvanceMs = now;
                nextDay();
            }
            return;
        }

        // Overworld normal: comportamiento original
        // Si seguimos el reloj del overworld, NO avanzamos aquí para evitar doble conteo.
        if (followOverworldTime) return;

        // Si no seguimos reloj del mundo, dormir es la forma de avanzar
        nextDay();
    }
}
