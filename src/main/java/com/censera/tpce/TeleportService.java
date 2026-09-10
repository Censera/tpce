package com.censera.tpce;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

final class TeleportService {
    private final JavaPlugin plugin;
    private final Supplier<Settings> settingsSupplier;

    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();
    private final Map<UUID, Location> backLocations = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    TeleportService(JavaPlugin plugin, Supplier<Settings> settingsSupplier) {
        this.plugin = plugin;
        this.settingsSupplier = settingsSupplier;
    }

    void begin(Player player, Location destination, String reason) {
        Settings settings = settingsSupplier.get();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldown = cooldownUntil.getOrDefault(id, 0L);
        if (cooldown > now) {
            long seconds = (cooldown - now + 999L) / 1000L;
            player.sendMessage(Component.text("You cannot teleport yet. Please wait " + seconds + " seconds."));
            return;
        }
        World world = destination.getWorld();
        if (world == null || Bukkit.getWorld(world.getUID()) == null) {
            player.sendMessage(Component.text("Teleport failed: destination world is unavailable."));
            plugin.getLogger().warning("Teleport rejected for " + player.getName() + ": unavailable world for " + reason + ".");
            return;
        }
        if (settings.requireSafeDestination() && !isSafe(destination)) {
            player.sendMessage(Component.text("That destination is not safe."));
            return;
        }
        cancel(player, false);
        Location source = player.getLocation().clone();
        Location target = destination.clone();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin,
                () -> completeTeleport(player), secondsToTicks(settings.teleportDelaySeconds()));
        pendingTeleports.put(id, new PendingTeleport(source, target, reason, task));
        player.sendMessage(Component.text("Teleporting in " + settings.teleportDelaySeconds() + " seconds..."));
    }

    private void completeTeleport(Player player) {
        // A cancelled BukkitTask never runs its callback, so whatever is still
        // pending for this player when the delay elapses is necessarily the
        // teleport this callback was scheduled for. No identity check needed.
        UUID id = player.getUniqueId();
        PendingTeleport pending = pendingTeleports.remove(id);
        if (pending == null) return;
        if (!player.isOnline()) return;
        Settings settings = settingsSupplier.get();
        if (settings.requireSafeDestination() && !isSafe(pending.destination())) {
            player.sendMessage(Component.text("Teleport failed: the destination is no longer safe."));
            return;
        }
        if (!player.teleport(pending.destination())) {
            player.sendMessage(Component.text("Teleport failed: the server rejected the teleport."));
            plugin.getLogger().warning("Teleport rejected for " + player.getName() + " to " + pending.reason() + ".");
            return;
        }
        backLocations.put(id, pending.source());
        if (settings.teleportCooldownSeconds() > 0) {
            cooldownUntil.put(id, System.currentTimeMillis() + settings.teleportCooldownSeconds() * 1000L);
        }
        player.sendMessage(Component.text("Teleported to " + pending.reason() + "."));
    }

    void cancel(Player player, boolean notify) {
        PendingTeleport pending = pendingTeleports.remove(player.getUniqueId());
        if (pending == null) return;
        pending.task().cancel();
        if (notify) player.sendMessage(Component.text("Teleport cancelled."));
    }

    Optional<Location> backLocation(UUID playerId) {
        return Optional.ofNullable(backLocations.get(playerId));
    }

    void onQuit(Player player) {
        cancel(player, false);
        backLocations.remove(player.getUniqueId());
        cooldownUntil.remove(player.getUniqueId());
    }

    void shutdown() {
        for (PendingTeleport pending : pendingTeleports.values()) {
            pending.task().cancel();
        }
        pendingTeleports.clear();
    }

    private static boolean isSafe(Location location) {
        World world = location.getWorld();
        if (world == null) return false;
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && ground.getType().isSolid();
    }

    private static long secondsToTicks(int seconds) {
        return seconds * 20L;
    }

    private record PendingTeleport(Location source, Location destination, String reason, BukkitTask task) { }
}
