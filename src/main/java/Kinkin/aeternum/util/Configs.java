package Kinkin.aeternum.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Configs {
    private final Plugin plugin;

    public FileConfiguration calendar, hud, climate, survival;

    public Configs(Plugin plugin) { this.plugin = plugin; }

    public void loadAll() {
        calendar = load("calendar.yml");
        hud      = load("hud.yml");
        climate  = load("climate.yml");
    }

    private FileConfiguration load(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            plugin.saveResource(name, false);
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);

        // --- EL SECRETO ESTÁ AQUÍ ---
        // Esto intenta copiar los comentarios del archivo que tienes dentro del JAR (resource)
        // hacia el archivo que está en la carpeta del plugin si faltan llaves.
        try (InputStreamReader reader = new InputStreamReader(plugin.getResource(name), StandardCharsets.UTF_8)) {
            YamlConfiguration defaultCfg = YamlConfiguration.loadConfiguration(reader);

            // Forzamos a que el archivo de salida use los comentarios del default
            cfg.setDefaults(defaultCfg);
            cfg.options().copyDefaults(true);
        } catch (Exception e) {
            // Manejar error si el recurso interno no existe
        }

        return cfg;
    }

    public void save(String name, FileConfiguration cfg) {
        try {
            // Configuramos para que intente separar visualmente las secciones
            cfg.options().copyDefaults(true);
            cfg.save(new File(plugin.getDataFolder(), name));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveAll() {
        save("calendar.yml", calendar);
        save("hud.yml", hud);
        save("climate.yml", climate);
    }
}
