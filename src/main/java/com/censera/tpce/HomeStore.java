package com.censera.tpce;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

            ConfigurationSection playerSection = players.getConfigurationSection(playerKey);
            if (playerSection == null) {
                continue;
            }

            ConfigurationSection playerHomes = playerSection.getConfigurationSection("homes");
            if (playerHomes == null) {
                continue;
            }

            Map<String, Location> loaded = new LinkedHashMap<>();
            for (String rawName : playerHomes.getKeys(false)) {
                String name = normalize(rawName);
                ConfigurationSection home = playerHomes.getConfigurationSection(rawName);
                if (home == null) {
                    plugin.getLogger().warning("Ignoring home " + rawName + " for " + playerId + ": invalid entry");
                    continue;
                }

                String worldValue = home.getString("world");
                if (worldValue == null) {
                    plugin.getLogger().warning("Ignoring home " + rawName + " for " + playerId + ": world is missing");
                    continue;
                }

                World world;
                try {
                    world = Bukkit.getWorld(UUID.fromString(worldValue));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Ignoring home " + rawName + " for " + playerId + ": invalid world UUID");
                    continue;
                }
                if (world == null) {
                    plugin.getLogger().warning("Ignoring home " + rawName + " for " + playerId + ": world is unavailable");
                    continue;
                }

                loaded.put(name, new Location(
                        world,
                        home.getDouble("x"),
                        home.getDouble("y"),
                        home.getDouble("z"),
                        (float) home.getDouble("yaw"),
                        (float) home.getDouble("pitch")
                ));

                if (loaded.size() == limit) {
                    break;
                }
            }

            if (!loaded.isEmpty()) {
                homes.put(playerId, loaded);
            }

            String primaryName = playerSection.getString("primary");
            if (primaryName != null) {
                String normalized = normalize(primaryName);
                if (loaded.containsKey(normalized)) {
                    primary.put(playerId, normalized);
                } else {
                    plugin.getLogger().warning("Ignoring invalid primary home for " + playerId + ": " + primaryName);
                }
            }
        }
    }

    void save() throws IOException {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data directory: " + plugin.getDataFolder());
        }

        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Location>> player : homes.entrySet()) {
            String base = "players." + player.getKey();
            String primaryName = primary.get(player.getKey());
            if (primaryName != null) {
                config.set(base + ".primary", primaryName);
            }

            for (Map.Entry<String, Location> home : player.getValue().entrySet()) {
                Location location = home.getValue();
                World world = location.getWorld();
                if (world == null) {
                    throw new IOException("Home " + home.getKey() + " for " + player.getKey() + " has no world");
                }
                String path = base + ".homes." + home.getKey();
                config.set(path + ".world", world.getUID().toString());
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

    void set(UUID player, String name, Location location) throws IOException {
        String key = normalize(name);
        Map<String, Location> playerHomes = homes.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
        if (!playerHomes.containsKey(key) && playerHomes.size() >= limit) {
            throw new IOException("Home limit reached for player " + player);
        }

        Location previous = playerHomes.put(key, location.clone());
        if (primary.putIfAbsent(player, key) == null) {
            try {
                save();
            } catch (IOException e) {
                primary.remove(player);
                playerHomes.remove(key);
                if (previous != null) {
                    playerHomes.put(key, previous);
                }
                if (playerHomes.isEmpty()) {
                    homes.remove(player);
                }
                throw e;
            }
            return;
        }

        try {
            save();
        } catch (IOException e) {
            if (previous == null) {
                playerHomes.remove(key);
            } else {
                playerHomes.put(key, previous);
            }
            throw e;
        }
    }

    boolean delete(UUID player, String name) throws IOException {
        Map<String, Location> playerHomes = homes.get(player);
        if (playerHomes == null) {
            return false;
        }
        String key = normalize(name);
        Location removed = playerHomes.remove(key);
        if (removed == null) {
            return false;
        }

        String previousPrimary = primary.get(player);
        if (key.equals(previousPrimary)) {
            primary.remove(player);
        }
        if (playerHomes.isEmpty()) {
            homes.remove(player);
            primary.remove(player);
        }

        try {
            save();
        } catch (IOException e) {
            playerHomes.put(key, removed);
            if (previousPrimary != null) {
                primary.put(player, previousPrimary);
            }
            throw e;
        }
        return true;
    }

    boolean setPrimary(UUID player, String name) throws IOException {
        String key = normalize(name);
        Map<String, Location> playerHomes = homes.get(player);
        if (playerHomes == null || !playerHomes.containsKey(key)) {
            return false;
        }

        String previous = primary.put(player, key);
        try {
            save();
        } catch (IOException e) {
            if (previous == null) {
                primary.remove(player);
            } else {
                primary.put(player, previous);
            }
            throw e;
        }
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

    List<String> names(UUID player) {
        Map<String, Location> playerHomes = homes.get(player);
        return playerHomes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(playerHomes.keySet()));
    }

    Optional<String> primaryName(UUID player) {
        return Optional.ofNullable(primary.get(player));
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
