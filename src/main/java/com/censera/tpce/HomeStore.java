package com.censera.tpce;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class HomeStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Map<String, Location>> homes = new LinkedHashMap<>();
    private final Map<UUID, String> primary = new LinkedHashMap<>();
    private int limit;

    HomeStore(JavaPlugin plugin, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Home limit must be at least 1");
        }
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "homes.yml");
        this.limit = limit;
    }

    void load() throws IOException {
        homes.clear();
        primary.clear();
        if (!file.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = config.getConfigurationSection("players");
        if (players == null) {
            return;
        }

        for (String playerKey : players.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(playerKey);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Ignoring invalid player UUID in homes.yml: " + playerKey);
                continue;
            }

            ConfigurationSection section = players.getConfigurationSection(playerKey);
            if (section == null) {
                continue;
            }

            Optional.ofNullable(section.getString("primary"))
                    .ifPresent(name -> primary.put(playerId, normalize(name)));

            ConfigurationSection playerHomes = section.getConfigurationSection("homes");
            if (playerHomes == null) {
                continue;
            }

            Map<String, Location> loaded = new LinkedHashMap<>();
            for (String name : playerHomes.getKeys(false)) {
                ConfigurationSection home = playerHomes.getConfigurationSection(name);
                if (home == null) {
                    continue;
                }
                Optional<String> worldId = Optional.ofNullable(home.getString("world"));
                if (worldId.isEmpty()) {
                    plugin.getLogger().warning("Ignoring home " + name + " for " + playerId + ": world is missing");
                    continue;
                }
                World world;
                try {
                    world = Bukkit.getWorld(UUID.fromString(worldId.get()));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Ignoring home " + name + " for " + playerId + ": invalid world");
                    continue;
                }
                if (world == null) {
                    plugin.getLogger().warning("Ignoring home " + name + " for " + playerId + ": world is unavailable");
                    continue;
                }
                loaded.put(normalize(name), new Location(
                        world,
                        home.getDouble("x"),
                        home.getDouble("y"),
                        home.getDouble("z"),
                        (float) home.getDouble("yaw"),
                        (float) home.getDouble("pitch")
                ));
            }
            if (!loaded.isEmpty()) {
                homes.put(playerId, loaded);
            }
        }
    }

    void save() throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Location>> player : homes.entrySet()) {
            String base = "players." + player.getKey();
            Optional.ofNullable(primary.get(player.getKey()))
                    .ifPresent(name -> config.set(base + ".primary", name));
            for (Map.Entry<String, Location> home : player.getValue().entrySet()) {
                String path = base + ".homes." + home.getKey();
                Location location = home.getValue();
                config.set(path + ".world", location.getWorld().getUID().toString());
                config.set(path + ".x", location.getX());
                config.set(path + ".y", location.getY());
                config.set(path + ".z", location.getZ());
                config.set(path + ".yaw", location.getYaw());
                config.set(path + ".pitch", location.getPitch());
            }
        }
        config.save(file);
    }

    void setLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Home limit must be at least 1");
        }
        this.limit = limit;
    }

    boolean set(UUID player, String name, Location location) {
        String key = normalize(name);
        Map<String, Location> playerHomes = homes.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
        if (!playerHomes.containsKey(key) && playerHomes.size() >= limit) {
            return false;
        }
        playerHomes.put(key, location.clone());
        primary.putIfAbsent(player, key);
        return true;
    }

    boolean delete(UUID player, String name) {
        Map<String, Location> playerHomes = homes.get(player);
        if (playerHomes == null) {
            return false;
        }
        String key = normalize(name);
        if (playerHomes.remove(key) == null) {
            return false;
        }
        if (key.equals(primary.get(player))) {
            primary.remove(player);
        }
        if (playerHomes.isEmpty()) {
            homes.remove(player);
            primary.remove(player);
        }
        return true;
    }

    boolean setPrimary(UUID player, String name) {
        String key = normalize(name);
        Map<String, Location> playerHomes = homes.get(player);
        if (playerHomes == null || !playerHomes.containsKey(key)) {
            return false;
        }
        primary.put(player, key);
        return true;
    }

    Optional<Location> get(UUID player, String name) {
        Map<String, Location> playerHomes = homes.get(player);
        if (playerHomes == null) {
            return Optional.empty();
        }
        Location location = playerHomes.get(normalize(name));
        return location == null ? Optional.empty() : Optional.of(location.clone());
    }

    Optional<Location> getPrimary(UUID player) {
        String name = primary.get(player);
        return name == null ? Optional.empty() : get(player, name);
    }

    java.util.List<String> names(UUID player) {
        Map<String, Location> playerHomes = homes.get(player);
        return playerHomes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(playerHomes.keySet()));
    }

    Optional<String> primaryName(UUID player) {
        return Optional.ofNullable(primary.get(player));
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase();
    }
}
