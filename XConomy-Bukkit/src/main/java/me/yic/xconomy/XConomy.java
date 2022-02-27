/*
 *  This file (XConomy.java) is a part of project XConomy
 *  Copyright (C) YiC and contributors
 *
 *  This program is free software: you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the
 *  Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package me.yic.xconomy;

import me.yic.xconomy.data.DataCon;
import me.yic.xconomy.data.DataFormat;
import me.yic.xconomy.data.DataLink;
import me.yic.xconomy.data.caches.CacheSemiOnline;
import me.yic.xconomy.data.sql.SQL;
import me.yic.xconomy.depend.LoadEconomy;
import me.yic.xconomy.depend.Placeholder;
import me.yic.xconomy.depend.economy.VaultHook;
import me.yic.xconomy.info.*;
import me.yic.xconomy.lang.MessagesManager;
import me.yic.xconomy.listeners.ConnectionListeners;
import me.yic.xconomy.listeners.SPPsync;
import me.yic.xconomy.listeners.SPsync;
import me.yic.xconomy.listeners.TabList;
import me.yic.xconomy.task.Baltop;
import me.yic.xconomy.task.Updater;
import me.yic.xconomy.utils.EconomyCommand;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;

public class XConomy extends JavaPlugin {

    private static XConomy instance;
    public static String syncversion = SyncInfo.syncversion;
    public static FileConfiguration config;
    private MessagesManager messageManager;
    private static boolean foundvaultpe = false;
    public static boolean foundvaultOfflinePermManager = false;
    private BukkitTask refresherTask = null;
    Metrics metrics = null;
    private Placeholder papiExpansion = null;

    @SuppressWarnings("ConstantConditions")
    public void onEnable() {
        instance = this;
        load();
        DataBaseINFO.load();
        readserverinfo();
        messageManager = new MessagesManager(this);
        messageManager.load();

        MCVersion.MCVersion = Bukkit.getBukkitVersion().toLowerCase();
        MCVersion.chatcolorcheck();

        if (!LoadEconomy.load()) {
            getLogger().warning("No supported dependent plugins were found");
            getLogger().warning("[ Vault ][ Enterprise ]");
            logger("XConomy已成功卸载", 0, null);
            return;
        }

        foundvaultOfflinePermManager = checkVaultOfflinePermManager();

        if (Bukkit.getPluginManager().getPlugin("DatabaseDrivers") != null) {
            logger("发现 DatabaseDrivers", 0, null);
            ServerINFO.DDrivers = true;
        }

        if (allowHikariConnectionPooling()) {
            ServerINFO.EnableConnectionPool = true;
        } else {
            logger("连接池未启用", 0, null);
        }

        if (!DataLink.create()) {
            logger("XConomy已成功卸载", 0, null);
            return;
        }

        DataCon.baltop();

        if (checkup()) {
            new Updater().runTaskAsynchronously(this);
        }
        // 检查更新

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            logger("发现 PlaceholderAPI", 0, null);
            if (checkVaultPE()) {
                logger(null, 0, String.join("", Collections.nCopies(70, "=")));
                logger("vault-baltop-tips-a", 0, null);
                logger("vault-baltop-tips-b", 0, null);
                logger(null, 0, String.join("", Collections.nCopies(70, "=")));
                foundvaultpe = true;
            }
            setupPlaceHolderAPI();
        }

        getServer().getPluginManager().registerEvents(new ConnectionListeners(), this);


        metrics = new Metrics(this, 6588);

        Bukkit.getPluginCommand("admin").setExecutor(new Commands());
        Bukkit.getPluginCommand("point").setExecutor(new Commands());
        Bukkit.getPluginCommand("ptop").setExecutor(new Commands());
        Bukkit.getPluginCommand("send").setExecutor(new Commands());
        Bukkit.getPluginCommand("xconomy").setExecutor(new Commands());

        this.getCommand("admin").setTabCompleter(new TabList());
        this.getCommand("point").setTabCompleter(new TabList());
        this.getCommand("ptop").setTabCompleter(new TabList());
        this.getCommand("send").setTabCompleter(new TabList());
        this.getCommand("xconomy").setTabCompleter(new TabList());

        if (config.getBoolean("Settings.eco-command")) {
            try {
                final Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");
                bukkitCommandMap.setAccessible(true);
                CommandMap commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());
                coveress(commandMap);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (config.getBoolean("BungeeCord.enable")) {
            if (isBungeecord()) {
                getServer().getMessenger().registerIncomingPluginChannel(this, "xconomy:aca", new SPsync());
                getServer().getMessenger().registerOutgoingPluginChannel(this, "xconomy:acb");
                getServer().getMessenger().registerIncomingPluginChannel(this, "xconomy:global", new SPPsync());
                logger("已开启BungeeCord同步", 0, null);
            } else if (DataBaseINFO.getStorageType() == 0 || DataBaseINFO.getStorageType() == 1) {
                if (DataBaseINFO.gethost().equalsIgnoreCase("Default")) {
                    logger("SQLite文件路径设置错误", 1, null);
                    logger("BungeeCord同步未开启", 1, null);
                }
            }
        }


        DataFormat.load();

        int time = config.getInt("Settings.refresh-time");
        if (time < 30) {
            time = 30;
        }

        refresherTask = new Baltop().runTaskTimerAsynchronously(this, time * 20L, time * 20L);
        logger(null, 0, "===== YiC =====");

    }

    public void onDisable() {
        LoadEconomy.unload();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null && papiExpansion != null) {
            try {
                papiExpansion.unregister();
            } catch (NoSuchMethodError ignored) {
            }
        }

        if (isBungeecord()) {
            getServer().getMessenger().unregisterIncomingPluginChannel(this, "xconomy:aca", new SPsync());
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, "xconomy:acb");
            getServer().getMessenger().unregisterIncomingPluginChannel(this, "xconomy:global", new SPPsync());
        }

        refresherTask.cancel();
        CacheSemiOnline.save();
        SQL.close();
        logger("XConomy已成功卸载", 0, null);
    }

    public static XConomy getInstance() {
        return instance;
    }

    public void reloadMessages() {
        messageManager.load();
    }

    @SuppressWarnings("ConstantConditions")
    public void readserverinfo() {
        ServerINFO.Lang = config.getString("Settings.language");
        ServerINFO.IsBungeeCordMode = isBungeecord();
        ServerINFO.Sign = config.getString("BungeeCord.sign");
        ServerINFO.InitialAmount = config.getDouble("Settings.initial-bal");
        ServerINFO.disablecache = config.getBoolean("Settings.disable-cache");
        ServerINFO.IgnoreCase = config.getBoolean("Settings.username-ignore-case");

        ServerINFO.RankingSize = config.getInt("Settings.ranking-size");
        ServerINFO.LinesNumber = config.getInt("Settings.lines-per-page");
        if (ServerINFO.RankingSize > 100) {
            ServerINFO.RankingSize = 100;
        }

        if (config.getString("Settings.UUID-mode").equalsIgnoreCase("Online")) {
            ServerINFO.IsOnlineMode = true;
        } else if (config.getString("Settings.UUID-mode").equalsIgnoreCase("SemiOnline")) {
            ServerINFO.IsSemiOnlineMode = true;
        }
    }

    public static boolean allowHikariConnectionPooling() {
        if (DataBaseINFO.DataBaseINFO.getBoolean("Settings.usepool")) {
            try {
                Class.forName("org.slf4j.Logger");
            } catch (ClassNotFoundException e) {
                getInstance().logger("未找到 'org.slf4j.Logger'", 1, null);
                return false;
            }
            if (DataBaseINFO.getStorageType() == 0 || DataBaseINFO.getStorageType() == 1) {
                return false;
            }
            return !foundvaultpe;
        }
        return false;
    }

    public static String getSign() {
        return config.getString("BungeeCord.sign");
    }

    private void setupPlaceHolderAPI() {
        papiExpansion = new Placeholder(this);
        if (papiExpansion.register()) {
            getLogger().info("PlaceholderAPI successfully hooked");
        } else {
            getLogger().warning("PlaceholderAPI unsuccessfully hooked");
        }
    }

    public void logger(String tag, int type, String message) {
        String mess = message;
        if (tag != null) {
            if (message == null) {
                mess = MessagesManager.systemMessage(tag);
            } else {
                if (message.startsWith("<#>")) {
                    mess = message.substring(3) + MessagesManager.systemMessage(tag);
                } else {
                    mess = MessagesManager.systemMessage(tag) + message;
                }
            }
        }
        if (type == 1) {
            getLogger().warning(mess);
        } else {
            getLogger().info(mess);
        }
    }

    public static boolean isBungeecord() {
        if (!config.getBoolean("BungeeCord.enable")) {
            return false;
        }

        if (DataBaseINFO.getStorageType() == 0 || DataBaseINFO.getStorageType() == 1) {
            return !DataBaseINFO.gethost().equalsIgnoreCase("Default");
        }

        return true;

    }


    public static boolean checkup() {
        return config.getBoolean("Settings.check-update");
    }

    private void load() {
        saveDefaultConfig();
        update_config();
        reloadConfig();
        config = getConfig();
    }

    @SuppressWarnings("ConstantConditions")
    private static boolean checkVaultPE() {
        File peFolder = new File(Bukkit.getPluginManager().getPlugin("PlaceholderAPI").getDataFolder(), "config.yml");
        if (!peFolder.exists()) {
            return false;
        }
        FileConfiguration peConfig = YamlConfiguration.loadConfiguration(peFolder);
        if (peConfig.contains("expansions.vault.baltop.enabled")) {
            return peConfig.getBoolean("expansions.vault.baltop.enabled");
        }
        return false;
    }

    private void update_config() {
        File config = new File(this.getDataFolder(), "config.yml");
        boolean update = UpdateConfig.update(getConfig(), config);
        if (update) {
            saveConfig();
        }
    }


    private void coveress(CommandMap commandMap) {
        Command commanda = new EconomyCommand("economy");
        commandMap.register("economy", commanda);
        Command commandb = new EconomyCommand("eco");
        commandMap.register("eco", commandb);
        Command commandc = new EconomyCommand("ebalancetop");
        commandMap.register("ebalancetop", commandc);
        Command commandd = new EconomyCommand("ebaltop");
        commandMap.register("ebaltop", commandd);
        Command commande = new EconomyCommand("eeconomy");
        commandMap.register("eeconomy", commande);
    }

    @SuppressWarnings("all")
    private boolean checkVaultOfflinePermManager() {
        // Check if vault is linked to a permission system that supports offline player checks.
        if (LoadEconomy.vault && VaultHook.vaultPerm != null) {
            switch (VaultHook.vaultPerm.getName()) {
                // Add other plugins that also have an offline player permissions manager.
                case "LuckPerms":
                    return true;
            }
        }
        return false;
    }
}
