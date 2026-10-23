package clre20.httpHub.config;

import clre20.httpHub.HttpHub;
import clre20.httpHub.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * messages.yml：所有顯示文字。
 * 變數寫法 {name}，呼叫時用成對的參數替換：get("key", "name", "值", ...)。
 */
public final class Messages {

    private final HttpHub plugin;
    private volatile YamlConfiguration yaml = new YamlConfiguration();
    private volatile YamlConfiguration defaults = new YamlConfiguration();
    private int lastFilled;

    public Messages(HttpHub plugin) {
        this.plugin = plugin;
    }

    public void load() {
        YamlFiles.Loaded loaded = YamlFiles.load(plugin, "messages.yml", null,
                (file, reason) -> plugin.getLogger().warning("messages.yml: " + reason));
        yaml = loaded.yaml();
        defaults = loaded.defaults();
        lastFilled = loaded.filled();
    }

    /** 上次載入時補上的項目數（給後台訊息用）。 */
    public int lastFilled() {
        return lastFilled;
    }

    /** 取得訊息（含 & 顏色碼），找不到時回傳 key 本身。 */
    public String get(String key, Object... vars) {
        String raw = yaml.getString(key);
        if (raw == null) {
            raw = defaults.getString(key, key);
        }
        return apply(raw, vars);
    }

    /** 取得多行訊息（物品描述等）。 */
    public List<String> list(String key, Object... vars) {
        List<String> raw = yaml.isList(key) ? yaml.getStringList(key) : defaults.getStringList(key);
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            out.add(apply(line, vars));
        }
        return out;
    }

    public Component component(String key, Object... vars) {
        return Text.color(get(key, vars));
    }

    /** 傳送帶前綴的訊息。 */
    public void send(CommandSender to, String key, Object... vars) {
        to.sendMessage(Text.color(plugin.config().prefix() + get(key, vars)));
    }

    /** 傳送不帶前綴的訊息。 */
    public void raw(CommandSender to, String key, Object... vars) {
        to.sendMessage(Text.color(get(key, vars)));
    }

    /** 後台訊息（去掉顏色碼）。 */
    public String console(String key, Object... vars) {
        return Text.plain(get("console." + key, vars));
    }

    static String apply(String text, Object... vars) {
        if (vars.length == 0 || text.indexOf('{') < 0) {
            return text;
        }
        String out = text;
        for (int i = 0; i + 1 < vars.length; i += 2) {
            out = out.replace("{" + vars[i] + "}", String.valueOf(vars[i + 1]));
        }
        return out;
    }
}
