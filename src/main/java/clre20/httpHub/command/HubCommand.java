package clre20.httpHub.command;

import clre20.httpHub.HttpHub;
import clre20.httpHub.server.HubServer;
import clre20.httpHub.server.RouteTable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /httphub [help|version|status|reload] */
public final class HubCommand implements TabExecutor {

    private static final int MAX_LINES = 30;

    /** 子指令與權限（順序即說明的順序）。 */
    private static final String[][] SUBS = {
            {"help", "httphub.help"},
            {"version", "httphub.version"},
            {"status", "httphub.status"},
            {"reload", "httphub.reload"},
    };

    private final HttpHub plugin;

    public HubCommand(HttpHub plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String permission = permission(sub);
        if (permission == null) {
            plugin.messages().send(sender, "command.unknown", "label", label);
            return true;
        }
        if (!sender.hasPermission(permission)) {
            plugin.messages().send(sender, "command.no-permission");
            return true;
        }
        switch (sub) {
            case "version" -> plugin.updateNotifier().showTo(sender);
            case "status" -> status(sender);
            case "reload" -> plugin.messages().send(sender, plugin.reload() ? "command.reloaded" : "command.reload-failed",
                    "port", plugin.config().port());
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        plugin.messages().raw(sender, "help.header", "version", plugin.getPluginMeta().getVersion());
        for (String[] s : SUBS) {
            if (sender.hasPermission(s[1])) {
                plugin.messages().raw(sender, "help.line", "label", label, "sub", s[0],
                        "desc", plugin.messages().get("help." + s[0]));
            }
        }
    }

    private void status(CommandSender sender) {
        HubServer server = plugin.server();
        boolean running = server.running();
        plugin.messages().raw(sender, "status.header");
        plugin.messages().raw(sender, "status.server",
                "state", plugin.messages().get(running ? "status.on" : "status.off"),
                "host", plugin.config().host(), "port", plugin.config().port());
        plugin.messages().raw(sender, "status.stats", "served", server.served(), "bytes", size(server.bytesOut()),
                "rejected", server.rejected(), "active", server.activeConnections());
        List<RouteTable.Info> list = plugin.routes().snapshot();
        if (!list.isEmpty()) {
            plugin.messages().raw(sender, "status.routes-header", "count", list.size());
            int shown = 0;
            for (RouteTable.Info r : list) {
                if (shown++ >= MAX_LINES) {
                    plugin.messages().raw(sender, "status.more", "count", list.size() - MAX_LINES);
                    break;
                }
                String kind = plugin.messages().get("status.kind-" + r.kind().toLowerCase(Locale.ROOT),
                        "size", r.size() < 0 ? "" : size(r.size()));
                plugin.messages().raw(sender, "status.route", "path", r.path(), "plugin", r.plugin(), "kind", kind);
            }
        }

        // 顯示連接的外聯與更新插件
        if (plugin.clientHub() != null) {
            List<clre20.httpHub.api.HttpHubClientService.PluginClientInfo> clients = plugin.clientHub().getConnectedPlugins();
            sender.sendMessage("§e=== 已連接的外聯與集中更新插件 (" + clients.size() + ") ===");
            if (clients.isEmpty()) {
                sender.sendMessage("§7目前尚無外部插件向 HttpHub 登記外聯或更新檢查。");
            } else {
                for (clre20.httpHub.api.HttpHubClientService.PluginClientInfo c : clients) {
                    String updateTag = c.hasUpdate() ? "§c[有新版本: " + c.latestVersion() + "]" : "§a[最新版: " + c.latestVersion() + "]";
                    sender.sendMessage("§f- §b" + c.pluginName() + " §7(v" + c.currentVersion() + ") " + updateTag
                            + " §8| 請求數: §f" + c.requestCount() + " §8| Repo: §7" + c.repo());
                }
            }
        }
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    private static String permission(String sub) {
        for (String[] s : SUBS) {
            if (s[0].equals(sub)) {
                return s[1];
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            for (String[] s : SUBS) {
                if (s[0].startsWith(typed) && sender.hasPermission(s[1])) {
                    out.add(s[0]);
                }
            }
        }
        return out;
    }
}
