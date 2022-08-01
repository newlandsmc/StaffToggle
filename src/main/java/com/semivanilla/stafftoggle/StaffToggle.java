package com.semivanilla.stafftoggle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

public final class StaffToggle extends JavaPlugin implements Listener {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static Set<UUID> inStaffMode = new HashSet<>();
    private static List<String> onCommands = new ArrayList<>();
    private static List<String> offCommands = new ArrayList<>();
    private FileConfiguration config = null;

    private List<Component> stillInStaffMode;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists())
            getDataFolder().mkdir();
        File config = new File(getDataFolder(), "config.yml");
        if (!config.exists()) {
            saveDefaultConfig();
        }
        getCommand("stafftoggle").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("actionbar.enable")) {
            Component bar = MINI_MESSAGE.deserialize(getConfig().getString("actionbar.bar"));
            Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
                for (UUID uuid : inStaffMode) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        player.sendActionBar(bar);
                    } else inStaffMode.remove(uuid);
                }
            }, 20L, 20L);
        }
        onCommands.clear();
        onCommands.addAll(getConfig().getStringList("commands.on"));
        offCommands.clear();
        offCommands.addAll(getConfig().getStringList("commands.off"));

        stillInStaffMode = new ArrayList<>();
        for (String s : getConfig().getStringList("messages.log-on")) {
            stillInStaffMode.add(MINI_MESSAGE.deserialize(s));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogout(PlayerQuitEvent event) {
        //toggle(event.getPlayer(), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        checkStaff(player);
        /*
        getServer().getScheduler().runTaskLater(this, ()-> {
            if (player != null && player.isOnline()) {
                toggle(player, true);
            }
        }, 20L);
         */
    }

    public void checkStaff(Player player) {
        if (player == null) return;
        if (inStaffMode.contains(player.getUniqueId())) {
            return;
        }
        if (isInStaffMode(player)) {
            for (Component component : stillInStaffMode) {
                player.sendMessage(component);
            }
            inStaffMode.add(player.getUniqueId());
        }
    }

    public Triplet<Boolean, Optional<Node>, User> checkPermission(Player player) {
        LuckPerms lp = LuckPermsProvider.get();
        User user = lp.getUserManager().getUser(player.getUniqueId());
        List<Group> groups = new ArrayList<>();
        groups.add(lp.getGroupManager().getGroup(user.getPrimaryGroup()));
        groups.addAll(user.getInheritedGroups(user.getQueryOptions()));
        List<Node> nodes = new ArrayList<>(user.getNodes());
        groups.forEach(g -> nodes.addAll(g.getNodes()));
        Optional<Node> optionalNode = nodes.stream().filter(n -> n.getValue() && !n.hasExpired() && !n.isNegated() && n.getKey().toLowerCase().startsWith("stafftoggle.")).findFirst();
        boolean hasPerm = optionalNode.isPresent();
        return new Triplet<>(hasPerm, optionalNode, user);
    }

    public boolean isInStaffMode(Player player) {
        Triplet<Boolean, Optional<Node>, User> triplet = checkPermission(player);
        boolean hasPerm = triplet.getValue0();
        Optional<Node> optNode = triplet.getValue1();
        User user = triplet.getValue2();
        if (hasPerm && optNode.isPresent()) {
            Node node = optNode.get();
            String group = node.getKey().replace("stafftoggle.", "");
            return user.getNodes().stream().anyMatch(n -> n.getKey().equalsIgnoreCase("group." + group));
        }
        return false;
    }

    public void toggle(Player player, boolean... toggleOff) {
        Triplet<Boolean, Optional<Node>, User> triplet = checkPermission(player);
        boolean hasPerm = triplet.getValue0();
        Optional<Node> optionalNode = triplet.getValue1();
        User user = triplet.getValue2();
        LuckPerms lp = LuckPermsProvider.get();
        if (hasPerm) {
            Node node = optionalNode.get();
            String group = node.getKey().replace("stafftoggle.", "");
            if (toggleOff.length > 0 && toggleOff[0]) {
                user.data().remove(Node.builder("group." + group).build());
                lp.getUserManager().saveUser(user);
                inStaffMode.remove(player.getUniqueId());
                return;
            }
            boolean toggleOn = user.getNodes().stream().noneMatch(n -> n.getKey().equalsIgnoreCase("group." + group));
            if (toggleOn) {
                user.data().add(Node.builder("group." + group).build());
                List<String> messages = getConfig().getStringList("messages.toggle-on");
                for (String message : messages) {
                    player.sendMessage(MINI_MESSAGE.deserialize(message));
                }
                inStaffMode.add(player.getUniqueId());
                for (String onCommand : onCommands) {
                    if (onCommand.isEmpty()) continue;
                    player.performCommand(onCommand.replace("%player%", player.getName()));
                }
            } else {
                List<String> messages = getConfig().getStringList("messages.toggle-off");
                for (String message : messages) {
                    player.sendMessage(MINI_MESSAGE.deserialize(message));
                }
                user.data().remove(Node.builder("group." + group).build());
                inStaffMode.remove(player.getUniqueId());
                for (String offCommand : offCommands) {
                    if (offCommand.isEmpty()) continue;
                    player.performCommand(offCommand.replace("%player%", player.getName()));
                }
            }
            lp.getUserManager().saveUser(user);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This is a player-only command!");
            return true;
        }
        if (!sender.hasPermission("stafftoggle.use")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
            return true;
        }
        Player player = (Player) sender;
        toggle(player);
        return true;
    }

    @Override
    public @NotNull FileConfiguration getConfig() {
        if (config == null) {
            config = YamlConfiguration.loadConfiguration(new File(getDataFolder() + "/config.yml"));
        }
        return config;
    }
}
