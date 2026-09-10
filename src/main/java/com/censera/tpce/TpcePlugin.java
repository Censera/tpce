package com.censera.tpce;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TpcePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, Request> incoming = new HashMap<>();
    private final Map<UUID, Request> outgoing = new HashMap<>();
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();
    private final Map<UUID, BukkitTask> teleportTasks = new HashMap<>();
    private final Map<UUID, Location> backLocations = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    private HomeStore homes;
    private int requestExpirationSeconds;
    private int teleportDelaySeconds;
    private int teleportCooldownSeconds;
    private boolean requireSafeDestination;
    private boolean cancelOnMovement;
    private boolean cancelOnDamage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        homes = new HomeStore(this, getConfig().getInt("homes.limit"));
        try {
            homes.load();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load homes.yml", e);
        }

        for (String name : List.of("tpce", "tpr", "tpa", "tpd", "tpb", "bed", "home", "spawn")) {
            Command command = getCommand(name);
            if (command == null) {
                throw new IllegalStateException("Required command is missing from plugin.yml: " + name);
            }
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("tpce enabled.");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : teleportTasks.values()) {
            task.cancel();
        }
        for (Request request : incoming.values()) {
            request.expirationTask().cancel();
        }
        try {
            homes.save();
        } catch (IOException e) {
            getLogger().severe("Failed to save homes.yml: " + e.getMessage());
        }
    }

    private void loadConfiguration() {
        requestExpirationSeconds = requiredPositiveInt("request-expiration");
        teleportDelaySeconds = requiredNonNegativeInt("teleport-delay");
        teleportCooldownSeconds = requiredNonNegativeInt("teleport-cooldown");

        ConfigurationSection homeSection = getConfig().getConfigurationSection("homes");
        if (homeSection == null) {
            throw new IllegalStateException("Missing configuration section: homes");
        }
        int homeLimit = homeSection.getInt("limit", 0);
        if (homeLimit < 1) {
            throw new IllegalStateException("Invalid configuration homes.limit: expected at least 1");
        }

        ConfigurationSection safety = getConfig().getConfigurationSection("safety");
        if (safety == null) {
            throw new IllegalStateException("Missing configuration section: safety");
        }
        requireSafeDestination = requireBoolean(safety, "require-safe-destination");
        cancelOnMovement = requireBoolean(safety, "cancel-on-movement");
        cancelOnDamage = requireBoolean(safety, "cancel-on-damage");
    }

    private int requiredPositiveInt(String path) {
        int value = getConfig().getInt(path, 0);
        if (value < 1) {
            throw new IllegalStateException("Invalid configuration " + path + ": expected at least 1");
        }
        return value;
    }

    private int requiredNonNegativeInt(String path) {
        int value = getConfig().getInt(path, -1);
        if (value < 0) {
            throw new IllegalStateException("Invalid configuration " + path + ": expected 0 or greater");
        }
        return value;
    }

    private boolean requireBoolean(ConfigurationSection section, String path) {
        if (!section.isBoolean(path)) {
            throw new IllegalStateException("Invalid configuration safety." + path + ": expected true or false");
        }
        return section.getBoolean(path);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is only available to players.");
            return true;
        }

        return switch (command.getName().toLowerCase()) {
            case "tpce" -> handleTpce(player, args);
            case "tpr" -> handleRequest(player, args);
            case "tpa" -> acceptRequest(player);
            case "tpd" -> declineRequest(player);
            case "tpb" -> teleportBack(player);
            case "bed" -> teleportBed(player);
            case "home" -> handleHome(player, args);
            case "spawn" -> teleportSpawn(player);
            default -> false;
        };
    }

    private boolean handleTpce(Player player, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadConfiguration();
            homes.setLimit(getConfig().getInt("homes.limit"));
            player.sendMessage(Component.text("tpce configuration reloaded."));
            return true;
        }
        player.sendMessage(Component.text("Usage: /tpce reload"));
        return true;
    }

    private boolean handleRequest(Player player, String[] args) {
        if (args.length > 1) {
            player.sendMessage(Component.text("Usage: /tpr [player]"));
            return true;
        }
        if (args.length == 1) {
            Optional<Player> target = Optional.ofNullable(Bukkit.getPlayerExact(args[0]));
            if (target.isEmpty()) {
                player.sendMessage(Component.text("Player is not online: " + args[0]));
                return true;
            }
            sendRequest(player, target.get());
            return true;
        }

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.remove(player);
        if (players.isEmpty()) {
            player.sendMessage(Component.text("No other players are online."));
            return true;
        }

        player.sendMessage(Component.text("Who do you want to teleport to?"));
        for (Player target : players) {
            player.sendMessage(button(target.getName(), "/tpr " + target.getName()));
        }
        return true;
    }

    private void sendRequest(Player requester, Player target) {
        if (requester.equals(target)) {
            requester.sendMessage(Component.text("You cannot teleport to yourself."));
            return;
        }
        if (outgoing.containsKey(requester.getUniqueId())) {
            cancelRequest(requester.getUniqueId(), "Your previous teleport request was cancelled.");
        }
        if (incoming.containsKey(target.getUniqueId())) {
            requester.sendMessage(Component.text(target.getName() + " already has a pending teleport request."));
            return;
        }

        UUID requesterId = requester.getUniqueId();
        UUID targetId = target.getUniqueId();
        BukkitTask expiration = Bukkit.getScheduler().runTaskLater(
                this, () -> expireRequest(requesterId), requestExpirationSeconds * 20L);
        Request request = new Request(requesterId, targetId, expiration);
        outgoing.put(requesterId, request);
        incoming.put(targetId, request);

        requester.sendMessage(Component.text("Teleport request sent to " + target.getName() + "."));
        target.sendMessage(Component.text(requester.getName() + " wants to teleport to you."));
        target.sendMessage(buttons(
                button("[ Accept ]", "/tpa"),
                button("[ Decline ]", "/tpd")
        ));
    }

    private boolean acceptRequest(Player target) {
        Request request = incoming.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(Component.text("You have no pending teleport request."));
            return true;
        }
        outgoing.remove(request.requester(), request);
        request.expirationTask().cancel();

        Optional<Player> requester = Optional.ofNullable(Bukkit.getPlayer(request.requester()));
        if (requester.isEmpty()) {
            target.sendMessage(Component.text("The requester is no longer online."));
            return true;
        }

        target.sendMessage(Component.text("Teleport request accepted."));
        requester.get().sendMessage(Component.text(target.getName() + " accepted your teleport request."));
        beginTeleport(requester.get(), target.getLocation(), target.getName());
        return true;
    }

    private boolean declineRequest(Player target) {
        Request request = incoming.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(Component.text("You have no pending teleport request."));
            return true;
        }
        outgoing.remove(request.requester(), request);
        request.expirationTask().cancel();
        Optional<Player> requester = Optional.ofNullable(Bukkit.getPlayer(request.requester()));
        target.sendMessage(Component.text("Teleport request declined."));
        requester.ifPresent(value -> value.sendMessage(Component.text(target.getName() + " declined your teleport request.")));
        return true;
    }

    private void expireRequest(UUID requesterId) {
        Request request = outgoing.remove(requesterId);
        if (request == null) {
            return;
        }
        incoming.remove(request.target(), request);
        Optional.ofNullable(Bukkit.getPlayer(requesterId))
                .ifPresent(player -> player.sendMessage(Component.text("Teleport request expired.")));
        Optional.ofNullable(Bukkit.getPlayer(request.target()))
                .ifPresent(player -> player.sendMessage(Component.text("Teleport request expired.")));
    }

    private void cancelRequest(UUID requesterId, String message) {
        Request request = outgoing.remove(requesterId);
        if (request == null) {
            return;
        }
        incoming.remove(request.target(), request);
        request.expirationTask().cancel();
        Optional.ofNullable(Bukkit.getPlayer(requesterId))
                .ifPresent(player -> player.sendMessage(Component.text(message)));
    }

    private boolean handleHome(Player player, String[] args) {
        if (args.length == 0) {
            Optional<Location> primary = homes.getPrimary(player.getUniqueId());
            if (primary.isPresent()) {
                beginTeleport(player, primary.get(), "home");
                return true;
            }
            showHomes(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("set")) {
            if (args.length < 2 || args.length > 3) {
                player.sendMessage(Component.text("Usage: /home set <name> [primary]"));
                return true;
            }
            String name = args[1].trim();
            if (name.isEmpty() || name.length() > 32 || name.contains(" ")) {
                player.sendMessage(Component.text("Home name must be 1-32 characters without spaces."));
                return true;
            }
            if (!homes.set(player.getUniqueId(), name, player.getLocation())) {
                player.sendMessage(Component.text("You have reached the maximum number of homes."));
                return true;
            }
            if (args.length == 3 && args[2].equalsIgnoreCase("primary")) {
                homes.setPrimary(player.getUniqueId(), name);
            }
            saveHomes(player);
            player.sendMessage(Component.text("Home saved: " + name + "."));
            return true;
        }
        if (args.length == 1) {
            String name = args[0];
            Optional<Location> home = homes.get(player.getUniqueId(), name);
            if (home.isEmpty()) {
                player.sendMessage(Component.text("Home does not exist: " + name));
                showHomes(player);
                return true;
            }
            beginTeleport(player, home.get(), "home " + name);
            return true;
        }
        player.sendMessage(Component.text("Usage: /home [name|set <name> [primary]]"));
        return true;
    }

    private void showHomes(Player player) {
        List<String> names = homes.names(player.getUniqueId());
        if (names.isEmpty()) {
            player.sendMessage(Component.text("You have no homes. Use /home set <name> to create one."));
            return;
        }
        player.sendMessage(Component.text("Homes:"));
        for (String name : names) {
            player.sendMessage(button(name, "/home " + name));
        }
        homes.primaryName(player.getUniqueId()).ifPresent(primary ->
                player.sendMessage(Component.text("Primary home: " + primary)));
    }

    private boolean teleportBack(Player player) {
        Location destination = backLocations.get(player.getUniqueId());
        if (destination == null) {
            player.sendMessage(Component.text("No previous location is available."));
            return true;
        }
        beginTeleport(player, destination, "previous location");
        return true;
    }

    private boolean teleportBed(Player player) {
        Optional<Location> destination = Optional.ofNullable(player.getRespawnLocation());
        if (destination.isEmpty()) {
            player.sendMessage(Component.text("You do not have a valid bed location."));
            return true;
        }
        beginTeleport(player, destination.get(), "bed");
        return true;
    }

    private boolean teleportSpawn(Player player) {
        beginTeleport(player, player.getWorld().getSpawnLocation(), "spawn");
        return true;
    }

    private void beginTeleport(Player player, Location destination, String reason) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldown = cooldownUntil.getOrDefault(id, 0L);
        if (cooldown > now) {
            long seconds = (cooldown - now + 999L) / 1000L;
            player.sendMessage(Component.text("You cannot teleport yet. Please wait " + seconds + " seconds."));
            return;
        }

        Optional<World> world = Optional.ofNullable(destination.getWorld());
        if (world.isEmpty() || !Bukkit.getWorlds().contains(world.get())) {
            player.sendMessage(Component.text("Teleport failed: destination world is unavailable."));
            return;
        }
        if (requireSafeDestination && !isSafe(destination)) {
            player.sendMessage(Component.text("That destination is not safe."));
            return;
        }

        cancelTeleport(player, false);
        Location source = player.getLocation().clone();
        PendingTeleport pending = new PendingTeleport(source, destination.clone(), reason);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                this, () -> completeTeleport(player, pending), teleportDelaySeconds * 20L);
        pendingTeleports.put(id, pending);
        teleportTasks.put(id, task);
        player.sendMessage(Component.text("Teleporting in " + teleportDelaySeconds + " seconds..."));
    }

    private void completeTeleport(Player player, PendingTeleport pending) {
        UUID id = player.getUniqueId();
        if (pendingTeleports.get(id) != pending) {
            return;
        }
        pendingTeleports.remove(id);
        teleportTasks.remove(id);
        if (!player.isOnline()) {
            return;
        }
        if (requireSafeDestination && !isSafe(pending.destination())) {
            player.sendMessage(Component.text("Teleport failed: the destination is no longer safe."));
            return;
        }
        if (!player.teleport(pending.destination())) {
            player.sendMessage(Component.text("Teleport failed: the server rejected the teleport."));
            getLogger().warning("Teleport rejected for " + player.getName() + " to " + pending.reason());
            return;
        }
        backLocations.put(id, pending.source());
        cooldownUntil.put(id, System.currentTimeMillis() + teleportCooldownSeconds * 1000L);
        player.sendMessage(Component.text("Teleported to " + pending.reason() + "."));
    }

    private void cancelTeleport(Player player, boolean notify) {
        UUID id = player.getUniqueId();
        pendingTeleports.remove(id);
        Optional.ofNullable(teleportTasks.remove(id)).ifPresent(BukkitTask::cancel);
        if (notify) {
            player.sendMessage(Component.text("Teleport cancelled."));
        }
    }

    private boolean isSafe(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && ground.getType().isSolid();
    }

    private void saveHomes(Player player) {
        try {
            homes.save();
        } catch (IOException e) {
            player.sendMessage(Component.text("Home saved in memory, but persistent storage failed."));
            getLogger().severe("Failed to save homes.yml after change by " + player.getName() + ": " + e.getMessage());
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!cancelOnMovement || event.getTo() == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        if (pendingTeleports.containsKey(event.getPlayer().getUniqueId())) {
            cancelTeleport(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !cancelOnDamage) {
            return;
        }
        if (pendingTeleports.containsKey(player.getUniqueId())) {
            cancelTeleport(player, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancelTeleport(event.getPlayer(), false);

        Request incomingRequest = incoming.remove(id);
        if (incomingRequest != null) {
            outgoing.remove(incomingRequest.requester(), incomingRequest);
            incomingRequest.expirationTask().cancel();
            Optional.ofNullable(Bukkit.getPlayer(incomingRequest.requester()))
                    .ifPresent(player -> player.sendMessage(Component.text("Teleport request cancelled because the target left.")));
        }
        cancelRequest(id, "Teleport request cancelled because you left the server.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        String name = command.getName().toLowerCase();
        if (name.equals("tpce")) {
            return args.length == 1 ? List.of("reload") : List.of();
        }
        if (name.equals("tpr") && args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .filter(other -> !other.equals(player))
                    .map(Player::getName)
                    .filter(other -> other.toLowerCase().startsWith(prefix))
                    .sorted()
                    .toList();
        }
        if (name.equals("home")) {
            if (args.length == 1) {
                List<String> result = new ArrayList<>(homes.names(player.getUniqueId()));
                result.add("set");
                return result.stream().filter(value -> value.startsWith(args[0].toLowerCase())).toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                return List.of("primary");
            }
        }
        return List.of();
    }

    private static Component button(String label, String command) {
        return Component.text(label).clickEvent(ClickEvent.runCommand(command));
    }

    private static Component buttons(Component first, Component second) {
        return first.append(Component.text(" ")).append(second);
    }

    private record Request(UUID requester, UUID target, BukkitTask expirationTask) { }

    private record PendingTeleport(Location source, Location destination, String reason) { }
}
