package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WinterWorldGuardHelper {

    private static boolean hooked = false;
    private static boolean respectRegions = true;

    // WorldGuard/WorldEdit (reflection)
    private static Object query;                     // RegionQuery instance
    private static Method adaptMethod;               // BukkitAdapter.adapt(Location)
    private static Method testStateMethod;           // RegionQuery#testState(...)
    private static final Map<String, Object> FLAGS = new HashMap<>();

    private WinterWorldGuardHelper() {}

    /** Llama esto en onEnable y también cuando recargues config/climate si aplica */
    public static void reload(AeternumSeasonsPlugin plugin) {
        // Puedes ajustar la ruta si tu config usa otra key
        respectRegions = plugin.getConfig().getBoolean("worldguard-respect-regions", true);

        // Si no queremos respetar regiones, no hookeamos
        if (!respectRegions) {
            hooked = false;
            query = null;
            FLAGS.clear();
            return;
        }

        // Si WG no está instalado, salimos limpio (sin errores)
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            hooked = false;
            query = null;
            FLAGS.clear();
            return;
        }

        try {
            // WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Method getInstance = wgClass.getMethod("getInstance");
            Object wg = getInstance.invoke(null);

            Method getPlatform = wg.getClass().getMethod("getPlatform");
            Object platform = getPlatform.invoke(wg);

            Method getRegionContainer = platform.getClass().getMethod("getRegionContainer");
            Object container = getRegionContainer.invoke(platform);

            Method createQuery = container.getClass().getMethod("createQuery");
            query = createQuery.invoke(container);

            // BukkitAdapter.adapt(org.bukkit.Location)
            Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            adaptMethod = bukkitAdapter.getMethod("adapt", Location.class);

            // Encontrar RegionQuery#testState(...) sin casarnos con tipos exactos
            testStateMethod = null;
            for (Method m : query.getClass().getMethods()) {
                if (!m.getName().equals("testState")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 3) continue;
                // Queremos (weLoc, player/null, flag)
                // El último debe ser StateFlag (o algo compatible)
                if (p[2].getName().equals("com.sk89q.worldguard.protection.flags.StateFlag")
                        || p[2].getSuperclass() != null && p[2].getSuperclass().getName().equals("com.sk89q.worldguard.protection.flags.StateFlag")) {
                    testStateMethod = m;
                    break;
                }
            }

            // Cargar Flags.* que usas
            FLAGS.clear();
            loadFlag("BUILD");
            loadFlag("SNOW_FALL");
            loadFlag("ICE_FORM");
            loadFlag("ICE_MELT");

            hooked = (query != null && adaptMethod != null && testStateMethod != null);

            if (hooked) {
                plugin.getLogger().info("[WG] Hook OK (respectRegions=true)");
            } else {
                plugin.getLogger().warning("[WG] Hook parcial: no pude resolver métodos/flags. Se ignorarán regiones.");
            }

        } catch (Throwable t) {
            hooked = false;
            query = null;
            FLAGS.clear();
            plugin.getLogger().warning("[WG] No pude hookear WorldGuard, ignorando regiones: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void loadFlag(String name) {
        try {
            Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
            Field f = flagsClass.getField(name);
            Object flag = f.get(null);
            if (flag != null) FLAGS.put(name.toUpperCase(Locale.ROOT), flag);
        } catch (Throwable ignored) {
            // Si no existe ese flag en la versión de WG, lo dejamos sin cargar.
        }
    }

    private static boolean test(Block block, String flagName) {
        if (!hooked || !respectRegions) return true;
        if (query == null || block == null) return true;

        Object flag = FLAGS.get(flagName.toUpperCase(Locale.ROOT));
        if (flag == null) return true; // si no existe el flag, no bloqueamos

        Location loc = block.getLocation();
        if (loc == null || loc.getWorld() == null) return true;

        try {
            Object weLoc = adaptMethod.invoke(null, loc);
            // null = acción ambiental (sin jugador)
            Object out = testStateMethod.invoke(query, weLoc, null, flag);
            return (out instanceof Boolean) ? (Boolean) out : true;
        } catch (Throwable t) {
            return true; // ante cualquier fallo, no bloqueamos
        }
    }

    public static boolean canSnowFall(Block block) {
        return test(block, "SNOW_FALL");
    }

    public static boolean canIceForm(Block block) {
        return test(block, "ICE_FORM");
    }

    public static boolean canIceMelt(Block block) {
        return test(block, "ICE_MELT");
    }

    // Compat con tu código viejo
    public static boolean canModify(Block block) {
        return test(block, "BUILD");
    }
    // --- Compat con tu código actual ---
    public static void init(AeternumSeasonsPlugin plugin) {
        reload(plugin);
    }

    public static boolean canSnowMelt(org.bukkit.block.Block block) {
        // "Snow melt" en WG no existe como flag oficial separado;
        // en tu plugin lo estabas usando como permiso ambiental.
        // Si quieres que nieve se derrita incluso en regiones protegidas,
        // aquí puedes usar BUILD o ICE_MELT dependiendo tu intención.
        // Yo lo dejo usando BUILD para "puede modificar el mundo".
        return canModify(block);
    }

}
