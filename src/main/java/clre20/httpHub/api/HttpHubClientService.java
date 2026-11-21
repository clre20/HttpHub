package clre20.httpHub.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 提供給其他插件共用的集中式外聯 HTTP 客戶端與更新檢查服務。
 * 避免全伺服器幾十個插件各自開 HttpClient 導致線程爆炸。
 */
public interface HttpHubClientService {

    record UpdateInfo(String pluginName, String currentVersion, String latestVersion, boolean hasUpdate, String reason) {
    }

    record PluginClientInfo(String pluginName, String repo, String currentVersion, String latestVersion,
                            boolean hasUpdate, int requestCount, long lastActiveTime) {
    }

    static HttpHubClientService get() {
        return Bukkit.getServicesManager().load(HttpHubClientService.class);
    }

    /** 透過共用的虛擬執行緒 HttpClient 發起非同步 GET 請求並回傳字串。 */
    CompletableFuture<String> getStringAsync(Plugin caller, String url);

    /** 透過共用的虛擬執行緒 HttpClient 發起非同步 HTTP 請求。 */
    CompletableFuture<HttpResponse<byte[]>> sendAsync(Plugin caller, HttpRequest request);

    /**
     * 註冊插件的 GitHub 更新檢查。由 HttpHub 統一非同步查詢並快取，避免各自啟動查詢執行緒。
     *
     * @param caller         發起註冊的插件
     * @param repo           GitHub 倉庫（例如 "clre20/CoreTP"）
     * @param currentVersion 目前運行的版本號（例如 "3.0.1"）
     * @param permission     管理員登入通知所需的權限節點（例如 "coretp.version"，可為 null）
     * @param callback       查詢完成時的回呼（回傳 UpdateInfo）
     */
    void registerUpdateCheck(Plugin caller, String repo, String currentVersion, String permission, Consumer<UpdateInfo> callback);

    /** 取得目前所有已連接/註冊的外部插件清單與統計數據。 */
    List<PluginClientInfo> getConnectedPlugins();
}
