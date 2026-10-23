package clre20.httpHub.config;

import clre20.httpHub.HttpHub;
import clre20.httpHub.server.Limits;
import org.bukkit.configuration.file.YamlConfiguration;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * config.yml。啟動與重載時自動補上缺少的設定，原本的值保留。
 * 所有讀取都有預設值與範圍限制，設定檔寫錯也不會出錯。
 */
public final class PluginConfig {

    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final HttpHub plugin;
    private volatile YamlConfiguration yaml = new YamlConfiguration();
    private int lastFilled;

    public PluginConfig(HttpHub plugin) {
        this.plugin = plugin;
    }

    public void load() {
        YamlFiles.Loaded loaded = YamlFiles.load(plugin, "config.yml", null,
                (file, reason) -> plugin.getLogger().warning("config.yml: " + reason));
        yaml = loaded.yaml();
        lastFilled = loaded.filled();
    }

    public int lastFilled() {
        return lastFilled;
    }

    public String prefix() {
        return yaml.getString("prefix", "&8[&bHttpHub&8] &r");
    }

    public String host() {
        String h = yaml.getString("server.host", "0.0.0.0");
        return h == null || h.isBlank() ? "0.0.0.0" : h.trim();
    }

    public int port() {
        return clamp(yaml.getInt("server.port", 52010), 1, 65535);
    }

    /** 對外網址；回傳 null 代表 auto（用玩家連線時的位址）。 */
    public String publicUrl() {
        String url = yaml.getString("server.public-url", "auto");
        if (url == null || url.isBlank() || url.trim().equalsIgnoreCase("auto")) {
            return null;
        }
        url = url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public int threads() {
        return clamp(yaml.getInt("server.threads", 8), 2, 64);
    }

    public int queue() {
        return clamp(yaml.getInt("server.queue", 64), 8, 1024);
    }

    public Limits limits() {
        Set<String> proxies = new HashSet<>();
        for (String s : yaml.getStringList("limits.trusted-proxies")) {
            String ip = s == null ? "" : s.trim();
            if (!IP_LITERAL.matcher(ip).matches()) {
                plugin.getLogger().warning(plugin.messages().console("bad-proxy", "value", ip));
                continue;
            }
            try {
                // 只接受 IP 字面值（上面已檢查字元），不會做 DNS 查詢；統一成標準寫法再比對
                proxies.add(InetAddress.getByName(ip).getHostAddress());
            } catch (Exception e) {
                plugin.getLogger().warning(plugin.messages().console("bad-proxy", "value", ip));
            }
        }
        return new Limits(
                clamp(yaml.getInt("limits.max-connections-per-ip", 4), 1, 64),
                clamp(yaml.getInt("limits.requests-per-second", 20), 1, 1000),
                clamp(yaml.getInt("limits.stall-seconds", 15), 5, 300),
                clamp(yaml.getInt("limits.header-timeout-seconds", 10), 2, 60),
                clamp(yaml.getInt("limits.max-body-kb", 64), 0, 10240) * 1024,
                Set.copyOf(proxies));
    }

    public boolean updateCheckOnStartup() {
        return yaml.getBoolean("update-checker.check-on-startup", true);
    }

    public boolean updateNotifyOnJoin() {
        return yaml.getBoolean("update-checker.notify-on-join", true);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
