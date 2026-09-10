package com.censera.tpce;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class TpcePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final int PLAYER_PAGE_SIZE = 6;

    private HomeStore homes;
    private RequestManager requests;
    private TeleportService teleports;
    private Settings settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = readSettings();
        homes = new HomeStore(this, settings.homeLimit());
        try {
            homes.load();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load homes.yml", e);
        }
        teleports = new TeleportService(this, () -> settings);
        requests = new RequestManager(this, () -> settings.requestExpirationSeconds(), this::notifyExpired);
        registerCommands();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("tpce enabled with " + settings.homeLimit() + " home slots per player.");
    }

    @Override
    public void onDisable() {
        if (teleports != null) teleports.shutdown();
        if (requests != null) requests.shutdown();
        if (homes != null) {
            try {
                homes.save();
            } catch (IOException e) {
                getLogger().severe("Failed to save homes.yml while disabling: " + e.getMessage());
            }
        }
    }

    private void notifyExpired(UUID requesterId, UUID targetId) {
        Player requester = Bukkit.getPlayer(requesterId);
        if (requester != null) requester.sendMessage(info("Teleport request expired."));
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) target.sendMessage(info("Teleport request expired."));
    }

    private void registerCommands() {
        for (String name : List.of("tpce", "tpr", "tpa", "tpd", "tpb", "bed", "home", "spawn")) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                throw new IllegalStateException("Required command is missing from plugin.yml: " + name);
            }
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    private Settings readSettings() {
        int requestExpiration = requiredPositiveInt("request-expiration");
        int teleportDelay = requiredNonNegativeInt("teleport-delay");
        int teleportCooldown = requiredNonNegativeInt("teleport-cooldown");
        ConfigurationSection homesSection = getConfig().getConfigurationSection("homes");
        if (homesSection == null) {
            throw new IllegalStateException("Missing configuration section: homes");
        }
        int homeLimit = homesSection.getInt("limit", -1);
        if (homeLimit < 1) {
            throw new IllegalStateException("Invalid configuration homes.limit: expected at least 1");
        }
        ConfigurationSection safety = getConfig().getConfigurationSection("safety");
        if (safety == null) {
            throw new IllegalStateException("Missing configuration section: safety");
        }
        return new Settings(requestExpiration, teleportDelay, teleportCooldown, homeLimit,
                requiredBoolean(safety, "require-safe-destination"),
                requiredBoolean(safety, "cancel-on-movement"),
                requiredBoolean(safety, "cancel-on-damage"));
    }

    private int requiredPositiveInt(String path) {
        int value = getConfig().getInt(path, -1);
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

    private boolean requiredBoolean(ConfigurationSection section, String path) {
        if (!section.isBoolean(path)) {
            throw new IllegalStateException("Invalid configuration safety." + path + ": expected true or false");
        }
        return section.getBoolean(path);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("tpce") && args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("tpce.reload")) {
                sender.sendMessage(error("You do not have permission to reload tpce."));
                return true;
            }
            reloadTpce(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(error("This command is only available to players."));
            return true;
        }
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "tpce" -> showMenu(player);
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

    private void reloadTpce(CommandSender sender) {
        reloadConfig();
        try {
            Settings newSettings = readSettings();
            homes.setLimit(newSettings.homeLimit());
            settings = newSettings;
            sender.sendMessage(success("tpce configuration reloaded."));
            getLogger().info("Configuration reloaded by " + sender.getName() + ".");
        } catch (IllegalStateException e) {
            sender.sendMessage(error("Configuration reload failed: " + e.getMessage()));
            getLogger().warning("Configuration reload rejected: " + e.getMessage());
        }
    }

    private boolean showMenu(Player player) {
        player.sendMessage(Component.text("tpce", NamedTextColor.DARK_AQUA));
        sendButtonLine(player, button("[ Request teleport ]", "/tpr", NamedTextColor.GREEN),
                button("[ Homes ]", "/home list", NamedTextColor.YELLOW));
        sendButtonLine(player, button("[ Bed ]", "/bed", NamedTextColor.GREEN),
                button("[ Spawn ]", "/spawn", NamedTextColor.GREEN),
                button("[ Back ]", "/tpb", NamedTextColor.GOLD));
        Optional<UUID> requesterId = requests.incomingRequester(player.getUniqueId());
        if (requesterId.isPresent()) {
            Player requester = Bukkit.getPlayer(requesterId.get());
            if (requester != null) {
                player.sendMessage(info("Pending request from " + requester.getName() + ":"));
                sendButtonLine(player, button("[ Accept ]", "/tpa", NamedTextColor.GREEN),
                        button("[ Decline ]", "/tpd", NamedTextColor.RED));
            }
        }
        if (requests.hasOutgoing(player.getUniqueId())) {
            sendButtonLine(player, button("[ Cancel request ]", "/tpr cancel", NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleRequest(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            showPlayerPage(player, 0);
            return true;
        }
        if (args[0].equalsIgnoreCase("cancel") && args.length == 1) {
            if (requests.cancelOutgoing(player.getUniqueId())) {
                player.sendMessage(success("Teleport request cancelled."));
            } else {
                player.sendMessage(error("You have no outgoing teleport request."));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("page")) {
            if (args.length != 2) {
                player.sendMessage(usage("/tpr page <number>"));
                return true;
            }
            try {
                int page = Integer.parseInt(args[1]);
                if (page < 1) throw new NumberFormatException();
                showPlayerPage(player, page - 1);
            } catch (NumberFormatException e) {
                player.sendMessage(error("Page must be a positive number."));
            }
            return true;
        }
        if (args.length == 1) {
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                player.sendMessage(error("Player is not online: " + args[0]));
                return true;
            }
            sendRequest(player, target);
            return true;
        }
        player.sendMessage(usage("/tpr [player|cancel|page <number>]"));
        return true;
    }

    private void showPlayerPage(Player player, int page) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.remove(player);
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        if (players.isEmpty()) {
            player.sendMessage(info("No other players are online."));
            return;
        }
        int pageCount = (players.size() + PLAYER_PAGE_SIZE - 1) / PLAYER_PAGE_SIZE;
        if (page >= pageCount) page = pageCount - 1;
        int start = page * PLAYER_PAGE_SIZE;
        int end = Math.min(start + PLAYER_PAGE_SIZE, players.size());
        player.sendMessage(info("Who do you want to teleport to? Page " + (page + 1) + "/" + pageCount));
        for (int index = start; index < end; index++) {
            Player target = players.get(index);
            player.sendMessage(button(target.getName(), "/tpr " + target.getName(), NamedTextColor.GREEN));
        }
        List<Component> navigation = new ArrayList<>();
        if (page > 0) navigation.add(button("[ Previous ]", "/tpr page " + page, NamedTextColor.YELLOW));
        if (page + 1 < pageCount) navigation.add(button("[ Next ]", "/tpr page " + (page + 2), NamedTextColor.YELLOW));
        if (!navigation.isEmpty()) sendButtonLine(player, navigation.toArray(Component[]::new));
    }

    private void sendRequest(Player requester, Player target) {
        RequestManager.SendOutcome outcome = requests.send(requester.getUniqueId(), target.getUniqueId());
        switch (outcome) {
            case SELF -> requester.sendMessage(error("You cannot teleport to yourself."));
            case TARGET_HAS_OTHER_REQUEST ->
                    requester.sendMessage(error(target.getName() + " already has a pending teleport request."));
            case ALREADY_PENDING_FOR_TARGET ->
                    requester.sendMessage(error("You already have a request pending for " + target.getName() + "."));
            case SENT -> {
                requester.sendMessage(success("Teleport request sent to " + target.getName() + "."));
                target.sendMessage(info(requester.getName() + " wants to teleport to you."));
                sendButtonLine(target, button("[ Accept ]", "/tpa", NamedTextColor.GREEN),
                        button("[ Decline ]", "/tpd", NamedTextColor.RED));
            }
        }
    }

    private boolean acceptRequest(Player target) {
        Optional<UUID> requesterId = requests.accept(target.getUniqueId());
        if (requesterId.isEmpty()) {
            target.sendMessage(error("You have no pending teleport request."));
            return true;
        }
        Player requester = Bukkit.getPlayer(requesterId.get());
        if (requester == null) {
            target.sendMessage(error("The requester is no longer online."));
            return true;
        }
        target.sendMessage(success("Teleport request accepted."));
        requester.sendMessage(success(target.getName() + " accepted your teleport request."));
        teleports.begin(requester, target.getLocation(), target.getName());
        return true;
    }

    private boolean declineRequest(Player target) {
        Optional<UUID> requesterId = requests.decline(target.getUniqueId());
        if (requesterId.isEmpty()) {
            target.sendMessage(error("You have no pending teleport request."));
            return true;
        }
        target.sendMessage(success("Teleport request declined."));
        Player requester = Bukkit.getPlayer(requesterId.get());
        if (requester != null) requester.sendMessage(info(target.getName() + " declined your teleport request."));
        return true;
    }

    private boolean handleHome(Player player, String[] args) {
        if (args.length == 0) {
            var primary = homes.getPrimary(player.getUniqueId());
            if (primary.isPresent()) {
                teleports.begin(player, primary.get(), "primary home");
            } else {
                showHomes(player);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("list") && args.length == 1) {
            showHomes(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("set")) {
            if (args.length < 2 || args.length > 3) {
                player.sendMessage(usage("/home set <name> [primary]"));
                return true;
            }
            String name = args[1].trim();
            if (!isValidHomeName(name)) {
                player.sendMessage(error("Home name must be 1-32 characters using letters, numbers, '-' or '_'."));
                return true;
            }
            boolean makePrimary = args.length == 3;
            if (makePrimary && !args[2].equalsIgnoreCase("primary")) {
                player.sendMessage(usage("/home set <name> [primary]"));
                return true;
            }
            try {
                homes.set(player.getUniqueId(), name, player.getLocation());
                if (makePrimary) homes.setPrimary(player.getUniqueId(), name);
            } catch (IOException e) {
                player.sendMessage(error("Home save failed. Your home was not changed."));
                getLogger().severe("Failed to save home " + name + " for " + player.getName() + ": " + e.getMessage());
                return true;
            }
            player.sendMessage(success("Home saved: " + name + "."));
            return true;
        }
        if (args[0].equalsIgnoreCase("delete") && args.length == 2) {
            try {
                if (!homes.delete(player.getUniqueId(), args[1])) {
                    player.sendMessage(error("Home does not exist: " + args[1]));
                    return true;
                }
            } catch (IOException e) {
                player.sendMessage(error("Home delete failed. Your home was not changed."));
                getLogger().severe("Failed to delete home " + args[1] + " for " + player.getName() + ": " + e.getMessage());
                return true;
            }
            player.sendMessage(success("Home deleted: " + args[1] + "."));
            return true;
        }
        if (args[0].equalsIgnoreCase("primary") && args.length == 2) {
            try {
                if (!homes.setPrimary(player.getUniqueId(), args[1])) {
                    player.sendMessage(error("Home does not exist: " + args[1]));
                    return true;
                }
            } catch (IOException e) {
                player.sendMessage(error("Primary home update failed. Your home was not changed."));
                getLogger().severe("Failed to set primary home " + args[1] + " for " + player.getName() + ": " + e.getMessage());
                return true;
            }
            player.sendMessage(success("Primary home set: " + args[1] + "."));
            return true;
        }
        if (args.length == 1) {
            var home = homes.get(player.getUniqueId(), args[0]);
            if (home.isEmpty()) {
                player.sendMessage(error("Home does not exist: " + args[0]));
                showHomes(player);
                return true;
            }
            teleports.begin(player, home.get(), "home " + args[0]);
            return true;
        }
        player.sendMessage(usage("/home [name|list|set <name> [primary]|delete <name>|primary <name>]"));
        return true;
    }

    private void showHomes(Player player) {
        List<String> names = homes.names(player.getUniqueId());
        if (names.isEmpty()) {
            player.sendMessage(info("You have no home; try to set a home somewhere."));
            sendButtonLine(player, button("[ Set home-1 here ]", "/home set home-1", NamedTextColor.GREEN));
            return;
        }
        player.sendMessage(Component.text("Homes:", NamedTextColor.DARK_AQUA));
        String primary = homes.primaryName(player.getUniqueId()).orElse("");
        for (String name : names) {
            Component line = button(name + (name.equals(primary) ? " (primary)" : ""), "/home " + name, NamedTextColor.GREEN)
                    .append(Component.text(" "))
                    .append(button("[ Primary ]", "/home primary " + name, NamedTextColor.GOLD))
                    .append(Component.text(" "))
                    .append(button("[ Delete ]", "/home delete " + name, NamedTextColor.RED));
            player.sendMessage(line);
        }
        for (int slot = 1; slot <= settings.homeLimit(); slot++) {
            String defaultName = "home-" + slot;
            if (!names.contains(defaultName) && names.size() < settings.homeLimit()) {
                sendButtonLine(player, button("[ Set " + defaultName + " here ]", "/home set " + defaultName, NamedTextColor.GREEN));
            }
        }
    }

    private boolean teleportBack(Player player) {
        Optional<Location> destination = teleports.backLocation(player.getUniqueId());
        if (destination.isEmpty()) {
            player.sendMessage(error("No previous location is available."));
            return true;
        }
        teleports.begin(player, destination.get(), "previous location");
        return true;
    }

    private boolean teleportBed(Player player) {
        Location destination = player.getRespawnLocation();
        if (destination == null) {
            player.sendMessage(error("You do not have a valid bed location."));
            return true;
        }
        teleports.begin(player, destination, "bed");
        return true;
    }

    private boolean teleportSpawn(Player player) {
        teleports.begin(player, player.getWorld().getSpawnLocation(), "spawn");
        return true;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!settings.cancelOnMovement()) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) return;
        teleports.cancel(event.getPlayer(), true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!settings.cancelOnDamage() || event.isCancelled() || !(event.getEntity() instanceof Player player)) return;
        teleports.cancel(player, true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sendButtonLine(event.getPlayer(), info("Teleport options: "), button("[ Open ]", "/tpce", NamedTextColor.AQUA));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        teleports.onQuit(player);
        requests.removeIncomingFor(id).ifPresent(requesterId -> {
            Player requester = Bukkit.getPlayer(requesterId);
            if (requester != null) requester.sendMessage(error(player.getName() + " is no longer online. Teleport request cancelled."));
        });
        requests.removeOutgoingFor(id).ifPresent(targetId -> {
            Player target = Bukkit.getPlayer(targetId);
            if (target != null) target.sendMessage(error(player.getName() + " is no longer online. Teleport request cancelled."));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("tpr") && args.length == 1) {
            List<String> suggestions = new ArrayList<>(List.of("cancel", "page"));
            for (Player online : Bukkit.getOnlinePlayers()) if (!online.equals(player)) suggestions.add(online.getName());
            return partial(suggestions, args[0]);
        }
        if (name.equals("home")) {
            List<String> suggestions = new ArrayList<>(List.of("list", "set", "delete", "primary"));
            if (args.length == 1) {
                suggestions.addAll(homes.names(player.getUniqueId()));
                return partial(suggestions, args[0]);
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("primary"))) {
                return partial(homes.names(player.getUniqueId()), args[1]);
            }
        }
        if (name.equals("tpce") && args.length == 1) return partial(List.of("reload"), args[0]);
        return List.of();
    }

    private static List<String> partial(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static Component button(String label, String command, NamedTextColor color) {
        return Component.text(label, color).clickEvent(ClickEvent.runCommand(command));
    }

    private static Component info(String message) {
        return Component.text(message, NamedTextColor.GRAY);
    }

    private static Component success(String message) {
        return Component.text(message, NamedTextColor.GREEN);
    }

    private static Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    private static Component usage(String command) {
        return Component.text("Usage: ", NamedTextColor.GOLD).append(Component.text(command, NamedTextColor.WHITE));
    }

    private static void sendButtonLine(Player player, Component... components) {
        Component line = Component.empty();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) line = line.append(Component.text(" "));
            line = line.append(components[i]);
        }
        player.sendMessage(line);
    }

    private static boolean isValidHomeName(String name) {
        if (name.isEmpty() || name.length() > 32) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) return false;
        }
        return true;
    }
}
