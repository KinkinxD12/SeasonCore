package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.calendar.SeasonUpdateEvent;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Snow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Snowable;
import org.bukkit.Chunk;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;


import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Nieve/hielo:
 * - En WINTER: pinta nieve y congela agua alrededor de los jugadores.
 * - Fuera de WINTER:
 * * bloquea la creación vanilla de nieve/hielo (lluvia, congelación, golems, etc.)
 * * derrite TODA la nieve/hielo alrededor de los jugadores, sin mirar bioma.
 *
 * Hojas:
 * - En AUTUMN: pinta hojas spruce/birch como ACACIA_LEAVES en biomas taiga/birch.
 * - Fuera de AUTUMN: revierte esas hojas a su material original.
 */
public final class WinterWorldPainter implements Listener, Runnable {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private BukkitTask task;

    // tracking para revertir al apagar
    private final Set<String> paintedSnow = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> paintedIce  = Collections.synchronizedSet(new HashSet<>());

    private final Set<String> protectedSnow = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> protectedIce  = Collections.synchronizedSet(new HashSet<>());

    // ===== startup catch-up melt =====
    private boolean startupMeltEnabled;
    private int startupMeltChunksPerTick;
    private final Deque<Chunk> startupQueue = new ArrayDeque<>();
    private boolean startupRunning = false;

    // tracking de hojas pintadas (key -> material original)
    private final Map<String, Material> paintedLeaves =
            Collections.synchronizedMap(new HashMap<>());

    // ===== config cache =====
    private boolean enabled;
    private long   period;
    private int    budget;            // columnas por tick (nieve/hielo en invierno)
    private int    radius;            // radio en bloques
    private double placeChance;
    private double addLayerChance;
    private boolean freezeWater;

    // boost durante tormenta real (storm + temp fría)
    private boolean stormBoostEnabled;
    private double  stormBudgetMultiplier;
    private double  stormPlaceMultiplier;
    private double  stormLayerMultiplier;
    private int     stormRadiusBonus;

    // melt fuera de invierno
    private boolean meltWhenNotWinter;
    private long    meltPeriod;       // si 0, usa period
    private int     meltBudgetPerTick;
    private boolean meltAlsoIce;

    // Autumn foliage
    private boolean autumnFoliageEnabled;
    private int     autumnRadiusBlocks;
    private int     autumnPaintBudgetPerTick;
    private int     autumnRevertBudgetPerTick;
    private boolean revertLeavesOnNonAutumn;

    private Set<String> disabledFxWorlds = new HashSet<>();


    public WinterWorldPainter(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;
        this.startupMeltEnabled       = plugin.cfg.climate.getBoolean("real_snow.startup_melt.enabled", true);
        this.startupMeltChunksPerTick = Math.max(1, plugin.cfg.climate.getInt("real_snow.startup_melt.chunks_per_tick", 2));

        reloadFromConfig();
    }

    public void register() {
        WinterWorldGuardHelper.init(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        schedule();
        prepareStartupMelt();
    }

    public void unregister() {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);
        clearAllPainted();
    }

    private void schedule() {
        if (task != null) task.cancel();
        if (!enabled) return;

        long periodTicks = Math.max(1L, period);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this, 40L, periodTicks);
    }

    public void reloadFromConfig() {
        this.enabled          = plugin.cfg.climate.getBoolean("real_snow.enabled", true);
        this.period           = plugin.cfg.climate.getLong("real_snow.tick_period_ticks", 10L);
        this.budget           = plugin.cfg.climate.getInt("real_snow.max_columns_per_tick", 24);
        this.radius           = plugin.cfg.climate.getInt("real_snow.radius_blocks", 40);
        this.placeChance      = plugin.cfg.climate.getDouble("real_snow.place_chance", 0.20);
        this.addLayerChance   = plugin.cfg.climate.getDouble("real_snow.add_layer_chance", 0.30);
        this.freezeWater      = plugin.cfg.climate.getBoolean("real_snow.freeze_water", true);

        this.stormBoostEnabled    = plugin.cfg.climate.getBoolean("real_snow.storm_boost.enabled", true);
        this.stormBudgetMultiplier= clamp(plugin.cfg.climate.getDouble("real_snow.storm_boost.budget_multiplier", 2.0), 1.0, 20.0);
        this.stormPlaceMultiplier = clamp(plugin.cfg.climate.getDouble("real_snow.storm_boost.place_multiplier", 1.5), 1.0, 10.0);
        this.stormLayerMultiplier = clamp(plugin.cfg.climate.getDouble("real_snow.storm_boost.layer_multiplier", 1.5), 1.0, 10.0);
        this.stormRadiusBonus     = Math.max(0, plugin.cfg.climate.getInt("real_snow.storm_boost.radius_bonus_blocks", 8));

        this.meltWhenNotWinter = plugin.cfg.climate.getBoolean("real_snow.melt.enabled", true);
        this.meltPeriod        = plugin.cfg.climate.getLong("real_snow.melt.tick_period_ticks", 0L); // 0 = usar period
        this.meltBudgetPerTick = plugin.cfg.climate.getInt("real_snow.melt.budget_blocks_per_tick", 300);
        this.meltAlsoIce       = plugin.cfg.climate.getBoolean("real_snow.melt.also_ice", true);

        this.autumnFoliageEnabled      = plugin.cfg.climate.getBoolean("autumn_foliage.enabled", true);
        this.autumnRadiusBlocks        = plugin.cfg.climate.getInt("autumn_foliage.radius_blocks", 48);
        this.autumnPaintBudgetPerTick  = plugin.cfg.climate.getInt("autumn_foliage.paint_budget_per_tick", 220);
        this.autumnRevertBudgetPerTick = plugin.cfg.climate.getInt("autumn_foliage.revert_budget_per_tick", 400);
        this.revertLeavesOnNonAutumn   = plugin.cfg.climate.getBoolean("autumn_foliage.revert_on_non_autumn", true);

        // límites duros
        this.budget = Math.min(this.budget, 40);
        this.meltBudgetPerTick = Math.min(this.meltBudgetPerTick, 400);
        this.autumnPaintBudgetPerTick = Math.min(this.autumnPaintBudgetPerTick, 40);
        this.autumnRevertBudgetPerTick = Math.min(this.autumnRevertBudgetPerTick, 80);

        schedule();

        List<String> list = plugin.getConfig().getStringList("worlds.disabled_season_fx");
        Set<String> s = new HashSet<>();
        for (String w : list) {
            if (w == null) continue;
            String name = w.trim();
            if (!name.isEmpty()) s.add(name.toLowerCase(Locale.ROOT));
        }
        this.disabledFxWorlds = s;

    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @EventHandler
    public void onSeasonChange(SeasonUpdateEvent e) {
        CalendarState st = seasons.getStateCopy();

        // Salimos de WINTER --> arrancar derretido global progresivo
//        if (st.season != Season.WINTER && meltWhenNotWinter) {
//            plugin.getLogger().info("[AeternumSeasons] Season changed to " + st.season
//                    + " - starting global progressive melt of snow/ice.");
//            prepareStartupMelt();   // <-- IMPORTANTE: reencola todos los chunks cargados
//        }
    }

    /**
     * Evita que Minecraft genere nieve/hielo fuera de WINTER
     * (lluvia en biomas fríos, congelación de agua, etc.).
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent e) {
        Material newType = e.getNewState().getType();
        if (newType != Material.SNOW &&
                newType != Material.SNOW_BLOCK &&
                newType != Material.ICE &&
                newType != Material.FROSTED_ICE) {
            return; // no nos importa
        }

        CalendarState st = seasons.getStateCopy();
        // En invierno: si freeze_water=false, bloqueamos que el agua se convierta en ICE/FROSTED_ICE
        if (st.season == Season.WINTER) {
            if (!freezeWater && (newType == Material.ICE || newType == Material.FROSTED_ICE)) {
                e.setCancelled(true);
            }
            return;
        }

        Block b = e.getBlock();
        World w = b.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return;

        int cx = b.getX() >> 4;
        int cz = b.getZ() >> 4;

        // Si el chunk era NEVADO originalmente, NO cancelamos:
        if (BiomeSpoofAdapter.isChunkNaturallySnowy(w, cx, cz)) {
            return; // dejar que se forme la nieve/hielo vanilla
        }

        // Si está protegido por WorldGuard, no tocamos nada (deja vanilla)
        if (!WinterWorldGuardHelper.canModify(b)) return;

        // Resto de biomas: bloqueamos nieve/hielo fuera de winter
        e.setCancelled(true);
    }

    /**
     * Evita nieve de golems de nieve, etc., fuera de WINTER.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityBlockForm(EntityBlockFormEvent e) {
        Material newType = e.getNewState().getType();
        if (newType != Material.SNOW &&
                newType != Material.SNOW_BLOCK &&
                newType != Material.ICE &&
                newType != Material.FROSTED_ICE) {
            return;
        }

        CalendarState st = seasons.getStateCopy();
        if (st.season == Season.WINTER) {
            // Si freeze_water=false, no dejamos que entidades (Frost Walker, etc.) creen ICE/FROSTED_ICE
            if (!freezeWater && (newType == Material.ICE || newType == Material.FROSTED_ICE)) {
                e.setCancelled(true);
            }
            return; // en invierno, snow golems y demás funcionan normal
        }

        Block b = e.getBlock();
        World w = b.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return;

        int cx = b.getX() >> 4;
        int cz = b.getZ() >> 4;

        // En chunks originalmente nevados dejamos que el golem/etc. ponga nieve
        if (BiomeSpoofAdapter.isChunkNaturallySnowy(w, cx, cz)) {
            return;
        }

        // Si está protegido por WorldGuard, no tocamos nada (deja vanilla)
        if (!WinterWorldGuardHelper.canModify(b)) return;

        // En el resto de biomas seguimos bloqueando nieve/hielo fuera de winter
        e.setCancelled(true);
    }

    @Override
    public void run() {
        if (!enabled) return;

        CalendarState st = seasons.getStateCopy();
        Season season = st.season;
        boolean isWinter = (season == Season.WINTER);
        boolean isAutumn = (season == Season.AUTUMN);

        // ===== HOJAS OTOÑO =====
        if (autumnFoliageEnabled) {
            if (isAutumn) {
                paintAutumnLeavesStep();
            } else if (revertLeavesOnNonAutumn) {
                revertLeavesStep();
            }
        }

        // ✅ NUEVO: catch-up melt global al arrancar (solo fuera de invierno)
        if (!isWinter && startupRunning) {
            startupMeltStep();
            // no regreses; deja que también haga meltAllStep
        }

        // ===== NIEVE/HIELO =====
        if (!isWinter) {
            if (meltWhenNotWinter) {
                meltAllStep();
            }
            return;
        }

        // En invierno: pintamos nieve/hielo
        spawnWinterSnowAndIce();
    }

    private void startupMeltStep() {
        int chunks = startupMeltChunksPerTick;

        while (chunks-- > 0 && !startupQueue.isEmpty()) {
            Chunk ch = startupQueue.poll();
            if (ch == null || !ch.isLoaded()) continue;

            World w = ch.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) continue;

            boolean naturallySnowy = BiomeSpoofAdapter.isChunkNaturallySnowy(w, ch.getX(), ch.getZ());

            if (naturallySnowy) continue; // nunca tocar nieve natural

            int minY = w.getMinHeight();
            int maxY = w.getMaxHeight();

            int baseX = ch.getX() << 4;
            int baseZ = ch.getZ() << 4;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = maxY - 1; y >= minY; y--) {
                        Block b = w.getBlockAt(baseX + x, y, baseZ + z);
                        Material t = b.getType();

                        if (t == Material.SNOW || t == Material.SNOW_BLOCK) {
                            if (!WinterWorldGuardHelper.canIceMelt(b)) {
                                continue;
                            }
                            b.setType(Material.AIR, false);
                            clearSnowyBelow(b);
                        } else if (meltAlsoIce && (t == Material.ICE || t == Material.FROSTED_ICE)) {
                            if (!WinterWorldGuardHelper.canIceMelt(b)) {
                                continue;
                            }
                            b.setType(Material.WATER, false);
                        } else {
                            // quita snowy de bloques sin nieve arriba
                            BlockData data = b.getBlockData();
                            if (data instanceof Snowable snowData && snowData.isSnowy()) {
                                Block above = b.getRelative(BlockFace.UP);
                                Material aboveType = above.getType();
                                if (aboveType != Material.SNOW && aboveType != Material.SNOW_BLOCK) {
                                    if (!WinterWorldGuardHelper.canIceMelt(b)) {
                                        continue;
                                    }
                                    snowData.setSnowy(false);
                                    b.setBlockData(snowData, false);
                                }
                            }
                        }
                    }
                }
            }
        }

//        if (startupQueue.isEmpty()) {
//            startupRunning = false;
//            plugin.getLogger().info("[AeternumSeasons] StartupMelt finished.");
//        }
    }


    /* ===================== Invierno: nieve/hielo ===================== */

    // *** NUEVA LÓGICA DE BLOQUEO DE NIEVE ***

    /**
     * Devuelve true si el bloque de tierra NO debería acumular nieve (bloques de luz,
     * bloques no completos como escaleras/losas, o si ya es nieve).
     */
    private boolean shouldBlockSnow(Block ground) {
        Material type = ground.getType();
        if (type == Material.SNOW) return false;

        String name = type.name();

        // Luz / emisores (igual que ya lo tienes)
        if (name.contains("GLOWSTONE") ||
                name.contains("SEA_LANTERN") ||
                name.contains("SHROOMLIGHT") ||
                name.contains("REDSTONE_LAMP") ||
                type == Material.LIGHT) {
            return true;
        }

        // Plantas / cultivos (ESTO arregla tus casos)
        if (Tag.CROPS.isTagged(type) ||
                Tag.FLOWERS.isTagged(type) ||
                Tag.SAPLINGS.isTagged(type) ||
                name.startsWith("POTTED_") ||           // macetas
                name.contains("MUSHROOM") ||
                name.contains("FERN") ||
                type == Material.DEAD_BUSH ||
                type == Material.BAMBOO ||
                type == Material.CACTUS ||
                type == Material.SWEET_BERRY_BUSH) {
            return true;
        }

        // Bloques “no full cube” (evita nieve en cofres/vallas/trapdoors/etc sin mapearlos todos)
        // OJO: si quieres permitir nieve en cofres, quita esta línea.
        if (!type.isOccluding() && type != Material.SNOW) {
            return true;
        }

        // Fallback final (lo tuyo)
        return !type.isSolid();
    }


    // *** FIN DE LA NUEVA LÓGICA ***

    private void spawnWinterSnowAndIce() {
        final ThreadLocalRandom r = ThreadLocalRandom.current();

        int remainingGlobal = budget;
        if (remainingGlobal <= 0) return;

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;
        Collections.shuffle(players, ThreadLocalRandom.current());

        for (Player p : players) {
            if (remainingGlobal <= 0) break;

            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) continue;
            if (isFxDisabled(w)) continue;

            // --- CAMBIO AQUÍ: Verificar si está lloviendo/nevando en el mundo ---
            // Si el mundo no tiene tormenta activa, no procesamos nieve para este jugador.
            if (!w.hasStorm()) continue;

            boolean anyCold = isColdAround(w, p.getLocation(), Math.min(24, radius));

            // Si quieres ser aún más estricto y que solo caiga en biomas fríos:
            // if (!anyCold) continue;

            int    thisBudget    = remainingGlobal;
            int    thisRadius    = radius;
            double thisPlace      = placeChance;
            double thisAddLayer   = addLayerChance;

            // Aplicar multiplicadores de tormenta (ahora que sabemos que SI hay tormenta)
            if (stormBoostEnabled) {
                thisBudget    = (int) Math.ceil(thisBudget * stormBudgetMultiplier);
                thisRadius    = thisRadius + stormRadiusBonus;
                thisPlace     = clamp(thisPlace * stormPlaceMultiplier, 0.0, 1.0);
                thisAddLayer  = clamp(thisAddLayer * stormLayerMultiplier, 0.0, 1.0);
            }

            thisBudget = Math.min(thisBudget, remainingGlobal);

            for (int i = 0; i < thisBudget && remainingGlobal > 0; i++) {
                int dx = r.nextInt(-thisRadius, thisRadius + 1);
                int dz = r.nextInt(-thisRadius, thisRadius + 1);
                int x = p.getLocation().getBlockX() + dx;
                int z = p.getLocation().getBlockZ() + dz;

                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block highest = w.getBlockAt(x, y, z);

                // Congelar agua
                if (freezeWater && highest.getType() == Material.WATER) {
                    if (highest.getBlockData() instanceof Levelled lvl && lvl.getLevel() == 0) {
                        if (!WinterWorldGuardHelper.canModify(highest)) continue;
                        remainingGlobal--;
                        highest.setType(Material.ICE, false);
                        markIce(highest);

                        Block aboveIce = highest.getRelative(BlockFace.UP);
                        if (aboveIce.getType().isAir()) {
                            if (r.nextDouble() < thisPlace && WinterWorldGuardHelper.canSnowFall(aboveIce)) {
                                aboveIce.setType(Material.SNOW, false);
                                markSnow(aboveIce);
                            }
                        }
                    }
                    continue;
                }

                Block ground = highest;
                Block air = ground.getRelative(BlockFace.UP);

                if (!air.getType().isAir()) continue;
                if (shouldBlockSnow(ground)) continue;
                if (!WinterWorldGuardHelper.canModify(ground) || !WinterWorldGuardHelper.canModify(air)) continue;

                remainingGlobal--;

                if (r.nextDouble() < thisPlace) {
                    if (ground.getType() == Material.SNOW) {
                        Snow data = (Snow) ground.getBlockData();
                        if (r.nextDouble() < thisAddLayer && data.getLayers() < data.getMaximumLayers()) {
                            data.setLayers(data.getLayers() + 1);
                            ground.setBlockData(data, false);
                            markSnow(ground);
                        }
                    } else {
                        air.setType(Material.SNOW, false);
                        markSnow(air);
                    }
                }
            }
        }
    }


    private void prepareStartupMelt() {
        if (!startupMeltEnabled) return;

        CalendarState st = seasons.getStateCopy();
        if (st.season == Season.WINTER) return; // en invierno no hacemos catch-up

        startupQueue.clear();

        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.NORMAL) continue;
            // solo chunks cargados para no forzar carga
            startupQueue.addAll(Arrays.asList(w.getLoadedChunks()));
        }

        startupRunning = !startupQueue.isEmpty();
//        if (startupRunning) {
//            plugin.getLogger().info("[AeternumSeasons] StartupMelt queued "
//                    + startupQueue.size() + " loaded chunks.");
//        }
    }


    /* ===================== Autumn foliage ===================== */

    private void paintAutumnLeavesStep() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int remaining = autumnPaintBudgetPerTick;
        if (remaining <= 0) return;

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;
        Collections.shuffle(players, rnd);

        for (Player p : players) {
            if (remaining <= 0) break;
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) continue;
            if (isFxDisabled(w)) continue;

            int baseX = p.getLocation().getBlockX();
            int baseZ = p.getLocation().getBlockZ();

            int perPlayer = Math.min(remaining, 10);
            for (int i = 0; i < perPlayer && remaining > 0; i++) {
                int x = baseX + rnd.nextInt(-autumnRadiusBlocks, autumnRadiusBlocks + 1);
                int z = baseZ + rnd.nextInt(-autumnRadiusBlocks, autumnRadiusBlocks + 1);

                int topY = w.getHighestBlockYAt(x, z);
                int minY = Math.max(w.getMinHeight(), topY - 32);

                Block found = null;
                for (int y = topY; y >= minY; y--) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!isTargetLeaf(b.getType())) continue;

                    Biome biome = w.getBiome(x, y, z);
                    if (!isTaigaOrBirchBiome(biome)) continue;

                    found = b;
                    break;
                }

                if (found != null) {
                    paintLeafCluster(found);
                }

                remaining--;
            }
        }
    }

    private void paintLeafCluster(Block start) {
        World w = start.getWorld();
        Queue<Block> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        queue.add(start);
        visited.add(key(start));

        int maxNodes = 64;

        while (!queue.isEmpty() && maxNodes-- > 0) {
            Block b = queue.poll();
            Material type = b.getType();

            if (!isTargetLeaf(type) && type != Material.ACACIA_LEAVES) continue;

            String k = key(b);
            if (!paintedLeaves.containsKey(k)) {
                if (type != Material.ACACIA_LEAVES) {
                    paintedLeaves.put(k, type);
                }
                b.setType(Material.ACACIA_LEAVES, false);
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;

                        Block nb = w.getBlockAt(
                                b.getX() + dx,
                                b.getY() + dy,
                                b.getZ() + dz
                        );
                        String nk = key(nb);
                        if (visited.add(nk) && isTargetLeaf(nb.getType())) {
                            queue.add(nb);
                        }
                    }
                }
            }
        }
    }

    private boolean isTargetLeaf(Material m) {
        return m == Material.SPRUCE_LEAVES || m == Material.BIRCH_LEAVES;
    }

    private boolean isTaigaOrBirchBiome(Biome b) {
        String n = b.name();
        return n.contains("TAIGA") || n.contains("BIRCH");
    }

    private void revertLeavesStep() {
        int budget = autumnRevertBudgetPerTick;
        if (budget <= 0 || paintedLeaves.isEmpty()) return;

        Iterator<Map.Entry<String, Material>> it = paintedLeaves.entrySet().iterator();
        while (it.hasNext() && budget-- > 0) {
            Map.Entry<String, Material> e = it.next();
            String k = e.getKey();
            Material original = e.getValue();

            String[] s = k.split(";");
            World w = Bukkit.getWorld(s[0]);
            if (w == null) {
                it.remove();
                continue;
            }
            int x = Integer.parseInt(s[1]);
            int y = Integer.parseInt(s[2]);
            int z = Integer.parseInt(s[3]);
            Block b = w.getBlockAt(x, y, z);

            if (b.getType() == Material.ACACIA_LEAVES) {
                if (WinterWorldGuardHelper.canModify(b)) {
                    b.setType(original, false);
                }
            }
            it.remove();
        }
    }

    /* ===================== Melt fuera de invierno ===================== */

    private void meltAllStep() {
        int remaining = meltBudgetPerTick;
        if (remaining <= 0) return;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;
        Collections.shuffle(players, rnd);

        for (Player p : players) {
            if (remaining <= 0) break;

            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) continue;
            if (isFxDisabled(w)) continue;

            int px = p.getLocation().getBlockX();
            int pz = p.getLocation().getBlockZ();

            int rad = radius * 2;
            int colsPerPlayer = Math.min(remaining, 32);

            for (int i = 0; i < colsPerPlayer && remaining > 0; i++) {
                int x = px + rnd.nextInt(-rad, rad + 1);
                int z = pz + rnd.nextInt(-rad, rad + 1);

                int topY = w.getMaxHeight() - 1;
                int minY = w.getMinHeight();

                for (int y = topY; y >= minY && remaining > 0; y--) {
                    Block b = w.getBlockAt(x, y, z);
                    Material type = b.getType();

                    // ✅ CORREGIDO: Verificar si el bloque está en un bioma ORIGINALMENTE nevado
                    if (type == Material.SNOW || type == Material.SNOW_BLOCK ||
                            type == Material.ICE || type == Material.FROSTED_ICE) {

                        // ya tienes esto:
                        int chunkX = x >> 4;
                        int chunkZ = z >> 4;
                        if (BiomeSpoofAdapter.isChunkNaturallySnowy(w, chunkX, chunkZ)) {
                            continue;
                        }

                        // ✅ NUEVO: si fue puesta por jugador, no tocarla
                        String k = key(b);

                        // ✅ NUEVO: respetar lo del jugador
                        if ((type == Material.SNOW || type == Material.SNOW_BLOCK) && protectedSnow.contains(k)) {
                            continue;
                        }
                        if ((type == Material.ICE || type == Material.FROSTED_ICE) && protectedIce.contains(k)) {
                            continue;
                        }
                    }

                    if (type == Material.SNOW || type == Material.SNOW_BLOCK) {
                        if (!WinterWorldGuardHelper.canSnowMelt(b)) {
                            continue;
                        }
                        b.setType(Material.AIR, false);
                        clearSnowyBelow(b);
                        remaining--;
                        break;
                    } else if (meltAlsoIce &&
                            (type == Material.ICE || type == Material.FROSTED_ICE)) {
                        if (!WinterWorldGuardHelper.canSnowMelt(b)) {
                            continue;
                        }
                        b.setType(Material.WATER, false);
                        remaining--;
                        break;
                    } else {
                        BlockData data = b.getBlockData();
                        if (data instanceof Snowable snowData && snowData.isSnowy()) {
                            Block above = b.getRelative(0, 1, 0);
                            Material aboveType = above.getType();
                            if (aboveType != Material.SNOW && aboveType != Material.SNOW_BLOCK) {
                                if (!WinterWorldGuardHelper.canIceMelt(b)) {
                                    continue;
                                }
                                snowData.setSnowy(false);
                                b.setBlockData(snowData, false);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Método mejorado para verificar biomas nevados naturales */
    private boolean isNaturallySnowyBiome(World w, int x, int y, int z) {
        // Primero verificar si el chunk era originalmente nevado
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (BiomeSpoofAdapter.isChunkNaturallySnowy(w, chunkX, chunkZ)) {
            return true;
        }

        // Si no hay información del chunk, usar verificación por bioma actual como fallback
        Biome biome = w.getBiome(x, y, z);
        String name = biome.name().toUpperCase();

        return name.startsWith("SNOWY_") ||
                name.equals("ICE_SPIKES") ||
                name.equals("FROZEN_RIVER") ||
                name.contains("FROZEN_OCEAN") ||
                name.equals("FROZEN_PEAKS") ||
                name.equals("JAGGED_PEAKS") ||
                name.equals("GROVE");
    }

    // Este método lo puedes eliminar o mantener para otros usos
    private boolean isNaturallySnowyArea(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z);
        return isNaturallySnowyBiome(w, x, y, z);
    }

    /* ===================== helpers comunes ===================== */

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Material t = b.getType();

        if (t == Material.SNOW || t == Material.SNOW_BLOCK) {
            protectedSnow.add(key(b));
        } else if (t == Material.ICE) {
            protectedIce.add(key(b));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material t = b.getType();

        String k = key(b);
        if (t == Material.SNOW || t == Material.SNOW_BLOCK) {
            protectedSnow.remove(k);
        } else if (t == Material.ICE) {
            protectedIce.remove(k);
        }
    }

    private boolean isFxDisabled(World w) {
        return w != null && disabledFxWorlds.contains(w.getName().toLowerCase(Locale.ROOT));
    }

    private boolean isColdAround(World w, Location center, int rad) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 16; i++) {
            int x = center.getBlockX() + r.nextInt(-rad, rad + 1);
            int z = center.getBlockZ() + r.nextInt(-rad, rad + 1);
            if (isColdAt(w, x, z)) return true;
        }
        return false;
    }

    private boolean isColdAt(World w, int x, int z) {
        try {
            return w.getTemperature(x, z) <= 0.15D;
        } catch (Throwable ignored) {
            Biome b = w.getBiome(x, w.getHighestBlockYAt(x, z), z);
            String name = b.name();
            return name.contains("SNOW")
                    || name.contains("FROZEN")
                    || name.contains("GROVE")
                    || name.contains("TAIGA");
        }
    }

    private void markSnow(Block b) { paintedSnow.add(key(b)); }
    private void markIce(Block b)  { paintedIce.add(key(b)); }

    private String key(Block b) {
        return b.getWorld().getName() + ';' + b.getX() + ';' + b.getY() + ';' + b.getZ();
    }

    private void clearSnowyBelow(Block snowBlock) {
        Block below = snowBlock.getRelative(BlockFace.DOWN);
        if (below.getType().isSolid()) {
            BlockData data = below.getBlockData();
            if (data instanceof Snowable snowData && snowData.isSnowy()) {
                if (!WinterWorldGuardHelper.canModify(below)) {
                    return;
                }
                snowData.setSnowy(false);
                below.setBlockData(snowData, false);
            }
        }
    }

    private void clearAllPainted() {
        // nieve
        for (String k : new HashSet<>(paintedSnow)) {
            if (protectedSnow.contains(k)) continue; // ✅ NO borres lo del jugador

            String[] s = k.split(";");
            World w = Bukkit.getWorld(s[0]);
            if (w == null) continue;
            int x = Integer.parseInt(s[1]);
            int y = Integer.parseInt(s[2]);
            int z = Integer.parseInt(s[3]);
            Block b = w.getBlockAt(x, y, z);
            if (b.getType() == Material.SNOW || b.getType() == Material.SNOW_BLOCK) {
                if (WinterWorldGuardHelper.canIceMelt(b)) {
                    b.setType(Material.AIR, false);
                    clearSnowyBelow(b);
                }
            }
            if (!WinterWorldGuardHelper.canSnowMelt(b)) continue;
        }
        paintedSnow.clear();

        // hielo
        for (String k : new HashSet<>(paintedIce)) {
            if (protectedIce.contains(k)) continue; // ✅ NO borres lo del jugador

            String[] s = k.split(";");
            World w = Bukkit.getWorld(s[0]);
            if (w == null) continue;
            int x = Integer.parseInt(s[1]);
            int y = Integer.parseInt(s[2]);
            int z = Integer.parseInt(s[3]);
            Block b = w.getBlockAt(x, y, z);
            if (b.getType() == Material.ICE || b.getType() == Material.FROSTED_ICE) {
                if (WinterWorldGuardHelper.canIceMelt(b)) {
                    b.setType(Material.WATER, false);
                }
            }
        }
        paintedIce.clear();

        // hojas igual...
        for (Map.Entry<String, Material> e : new HashMap<>(paintedLeaves).entrySet()) {
            String k = e.getKey();
            Material original = e.getValue();
            String[] s = k.split(";");
            World w = Bukkit.getWorld(s[0]);
            if (w == null) continue;
            int x = Integer.parseInt(s[1]);
            int y = Integer.parseInt(s[2]);
            int z = Integer.parseInt(s[3]);
            Block b = w.getBlockAt(x, y, z);
            if (b.getType() == Material.ACACIA_LEAVES) {
                if (WinterWorldGuardHelper.canModify(b)) {
                    b.setType(original, false);
                }
            }
            // Agregado: para que la lógica de revertir no se rompa al final
            paintedLeaves.remove(k);
        }
    }
}
