package clre20.httpHub.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * HttpHub 提供給其他插件的服務：所有插件共用同一個 HTTP 端口。
 *
 * <pre>{@code
 * HttpHubService hub = HttpHubService.get();          // 沒裝 HttpHub 時是 null
 * Route pack = hub.serve(this, "pack.zip", bytes, "application/zip");
 * String url = hub.url(player, pack);                  // http://玩家連線的位址:端口/<插件名>/pack.zip
 * }</pre>
 *
 * <ul>
 *   <li>路徑一律放在 {@code /<插件名稱小寫>/} 底下，不能註冊到其他插件的路徑。</li>
 *   <li>路徑只能用英數字與 {@code . _ - ~ /}，不能有 {@code ..}、{@code //}，長度 200 以內。</li>
 *   <li>同一個插件註冊相同路徑時，新的取代舊的。</li>
 *   <li>插件停用時，它註冊的路徑全部自動移除。</li>
 *   <li>所有方法都可以在任何執行緒呼叫。</li>
 * </ul>
 */
public interface HttpHubService {

    /** 取得服務；HttpHub 沒有安裝或沒有啟用時回傳 null。呼叫前請先確認 HttpHub 類別存在（softdepend）。 */
    static HttpHubService get() {
        return Bukkit.getServicesManager().load(HttpHubService.class);
    }

    /**
     * 提供一份放在記憶體裡的內容（只回應 GET / HEAD）。
     *
     * @param owner       註冊的插件
     * @param path        插件底下的路徑，例如 {@code "pack.zip"} 或 {@code "img/logo.png"}
     * @param content     內容（HttpHub 不會複製，之後請不要再修改這個陣列）
     * @param contentType 例如 {@code "application/zip"}
     */
    Route serve(Plugin owner, String path, byte[] content, String contentType);

    /** 由程式產生回應，只比對完全相同的路徑。 */
    Route handle(Plugin owner, String path, RequestHandler handler);

    /**
     * 由程式產生回應，比對這個路徑本身與底下的所有路徑（例如 {@code "api"} 會比對 {@code /<插件>/api/...}）。
     * 完全相同的路徑優先，其次是較長的前綴。
     */
    Route handlePrefix(Plugin owner, String prefix, RequestHandler handler);

    /** 移除這個插件註冊的所有路徑。 */
    void unregisterAll(Plugin owner);

    /**
     * 給玩家用的網址根目錄，例如 {@code http://play.example.com:52010}（結尾沒有 /）。
     * config 的 public-url 為 auto 時使用玩家連線時輸入的位址；取不到時回傳 null。
     */
    String baseUrl(Player player);

    /** {@link #baseUrl(Player)} 加上路徑；取不到位址時回傳 null。 */
    String url(Player player, Route route);

    /** HTTP 伺服器目前是否在運作（端口開啟成功）。 */
    boolean running();

    /** 目前使用的端口。 */
    int port();
}
