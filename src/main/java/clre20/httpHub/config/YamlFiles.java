package clre20.httpHub.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * 讀取插件資料夾裡的 yml，並自動補上缺少的項目。
 * 有缺少時：以插件內建的預設檔為底（保留預設檔的註解），填回使用者原本的值後寫回檔案。
 */
final class YamlFiles {

    /** 讀取結果：使用者的設定（已掛上預設值當備援）與這次補上的項目數。 */
    record Loaded(YamlConfiguration yaml, YamlConfiguration defaults, int filled) {
    }

    private YamlFiles() {
    }

    static YamlConfiguration defaults(JavaPlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    /**
     * @param migrate 補齊前可以把舊設定搬到新位置（參數：使用者設定、合併後的設定），可為 null
     */
    static Loaded load(JavaPlugin plugin, String name, BiConsumer<YamlConfiguration, YamlConfiguration> migrate,
                       BiConsumer<String, String> onError) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration defaults = defaults(plugin, name);
        YamlConfiguration user = YamlConfiguration.loadConfiguration(file);

        int missing = 0;
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !user.contains(key, true)) {
                missing++;
            }
        }
        if (missing > 0) {
            YamlConfiguration merged = defaults(plugin, name);
            for (String key : user.getKeys(true)) {
                if (user.isConfigurationSection(key) || key.equals("config-version")) {
                    continue;
                }
                // 新版已經不用的舊項目不寫回去
                if (!merged.contains(key, true) || merged.isConfigurationSection(key)) {
                    continue;
                }
                merged.set(key, user.get(key));
            }
            if (migrate != null) {
                migrate.accept(user, merged);
            }
            try {
                merged.save(file);
                user = merged;
            } catch (IOException e) {
                onError.accept(name, e.getMessage());
                missing = 0;
            }
        }
        user.setDefaults(defaults);
        return new Loaded(user, defaults, missing);
    }
}
