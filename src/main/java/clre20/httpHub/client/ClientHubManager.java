package clre20.httpHub.client;

import clre20.httpHub.HttpHub;
import clre20.httpHub.api.HttpHubClientService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 集中管理所有外部插件的外聯請求與更新檢查。
 * 使用單例 HttpClient + Java 25 原生虛擬執行緒，不浪費任何 OS 線程。
 */
public final class ClientHubManager implements HttpHubClientService, Listener {

    private static class PluginRecord {
        final String pluginName;
        final String repo;
        final String currentVersion;
        final String permission;
        final Consumer<UpdateInfo> callback;
        final AtomicInteger requests = new AtomicInteger();
        volatile String latestVersion = "查詢中";
        volatile boolean hasUpdate = false;
        volatile String reason = "";
        volatile long lastActive = System.currentTimeMillis();

        PluginRecord(String pluginName, String repo, String currentVersion, String permission, Consumer<UpdateInfo> callback) {
            this.pluginName = pluginName;
            this.repo = repo;
            this.currentVersion = currentVersion;
            this.permission = permission;
            this.callback = callback;
        }
    }

    private final HttpHub plugin;
    private final HttpClient client;
    private final Map<String, PluginRecord> registeredPlugins = new ConcurrentHashMap<>();

    public ClientHubManager(HttpHub plugin) {
        this.plugin = plugin;
        this.client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private PluginRecord getOrAdd(Plugin caller) {
        String name = caller != null ? caller.getName() : "Unknown";
        return registeredPlugins.computeIfAbsent(name, k -> new PluginRecord(k, "", "", "", null));
    }

    @Override
    public CompletableFuture<String> getStringAsync(Plugin caller, String url) {
        PluginRecord rec = getOrAdd(caller);
        rec.requests.incrementAndGet();
        rec.lastActive = System.currentTimeMillis();
        plugin.getLogger().info("[Audit] 插件 '" + rec.pluginName + "' 透過 HttpHub 發起 HTTP GET: " + url);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Minecraft-HttpHub-SharedClient")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    @Override
    public CompletableFuture<HttpResponse<byte[]>> sendAsync(Plugin caller, HttpRequest request) {
        PluginRecord rec = getOrAdd(caller);
        rec.requests.incrementAndGet();
        rec.lastActive = System.currentTimeMillis();
        plugin.getLogger().info("[Audit] 插件 '" + rec.pluginName + "' 透過 HttpHub 發起 HTTP " + request.method() + ": " + request.uri());

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Override
    public void registerUpdateCheck(Plugin caller, String repo, String currentVersion, String permission, Consumer<UpdateInfo> callback) {
        String name = caller != null ? caller.getName() : "Unknown";
        PluginRecord rec = new PluginRecord(name, repo, currentVersion, permission, callback);
        registeredPlugins.put(name, rec);
        plugin.getLogger().info("[Audit] 插件 '" + name + "' 向 HttpHub 登記了集中更新檢查 (Repo: " + repo + ", 目前版本: " + currentVersion + ")");

        // 非同步背景檢查更新
        checkPluginUpdate(rec);
    }

    private void checkPluginUpdate(PluginRecord rec) {
        if (rec.repo == null || rec.repo.isBlank()) {
            return;
        }
        String url = "https://api.github.com/repos/" + rec.repo + "/releases/latest";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "HttpHub-Central-UpdateChecker")
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    rec.lastActive = System.currentTimeMillis();
                    if (res.statusCode() == 200) {
                        try {
                            JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
                            if (obj.has("tag_name")) {
                                String tag = obj.get("tag_name").getAsString().replaceAll("^[vV]", "").trim();
                                rec.latestVersion = tag;
                                int cmp = compareVersion(tag, rec.currentVersion);
                                rec.hasUpdate = cmp > 0;
                                rec.reason = rec.hasUpdate ? "發現新版本: " + tag : (cmp < 0 ? "開發版" : "已是最新版");
                            }
                        } catch (Exception e) {
                            rec.latestVersion = "解析失敗";
                            rec.reason = e.getMessage();
                        }
                    } else if (res.statusCode() == 403 || res.statusCode() == 429) {
                        rec.latestVersion = "查詢受限 (Rate Limit)";
                        rec.reason = "HTTP " + res.statusCode();
                    } else {
                        rec.latestVersion = "查詢失敗 (" + res.statusCode() + ")";
                        rec.reason = "HTTP " + res.statusCode();
                    }

                    UpdateInfo info = new UpdateInfo(rec.pluginName, rec.currentVersion, rec.latestVersion, rec.hasUpdate, rec.reason);
                    if (rec.callback != null) {
                        try {
                            rec.callback.accept(info);
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .exceptionally(err -> {
                    rec.latestVersion = "連線失敗";
                    rec.reason = err.getMessage();
                    return null;
                });
    }

    private int compareVersion(String a, String b) {
        String[] pa = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] pb = b.replaceAll("[^0-9.]", "").split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length && !pa[i].isEmpty() ? Integer.parseInt(pa[i]) : 0;
            int vb = i < pb.length && !pb[i].isEmpty() ? Integer.parseInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!p.isOp()) {
            boolean hasAny = registeredPlugins.values().stream()
                    .anyMatch(r -> r.permission != null && !r.permission.isEmpty() && p.hasPermission(r.permission));
            if (!hasAny) return;
        }

        // 登入 3 秒後提醒
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            for (PluginRecord r : registeredPlugins.values()) {
                if (r.hasUpdate) {
                    if (p.isOp() || (r.permission != null && p.hasPermission(r.permission))) {
                        p.sendMessage("§e[HttpHub] 插件 §b" + r.pluginName + " §e有新版本可更新：§a" + r.latestVersion
                                + " §7(目前: " + r.currentVersion + ") §8- https://github.com/" + r.repo + "/releases");
                    }
                }
            }
        }, 60L);
    }

    @Override
    public List<PluginClientInfo> getConnectedPlugins() {
        List<PluginClientInfo> list = new ArrayList<>();
        for (PluginRecord r : registeredPlugins.values()) {
            list.add(new PluginClientInfo(r.pluginName, r.repo, r.currentVersion, r.latestVersion,
                    r.hasUpdate, r.requests.get(), r.lastActive));
        }
        return list;
    }
}
