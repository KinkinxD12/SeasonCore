package Kinkin.aeternum.hud;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class HudService implements Listener, Runnable {

    public enum HudMode {
        FIXED,
        VARIABLE,
        OFF
    }

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;

    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, HudMode> modes = new HashMap<>();

    // Persistentes por jugador
    private final Set<UUID> variablePlayers = new HashSet<>();
    private final Set<UUID> offPlayers = new HashSet<>();

    private BukkitTask task;

    private final boolean bossbarEnabled;
    private final boolean actionbarEnabled;

    // NUEVO: si quieres limpiar tu actionbar al ocultar (por defecto NO, para no pisar otros plugins)
    private final boolean actionbarClearOnHide;

    private final boolean colorBySeason;
    private final long updateTicks;
    private final HudMode defaultMode;

    // NUEVO: cache para NO spamear actionbar y reducir conflictos
    private final Map<UUID, String> lastActionbarText = new HashMap<>();
    private final Set<UUID> actionbarShown = new HashSet<>();

    public HudService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;

        this.bossbarEnabled   = plugin.cfg.hud.getBoolean("bossbar.enabled", true);
        this.actionbarEnabled = plugin.cfg.hud.getBoolean("actionbar.enabled", false);

        // NUEVO (default false): NO borrar actionbar al ocultar
        this.actionbarClearOnHide = plugin.cfg.hud.getBoolean("actionbar.clear_on_hide", false);

        this.colorBySeason = plugin.cfg.hud.getBoolean("bossbar.color_by_season", true);
        this.updateTicks   = plugin.cfg.hud.getLong("bossbar.update_ticks", 40L);

        String rawDefault = plugin.cfg.hud.getString("bossbar.default_mode", "FIXED");
        HudMode dm;
        try {
            dm = HudMode.valueOf(rawDefault.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            dm = HudMode.FIXED;
        }
        this.defaultMode = dm;

        for (String s : plugin.cfg.hud.getStringList("bossbar.variable_players")) {
            try { variablePlayers.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }

        for (String s : plugin.cfg.hud.getStringList("bossbar.off_players")) {
            try { offPlayers.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
    }

    public void register() {
        if (!bossbarEnabled && !actionbarEnabled) return;

        Bukkit.getPluginManager().registerEvents(this, plugin);

        if (bossbarEnabled) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                ensureBar(p);
            }
        }

        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, updateTicks);
    }

    public void unregister() {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);

        bars.values().forEach(BossBar::removeAll);
        bars.clear();
        modes.clear();
        variablePlayers.clear();
        offPlayers.clear();

        lastActionbarText.clear();
        actionbarShown.clear();
    }

    // ───────────────────── Eventos de jugador ─────────────────────

    @org.bukkit.event.EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (bossbarEnabled) ensureBar(e.getPlayer());
    }

    @org.bukkit.event.EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!bossbarEnabled) return;
        Bukkit.getScheduler().runTask(plugin, () -> ensureBar(e.getPlayer()));
    }

    @org.bukkit.event.EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (bossbarEnabled) ensureBar(e.getPlayer());
    }

    @org.bukkit.event.EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        BossBar bar = bars.remove(id);
        if (bar != null) bar.removeAll();

        // IMPORTANTE: NO limpiar actionbar aquí (eso pisa otros plugins).
        // Solo limpiamos si el admin lo pidió explícitamente.
        if (actionbarEnabled && actionbarClearOnHide) {
            e.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
        }

        lastActionbarText.remove(id);
        actionbarShown.remove(id);

        modes.remove(id);
    }

    private void ensureBar(Player p) {
        UUID id = p.getUniqueId();
        BossBar bar = bars.get(id);

        if (bar == null) {
            bar = Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SEGMENTED_10);
            bars.put(id, bar);
        }

        if (!bar.getPlayers().contains(p)) {
            bar.addPlayer(p);
        }
    }

    public void setPlayerMode(Player p, HudMode mode) {
        UUID id = p.getUniqueId();
        modes.put(id, mode);

        if (mode == HudMode.OFF) {
            offPlayers.add(id);
            variablePlayers.remove(id);
        } else if (mode == HudMode.VARIABLE) {
            variablePlayers.add(id);
            offPlayers.remove(id);
        } else {
            variablePlayers.remove(id);
            offPlayers.remove(id);
        }

        if (bossbarEnabled) {
            ensureBar(p);
        }

        // Si apagas HUD, NO borres actionbar a menos que esté activado clear_on_hide
        if (actionbarEnabled && mode == HudMode.OFF) {
            actionbarShown.remove(id);
            lastActionbarText.remove(id);
            if (actionbarClearOnHide) {
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
            }
        }

        savePlayers("bossbar.variable_players", variablePlayers);
        savePlayers("bossbar.off_players", offPlayers);
    }

    public HudMode getPlayerMode(Player p) {
        UUID id = p.getUniqueId();

        HudMode mem = modes.get(id);
        if (mem != null) return mem;

        if (offPlayers.contains(id)) {
            modes.put(id, HudMode.OFF);
            return HudMode.OFF;
        }
        if (variablePlayers.contains(id)) {
            modes.put(id, HudMode.VARIABLE);
            return HudMode.VARIABLE;
        }

        return defaultMode;
    }

    private void savePlayers(String path, Set<UUID> set) {
        List<String> out = new ArrayList<>();
        for (UUID u : set) out.add(u.toString());
        plugin.cfg.hud.set(path, out);
        plugin.saveConfig();
    }

    @Override
    public void run() {
        CalendarState s = seasons.getStateCopy();
        int daysPerSeason = seasons.getDaysPerSeason();

        World overworld = primaryOverworld();
        long time = (overworld != null ? overworld.getTime() : 0L);

        for (Player p : Bukkit.getOnlinePlayers()) {

            World pw = p.getWorld();
            UUID pid = p.getUniqueId();

            if (plugin.isWorldDisabled(pw)) {
                BossBar bar = bars.remove(pid);
                if (bar != null) bar.removeAll();

                // NO limpiar actionbar (no pisar otros plugins)
                actionbarShown.remove(pid);
                lastActionbarText.remove(pid);

                HudMode currentMode = getPlayerMode(p);
                if (currentMode != HudMode.OFF) {
                    modes.remove(pid);
                }
                continue;
            }

            BossBar bar = null;
            if (bossbarEnabled) {
                ensureBar(p);
                bar = bars.get(pid);
                if (bar == null) continue;
            }

            HudMode mode = getPlayerMode(p);

            if (mode == HudMode.OFF) {
                if (bossbarEnabled) {
                    bar.setVisible(false);
                    bar.removePlayer(p);
                }
                // NO mandar "" al actionbar
                actionbarShown.remove(pid);
                lastActionbarText.remove(pid);
                continue;
            } else {
                if (bossbarEnabled) {
                    if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
                }
            }

            boolean inFrostOverworld = pw != null && pw.getName().equalsIgnoreCase("aeternum_frost");
            boolean inHeatWorld      = pw != null && pw.getName().equalsIgnoreCase("aeternum_heat");

            String title;
            Season visualSeason;
            double progress;

            if (inHeatWorld) {
                String realmName = plugin.lang.tr(p, "realm.heat_overworld");
                title = realmName;
                visualSeason = Season.SUMMER;
                progress = 1.0;

            } else if (inFrostOverworld) {
                int frostDay = s.day;

                String seasonName = plugin.lang.tr(p, "season.WINTER");
                String realmName  = plugin.lang.tr(p, "realm.frost_overworld");
                visualSeason = Season.WINTER;

                title = plugin.lang.trf(p, "hud.title_dim", Map.of(
                        "day", frostDay,
                        "year", s.year,
                        "season", seasonName,
                        "realm", realmName
                ));

                progress = Math.max(0.0, Math.min(1.0,
                        (double) frostDay / (double) daysPerSeason
                ));

            } else {
                String seasonName = plugin.lang.tr(p, "season." + s.season.name());
                visualSeason = s.season;

                title = plugin.lang.trf(p, "hud.title", Map.of(
                        "day", s.day,
                        "year", s.year,
                        "season", seasonName
                ));

                progress = Math.max(0.0, Math.min(1.0,
                        (double) s.day / (double) daysPerSeason
                ));
            }

// ───────────── ActionBar (NO INVASIVO, FIXED SIEMPRE) ─────────────
            if (actionbarEnabled) {

                if (mode == HudMode.FIXED) {
                    // FIXED = siempre enviar (si no, otros plugins te lo pisan y desaparece)
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(title));
                    lastActionbarText.put(pid, title);
                    actionbarShown.add(pid);
                } else {
                    // VARIABLE = solo en “momentos”
                    boolean showNow = isHudTime(time);

                    if (showNow) {
                        String last = lastActionbarText.get(pid);
                        boolean wasShown = actionbarShown.contains(pid);

                        if (!wasShown || !Objects.equals(last, title)) {
                            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(title));
                            lastActionbarText.put(pid, title);
                            actionbarShown.add(pid);
                        }
                    } else {
                        // NO mandar "" => no pisar otros plugins
                        actionbarShown.remove(pid);
                        lastActionbarText.remove(pid);

                        if (actionbarClearOnHide) {
                            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
                        }
                    }
                }
            }

            // ───────────── BossBar original ─────────────
            if (bossbarEnabled) {
                bar.setTitle(title);
                bar.setProgress(progress);

                if (colorBySeason) {
                    bar.setColor(switch (visualSeason) {
                        case SPRING -> BarColor.GREEN;
                        case SUMMER -> BarColor.YELLOW;
                        case AUTUMN -> BarColor.RED;
                        case WINTER -> BarColor.BLUE;
                    });
                }

                if (mode == HudMode.FIXED) {
                    bar.setVisible(true);
                } else {
                    bar.setVisible(isHudTime(time));
                }
            }
        }
    }

    private World primaryOverworld() {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return null;
        for (World w : worlds) {
            if (w.getEnvironment() == World.Environment.NORMAL) return w;
        }
        return worlds.get(0);
    }

    private boolean isHudTime(long time) {
        long t = time % 24000L;
        if (t >= 0 && t < 2000) return true;
        if (t >= 6000 && t < 8000) return true;
        if (t >= 13000 && t < 15000) return true;
        return false;
    }
}
