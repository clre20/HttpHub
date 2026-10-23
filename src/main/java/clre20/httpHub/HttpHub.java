package clre20.httpHub;

import clre20.httpHub.api.HttpHubService;
import clre20.httpHub.command.HubCommand;
import clre20.httpHub.config.Messages;
import clre20.httpHub.config.PluginConfig;
import clre20.httpHub.server.HubServer;
import clre20.httpHub.server.HubService;
import clre20.httpHub.server.RouteTable;
import clre20.httpHub.update.UpdateChecker;
import clre20.httpHub.update.UpdateNotifier;
import clre20.httpHub.util.Text;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class HttpHub extends JavaPlugin implements Listener {

    private PluginConfig config;
    private Messages messages;
    private RouteTable routes;
    private HubServer server;
    private HubService service;
    private UpdateChecker updates;
    private UpdateNotifier updateNotifier;

    @Override
    public void onEnable() {
        config = new PluginConfig(this);
        messages = new Messages(this);
        loadFiles();

        routes = new RouteTable();
        server = new HubServer(routes, config.limits(),
                (route, reason) -> getLogger().warning(messages.console("handler-error", "route", route, "reason", reason)));
        server.errorText(errorTexts());
        service = new HubService(routes, server, config::publicUrl, config::port);
        getServer().getServicesManager().register(HttpHubService.class, service, this, ServicePriority.Normal);

        updates = new UpdateChecker(getPluginMeta().getVersion());
        updateNotifier = new UpdateNotifier(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(updateNotifier, this);

        PluginCommand command = getCommand("httphub");
        if (command != null) {
            HubCommand executor = new HubCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        startServer();
        updateNotifier.checkOnStartup();
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (server != null) {
            server.stop();
        }
        if (routes != null) {
            routes.clear();
        }
        if (updates != null) {
            updates.shutdown();
        }
    }

    /** 其他插件停用時移除它的路徑。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == this || routes == null) {
            return;
        }
        int n = routes.removeAll(event.getPlugin());
        if (n > 0) {
            getLogger().info(messages.console("routes-removed", "plugin", event.getPlugin().getName(), "count", n));
        }
    }

    private void loadFiles() {
        config.load();
        messages.load();
        if (config.lastFilled() > 0) {
            getLogger().info(messages.console("config-filled", "count", config.lastFilled()));
        }
        if (messages.lastFilled() > 0) {
            getLogger().info(messages.console("messages-filled", "count", messages.lastFilled()));
        }
    }

    /** 開啟（或依新設定重開）HTTP 伺服器。 */
    public boolean startServer() {
        String host = config.host();
        int port = config.port();
        try {
            server.start(host, port, config.threads(), config.queue());
            String url = config.publicUrl();
            getLogger().info(messages.console("started", "host", host, "port", port,
                    "url", url != null ? url : messages.console("url-auto")));
            return true;
        } catch (IOException e) {
            getLogger().severe(messages.console("start-failed", "port", port, "reason", String.valueOf(e.getMessage())));
            return false;
        }
    }

    /** /httphub reload：重讀設定，端口等有變才重開伺服器；已註冊的路徑保留。 */
    public boolean reload() {
        loadFiles();
        server.limits(config.limits());
        server.errorText(errorTexts());
        return startServer();
    }

    /** 錯誤頁面文字先從 messages.yml 讀好，HTTP 執行緒直接查表。 */
    private java.util.function.IntFunction<String> errorTexts() {
        Map<Integer, String> map = new HashMap<>();
        for (int code : new int[]{400, 404, 405, 411, 413, 429, 431, 500}) {
            map.put(code, Text.plain(messages.get("http-error." + code)));
        }
        String fallback = Text.plain(messages.get("http-error.other"));
        Map<Integer, String> fixed = Map.copyOf(map);
        return code -> fixed.getOrDefault(code, fallback);
    }

    public PluginConfig config() { return config; }
    public Messages messages() { return messages; }
    public RouteTable routes() { return routes; }
    public HubServer server() { return server; }
    public UpdateChecker updates() { return updates; }
    public UpdateNotifier updateNotifier() { return updateNotifier; }
}
