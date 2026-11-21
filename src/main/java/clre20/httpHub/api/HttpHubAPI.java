package clre20.httpHub.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 方便其他插件直接以靜態方法調用 HttpHub 集中外聯服務的門面類別。
 */
public final class HttpHubAPI {

    private HttpHubAPI() {
    }

    /** 檢查 HttpHub 服務目前是否可用。 */
    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("HttpHub") && HttpHubClientService.get() != null;
    }

    /** 透過共用的虛擬執行緒 HttpClient 發起非同步 GET 請求。 */
    public static CompletableFuture<String> getStringAsync(Plugin caller, String url) {
        HttpHubClientService svc = HttpHubClientService.get();
        if (svc == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("HttpHub service is not available"));
        }
        return svc.getStringAsync(caller, url);
    }

    /** 透過共用的虛擬執行緒 HttpClient 發起非同步 HTTP 請求。 */
    public static CompletableFuture<HttpResponse<byte[]>> sendAsync(Plugin caller, HttpRequest request) {
        HttpHubClientService svc = HttpHubClientService.get();
        if (svc == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("HttpHub service is not available"));
        }
        return svc.sendAsync(caller, request);
    }

    /**
     * 向 HttpHub 集中登記更新檢查，省去插件各自維護 HttpClient 與查詢線程。
     */
    public static void registerUpdateCheck(Plugin caller, String repo, String currentVersion, String permission,
                                           Consumer<HttpHubClientService.UpdateInfo> callback) {
        HttpHubClientService svc = HttpHubClientService.get();
        if (svc != null) {
            svc.registerUpdateCheck(caller, repo, currentVersion, permission, callback);
        }
    }

    /** 取得所有已連接插件的資訊。 */
    public static List<HttpHubClientService.PluginClientInfo> getConnectedPlugins() {
        HttpHubClientService svc = HttpHubClientService.get();
        return svc == null ? List.of() : svc.getConnectedPlugins();
    }
}
