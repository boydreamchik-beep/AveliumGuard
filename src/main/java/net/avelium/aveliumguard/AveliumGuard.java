package net.avelium.aveliumguard;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

public class AveliumGuard extends JavaPlugin implements Listener {

    private File dataFile;
    private FileConfiguration data;
    private final Set<UUID> loggedIn = new HashSet<>();
    private final Map<UUID, Integer> attempts = new HashMap<>();
    private final Map<UUID, Integer> loginTasks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupDataFile();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AveliumGuard запущен!");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void setupDataFile() {
        dataFile = new File(getDataFolder(), "players.yml");
        if (!dataFile.exists()) {
            getDataFolder().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Не удалось создать players.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Не удалось сохранить players.yml: " + e.getMessage());
        }
    }

    private String hash(String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(pw.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return pw;
        }
    }

    private String msg(String path) {
        String prefix = getConfig().getString("messages.prefix", "");
        String message = getConfig().getString("messages." + path, path);
        return ChatColor.translateAlternateColorCodes('&', prefix + message);
    }

    private String plain(String path) {
        String message = getConfig().getString("messages." + path, path);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private boolean isRegistered(Player p) {
        return data.contains("players." + p.getUniqueId() + ".password");
    }

    private boolean isLoggedIn(Player p) {
        return loggedIn.contains(p.getUniqueId());
    }

    private Component legacyToComponent(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(
                ChatColor.translateAlternateColorCodes('&', s));
    }

    private void showTitle(Player p, String path) {
        String title = getConfig().getString("titles." + path + ".title", "");
        String sub = getConfig().getString("titles." + path + ".subtitle", "");
        p.showTitle(Title.title(
                legacyToComponent(title),
                legacyToComponent(sub),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(500))
        ));
    }

    private void startLoginTimer(Player p) {
        int timeout = getConfig().getInt("settings.login-timeout", 60);
        int taskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isLoggedIn(p) && p.isOnline()) {
                    p.kick(legacyToComponent(plain("kick-timeout")));
                }
            }
        }.runTaskLater(this, timeout * 20L).getTaskId();
        loginTasks.put(p.getUniqueId(), taskId);
    }

    private void cancelLoginTimer(Player p) {
        Integer id = loginTasks.remove(p.getUniqueId());
        if (id != null) getServer().getScheduler().cancelTask(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        e.joinMessage(null);

        if (getConfig().getBoolean("settings.auto-login-by-ip", true) && isRegistered(p)) {
            String savedIp = data.getString("players." + p.getUniqueId() + ".ip");
            String currentIp = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : null;
            if (savedIp != null && savedIp.equals(currentIp)) {
                loggedIn.add(p.getUniqueId());
                p.sendMessage(msg("auto-login"));
                showTitle(p, "success");
                return;
            }
        }

        startLoginTimer(p);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline() || isLoggedIn(p)) return;
                if (isRegistered(p)) {
                    p.sendMessage(msg("need-login"));
                    showTitle(p, "login");
                } else {
                    p.sendMessage(msg("need-register"));
                    showTitle(p, "register");
                }
            }
        }.runTaskLater(this, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        e.quitMessage(null);
        loggedIn.remove(p.getUniqueId());
        attempts.remove(p.getUniqueId());
        cancelLoginTimer(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        if (!isLoggedIn(e.getPlayer())) {
            if (e.getFrom().getBlockX() != e.getTo().getBlockX() ||
                    e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
                e.setTo(e.getFrom());
            }
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (!isLoggedIn(e.getPlayer())) {
            e.setCancelled(true);
            Player p = e.getPlayer();
            p.sendMessage(msg(isRegistered(p) ? "need-login" : "need-register"));
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (isLoggedIn(e.getPlayer())) return;
        String cmd = e.getMessage().toLowerCase().split(" ")[0].substring(1);
        List<String> allowed = Arrays.asList("register", "reg", "login", "l");
        if (!allowed.contains(cmd)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg(isRegistered(e.getPlayer()) ? "need-login" : "need-register"));
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!isLoggedIn(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!isLoggedIn(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && !isLoggedIn(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!isLoggedIn(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (!isLoggedIn(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(PlayerAttemptPickupItemEvent e) {
        if (!isLoggedIn(e.getPlayer())) e.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("guardreload")) {
            if (!sender.hasPermission("aveliumguard.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            reloadConfig();
            data = YamlConfiguration.loadConfiguration(dataFile);
            sender.sendMessage(ChatColor.GREEN + "AveliumGuard: конфиг перезагружен!");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("unregister")) {
            if (!sender.hasPermission("aveliumguard.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(ChatColor.RED + "Использование: /unregister <ник>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            UUID uuid = target != null ? target.getUniqueId() : null;
            if (uuid == null) {
                ConfigurationSection section = data.getConfigurationSection("players");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        if (args[0].equalsIgnoreCase(data.getString("players." + key + ".name"))) {
                            try {
                                uuid = UUID.fromString(key);
                            } catch (IllegalArgumentException ignored) {}
                            break;
                        }
                    }
                }
            }
            if (uuid == null) {
                sender.sendMessage(ChatColor.RED + "Игрок не найден!");
                return true;
            }
            data.set("players." + uuid, null);
            saveData();
            loggedIn.remove(uuid);
            sender.sendMessage(ChatColor.GREEN + "Аккаунт " + args[0] + " удалён!");
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Только для игроков!");
            return true;
        }

        switch (cmd.getName().toLowerCase()) {
            case "register" -> handleRegister(p, args);
            case "login" -> handleLogin(p, args);
            case "changepassword" -> handleChangePassword(p, args);
        }
        return true;
    }

    private void handleRegister(Player p, String[] args) {
        if (isRegistered(p)) {
            p.sendMessage(msg("already-registered"));
            return;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.RED + "Использование: /register <пароль> <пароль>");
            return;
        }
        if (!args[0].equals(args[1])) {
            p.sendMessage(msg("password-mismatch"));
            return;
        }

        int min = getConfig().getInt("settings.min-password-length", 4);
        int max = getConfig().getInt("settings.max-password-length", 32);
        if (args[0].length() < min) {
            p.sendMessage(msg("password-too-short").replace("%min%", String.valueOf(min)));
            return;
        }
        if (args[0].length() > max) {
            p.sendMessage(msg("password-too-long").replace("%max%", String.valueOf(max)));
            return;
        }

        String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "unknown";
        data.set("players." + p.getUniqueId() + ".password", hash(args[0]));
        data.set("players." + p.getUniqueId() + ".name", p.getName());
        data.set("players." + p.getUniqueId() + ".ip", ip);
        saveData();

        loggedIn.add(p.getUniqueId());
        cancelLoginTimer(p);
        p.sendMessage(msg("register-success"));
        showTitle(p, "success");
    }

    private void handleLogin(Player p, String[] args) {
        if (isLoggedIn(p)) {
            p.sendMessage(msg("already-logged-in"));
            return;
        }
        if (!isRegistered(p)) {
            p.sendMessage(msg("not-registered"));
            return;
        }
        if (args.length < 1) {
            p.sendMessage(ChatColor.RED + "Использование: /login <пароль>");
            return;
        }

        String saved = data.getString("players." + p.getUniqueId() + ".password");
        if (!hash(args[0]).equals(saved)) {
            int a = attempts.getOrDefault(p.getUniqueId(), 0) + 1;
            int max = getConfig().getInt("settings.max-attempts", 3);
            if (a >= max) {
                p.kick(legacyToComponent(plain("kick-too-many-attempts")));
                attempts.remove(p.getUniqueId());
                return;
            }
            attempts.put(p.getUniqueId(), a);
            p.sendMessage(msg("wrong-password").replace("%attempts%", String.valueOf(max - a)));
            return;
        }

        loggedIn.add(p.getUniqueId());
        attempts.remove(p.getUniqueId());
        cancelLoginTimer(p);
        String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "unknown";
        data.set("players." + p.getUniqueId() + ".ip", ip);
        saveData();
        p.sendMessage(msg("login-success"));
        showTitle(p, "success");
    }

    private void handleChangePassword(Player p, String[] args) {
        if (!isLoggedIn(p)) {
            p.sendMessage(msg("need-login"));
            return;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.RED + "Использование: /changepassword <старый> <новый>");
            return;
        }

        String saved = data.getString("players." + p.getUniqueId() + ".password");
        if (!hash(args[0]).equals(saved)) {
            p.sendMessage(ChatColor.RED + "Неверный текущий пароль!");
            return;
        }

        int min = getConfig().getInt("settings.min-password-length", 4);
        if (args[1].length() < min) {
            p.sendMessage(msg("password-too-short").replace("%min%", String.valueOf(min)));
            return;
        }

        data.set("players." + p.getUniqueId() + ".password", hash(args[1]));
        saveData();
        p.sendMessage(msg("password-changed"));
    }
}
