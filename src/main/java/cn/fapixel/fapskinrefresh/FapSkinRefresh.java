package cn.fapixel.fapskinrefresh;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.data.Skin;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.network.protocol.PlayerSkinPacket;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import com.neteasemc.nukkitmaster.NukkitMaster;
import com.neteasemc.nukkitmaster.pyrpc.PyRpcHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FapSkinRefresh — 网易 V860 (3.9) 客户端玩家隐身修复插件。
 * <p>
 * <b>问题</b>：网易 V860 客户端在 PlayerList 渲染上有缺陷，导致玩家偶尔完全隐身
 * （皮肤、nametag、手持物品全部不显示）。已有的代理层去重插件（NetEasePlayerListFix）
 * 虽然能避免重复 ADD 触发 bug，但当客户端内部状态损坏后，修复包会被代理去重逻辑抑制。
 * <p>
 * <b>原理</b>：利用代理只拦截 {@code PlayerListPacket} 的特性，通过以下手段绕过代理：
 * <ul>
 *   <li><b>PlayerSkinPacket</b>：代理不拦截此包，可直接刷新客户端皮肤数据</li>
 *   <li><b>despawn + spawn</b>：发送 RemoveEntity/AddPlayer，代理不拦截这些包</li>
 * </ul>
 */
public class FapSkinRefresh extends cn.nukkit.plugin.PluginBase implements Listener {

    // ---- Config ----
    private boolean refreshEnabled;
    private int refreshInterval;
    private int refreshBatchSize;

    private boolean fullRefreshEnabled;
    private int fullRefreshInterval;
    private int fullRefreshBatchSize;
    private int fullRefreshSpawnDelay;

    private boolean clientRequestEnabled;
    private int clientRequestCooldown;
    private int clientRequestRadius;

    private boolean joinRefreshEnabled;
    private int joinRefreshDelay;

    private boolean debug;

    // ---- Namespace ----
    private String modName;
    private String clientSystem;

    // ---- Runtime ----
    private NukkitMaster nm;

    // 客户端请求冷却记录：玩家名 → 上次请求时间戳
    private final Map<String, Long> clientRequestCooldowns = new ConcurrentHashMap<>();

    // 定期皮肤刷新游标：轮转处理玩家，避免每次只刷新前 N 个
    private int refreshSkinIndex = 0;

    // 全量实体刷新游标：轮转处理玩家，确保所有玩家都能轮到
    private int fullRefreshQueueIndex = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        nm = (NukkitMaster) getServer().getPluginManager().getPlugin("NukkitMaster");
        if (nm == null || !nm.isEnabled()) {
            getLogger().error("§cNukkitMaster not found or disabled! FapSkinRefresh cannot function.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        // 注册客户端请求监听
        if (clientRequestEnabled) {
            nm.listenForEvent(modName, clientSystem, "RequestSkinRefreshEvent",
                    (PyRpcHandler) (player, data) -> handleClientRequest(player));
        }

        // 定期皮肤刷新任务
        if (refreshEnabled && refreshInterval > 0) {
            int ticks = refreshInterval * 20;
            getServer().getScheduler().scheduleRepeatingTask(this, this::refreshAllSkins, ticks);
            getLogger().info("§a定期皮肤刷新: 每 " + refreshInterval + " 秒");
        }

        // 全量实体刷新任务
        if (fullRefreshEnabled && fullRefreshInterval > 0) {
            int ticks = fullRefreshInterval * 20;
            getServer().getScheduler().scheduleRepeatingTask(this, this::fullRefreshAll, ticks);
            getLogger().info("§a全量实体刷新: 每 " + fullRefreshInterval + " 秒");
        }

        printBanner();
    }

    // ================================================================
    //  定期皮肤刷新（PlayerSkinPacket，不经代理去重）
    // ================================================================

    private void refreshAllSkins() {
        refreshAllSkins(refreshBatchSize);
    }

    /**
     * 定期皮肤刷新（PlayerSkinPacket，不经代理去重）。
     * 游标轮转：每次只处理 batchSize 个 target 玩家，确保所有人都能轮到。
     *
     * @param batchSize 本轮处理的 target 玩家数（定时任务用配置值，手动命令传 Integer.MAX_VALUE）
     */
    private void refreshAllSkins(int batchSize) {
        if (getServer().getOnlinePlayers().size() < 2) return;

        List<Player> list = new ArrayList<>(getServer().getOnlinePlayers().values());
        int size = list.size();
        int actualBatch = Math.min(batchSize, size);

        // 游标轮转：从上次结束位置继续，确保所有玩家都能轮到刷新
        if (refreshSkinIndex >= size) {
            refreshSkinIndex = 0;
        }
        int start = refreshSkinIndex;
        int count = 0;

        for (int i = 0; i < actualBatch; i++) {
            int idx = (start + i) % size;
            Player target = list.get(idx);
            for (Player viewer : list) {
                if (target == viewer) continue;
                sendSkinPacket(target, viewer);
                count++;
            }
        }
        refreshSkinIndex = (start + actualBatch) % size;

        if (debug) {
            getLogger().info("§7[SkinRefresh] 发送了 " + count + " 个 PlayerSkinPacket"
                    + " (游标: " + start + " → " + refreshSkinIndex + "/" + size + ")");
        }
    }

    /**
     * 向 viewer 发送 target 的 PlayerSkinPacket。
     * 网易皮肤验证：无效皮肤替换为默认 Steve 皮肤，避免完全不显示。
     */
    private void sendSkinPacket(Player target, Player viewer) {
        if (target == null || viewer == null || !target.isOnline() || !viewer.isOnline()) return;
        try {
            Skin skin = target.getSkin();
            // 网易皮肤验证：检查皮肤数据是否合法（尺寸、格式、几何体等）
            if (skin == null || !skin.isValid()) {
                skin = Skin.NO_PERSONA_SKIN;
                if (debug) {
                    getLogger().warning("§e[SkinRefresh] " + target.getName()
                            + " 皮肤无效，使用默认 Steve 皮肤");
                }
            }
            PlayerSkinPacket pkt = new PlayerSkinPacket();
            pkt.uuid = target.getUniqueId();
            pkt.skin = skin;
            pkt.newSkinName = skin.getSkinId();
            pkt.oldSkinName = "";
            viewer.dataPacket(pkt);
        } catch (Throwable t) {
            if (debug) {
                getLogger().warning("§c[SkinRefresh] 发送皮肤包失败: " + target.getName()
                        + " → " + viewer.getName() + ": " + t.getMessage());
            }
        }
    }

    // ================================================================
    //  全量实体刷新（despawn + spawn，重置实体渲染）
    // ================================================================

    /**
     * 对所有在线玩家做 despawn + spawn 全量刷新。
     * 每个 (target, viewer) 对：先 despawn target from viewer，延迟 spawnDelay tick 后 spawn。
     */
    private void fullRefreshAll() {
        fullRefreshAll(fullRefreshBatchSize);
    }

    /**
     * 全量实体刷新（despawn + spawn，重置实体渲染）。
     * 游标轮转：每次只处理 batchSize 个 target 玩家，确保所有人都能轮到。
     *
     * @param batchSize 本轮处理的 target 玩家数（定时任务用配置值，手动命令传 Integer.MAX_VALUE）
     */
    private void fullRefreshAll(int batchSize) {
        if (getServer().getOnlinePlayers().size() < 2) return;

        List<Player> list = new ArrayList<>(getServer().getOnlinePlayers().values());
        int size = list.size();
        int actualBatch = Math.min(batchSize, size);

        // 游标轮转：从上次结束位置继续，确保所有玩家都能轮到全量刷新
        if (fullRefreshQueueIndex >= size) {
            fullRefreshQueueIndex = 0;
        }
        int start = fullRefreshQueueIndex;
        final int delay = fullRefreshSpawnDelay;

        for (int i = 0; i < actualBatch; i++) {
            int idx = (start + i) % size;
            final Player t = list.get(idx);

            // despawn from all
            try {
                t.despawnFromAll();
            } catch (Throwable ignored) {}

            // 延迟 spawn to all
            getServer().getScheduler().scheduleDelayedTask(this, () -> {
                if (t.isOnline()) {
                    try {
                        t.spawnToAll();
                    } catch (Throwable ignored) {}
                    // spawn 后立即发送 PlayerSkinPacket 确保皮肤数据有效
                    for (Player v : getServer().getOnlinePlayers().values()) {
                        if (v != t) sendSkinPacket(t, v);
                    }
                }
            }, delay);
        }
        fullRefreshQueueIndex = (start + actualBatch) % size;

        if (debug) {
            getLogger().info("§7[SkinRefresh] 全量刷新了 " + actualBatch + " 个玩家实体"
                    + " (游标: " + start + " → " + fullRefreshQueueIndex + "/" + size + ")");
        }
    }

    // ================================================================
    //  客户端请求刷新（精准，只刷新请求方附近的玩家）
    // ================================================================

    private void handleClientRequest(Player requester) {
        if (requester == null || !requester.isOnline()) return;

        // 冷却检查
        long now = System.currentTimeMillis();
        long cooldownMs = clientRequestCooldown * 1000L;
        Long lastTime = clientRequestCooldowns.get(requester.getName());
        if (lastTime != null && (now - lastTime) < cooldownMs) {
            if (debug) {
                getLogger().info("§7[SkinRefresh] " + requester.getName()
                        + " 请求刷新，冷却中 (" + (cooldownMs - (now - lastTime)) / 1000 + "s)");
            }
            return;
        }
        clientRequestCooldowns.put(requester.getName(), now);

        // 获取附近的玩家并刷新
        double radiusSq = (double) clientRequestRadius * clientRequestRadius;
        int refreshCount = 0;

        for (Player target : getServer().getOnlinePlayers().values()) {
            if (target == requester) continue;
            // 检查距离
            double distSq = target.distanceSquared(requester);
            if (distSq > radiusSq) continue;

            // 发送皮肤包
            sendSkinPacket(target, requester);

            // despawn + spawn 重置实体渲染
            final Player t = target;
            final Player r = requester;
            try {
                t.despawnFrom(r);
            } catch (Throwable ignored) {}

            getServer().getScheduler().scheduleDelayedTask(this, () -> {
                if (t.isOnline() && r.isOnline()) {
                    try {
                        t.spawnTo(r);
                    } catch (Throwable ignored) {}
                    sendSkinPacket(t, r);
                }
            }, fullRefreshSpawnDelay);

            refreshCount++;
        }

        if (debug) {
            getLogger().info("§a[SkinRefresh] " + requester.getName()
                    + " 请求刷新，刷新了 " + refreshCount + " 个附近玩家");
        }
    }

    // ================================================================
    //  事件
    // ================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!joinRefreshEnabled) return;
        final Player player = event.getPlayer();

        // 延迟刷新：等 NukkitMaster 皮肤验证完成
        getServer().getScheduler().scheduleDelayedTask(this, () -> {
            if (!player.isOnline()) return;

            // 向所有其他在线玩家发送新玩家的 PlayerSkinPacket
            for (Player other : getServer().getOnlinePlayers().values()) {
                if (other != player) {
                    sendSkinPacket(player, other);
                }
            }

            // 同时让新玩家收到所有其他在线玩家的 PlayerSkinPacket
            for (Player other : getServer().getOnlinePlayers().values()) {
                if (other != player) {
                    sendSkinPacket(other, player);
                }
            }

            if (debug) {
                getLogger().info("§7[SkinRefresh] " + player.getName() + " 进服延迟刷新完成");
            }
        }, joinRefreshDelay * 20);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clientRequestCooldowns.remove(event.getPlayer().getName());
    }

    // ================================================================
    //  Command
    // ================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("fapskin")) return false;

        if (args.length == 0) {
            sendInfo(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                reloadConfig();
                loadConfig();
                sender.sendMessage("§a[SkinRefresh] 配置已重载。");
                break;
            case "refresh":
                if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
                    sender.sendMessage("§e[SkinRefresh] 正在全量刷新...");
                    // 手动命令一次性处理所有玩家（不限制 batch_size）
                    refreshAllSkins(Integer.MAX_VALUE);
                    fullRefreshAll(Integer.MAX_VALUE);
                    sender.sendMessage("§a[SkinRefresh] 刷新完成。");
                } else if (args.length >= 2) {
                    Player target = getServer().getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage("§c玩家不在线: " + args[1]);
                        return true;
                    }
                    sender.sendMessage("§e[SkinRefresh] 正在刷新 " + target.getName() + " 的皮肤...");
                    for (Player other : getServer().getOnlinePlayers().values()) {
                        if (other != target) {
                            sendSkinPacket(target, other);
                        }
                    }
                    sender.sendMessage("§a[SkinRefresh] 已向所有玩家刷新 " + target.getName() + " 的皮肤。");
                } else {
                    sender.sendMessage("§7用法: /fapskin refresh <playername|all>");
                }
                break;
            case "info":
                sendInfo(sender);
                break;
            default:
                sender.sendMessage("§6FapSkinRefresh 命令:");
                sender.sendMessage("§7  /fapskin                  §8- 查看状态");
                sender.sendMessage("§7  /fapskin refresh <name>   §8- 刷新指定玩家皮肤");
                sender.sendMessage("§7  /fapskin refresh all      §8- 全量刷新");
                sender.sendMessage("§7  /fapskin reload           §8- 重载配置");
        }
        return true;
    }

    private void sendInfo(CommandSender sender) {
        sender.sendMessage("§6╔═══ FapSkinRefresh ═══╗");
        sender.sendMessage("§7 定期皮肤刷新: " + (refreshEnabled ? "§a✔" : "§c✘") + " §7(每" + refreshInterval + "s)");
        sender.sendMessage("§7 全量实体刷新: " + (fullRefreshEnabled ? "§a✔" : "§c✘") + " §7(每" + fullRefreshInterval + "s)");
        sender.sendMessage("§7 客户端请求: " + (clientRequestEnabled ? "§a✔" : "§c✘") + " §7(冷却" + clientRequestCooldown + "s)");
        sender.sendMessage("§7 进服延迟刷新: " + (joinRefreshEnabled ? "§a✔" : "§c✘") + " §7(延迟" + joinRefreshDelay + "s)");
        sender.sendMessage("§7 在线玩家: §b" + getServer().getOnlinePlayers().size());
    }

    // ================================================================
    //  Config & Utils
    // ================================================================

    private void loadConfig() {
        Config cfg = getConfig();
        modName = cfg.getString("mod_name", "FapModMain");
        clientSystem = cfg.getString("client_system", "FapModClient");

        refreshEnabled = cfg.getBoolean("refresh.enabled", true);
        refreshInterval = cfg.getInt("refresh.interval", 60);
        refreshBatchSize = cfg.getInt("refresh.batch_size", 10);

        fullRefreshEnabled = cfg.getBoolean("full_refresh.enabled", true);
        fullRefreshInterval = cfg.getInt("full_refresh.interval", 180);
        fullRefreshBatchSize = cfg.getInt("full_refresh.batch_size", 5);
        fullRefreshSpawnDelay = cfg.getInt("full_refresh.spawn_delay", 1);

        clientRequestEnabled = cfg.getBoolean("client_request.enabled", true);
        clientRequestCooldown = cfg.getInt("client_request.cooldown", 15);
        clientRequestRadius = cfg.getInt("client_request.radius", 128);

        joinRefreshEnabled = cfg.getBoolean("join_refresh.enabled", true);
        joinRefreshDelay = cfg.getInt("join_refresh.delay", 5);

        debug = cfg.getBoolean("debug", false);
    }

    private void printBanner() {
        getLogger().info("§e╔══════════════════════════════════════╗");
        getLogger().info("§e║  §6FapSkinRefresh §7v" + getDescription().getVersion() + " §a✔ 已加载");
        getLogger().info("§e║  §f皮肤刷新: " + (refreshEnabled ? "§a✔ 每" + refreshInterval + "s" : "§c✘"));
        getLogger().info("§e║  §f实体刷新: " + (fullRefreshEnabled ? "§a✔ 每" + fullRefreshInterval + "s" : "§c✘"));
        getLogger().info("§e║  §f客户端请求: " + (clientRequestEnabled ? "§a✔" : "§c✘"));
        getLogger().info("§e╚══════════════════════════════════════╝");
    }
}
