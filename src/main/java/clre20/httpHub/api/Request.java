package clre20.httpHub.api;

import java.util.List;
import java.util.Map;

/** 一個 HTTP 請求（只會是 GET、HEAD 或 POST）。所有內容都來自外部，使用前請自行驗證。 */
public interface Request {

    /** GET、HEAD 或 POST。 */
    String method();

    /** 完整路徑（已解碼），例如 {@code /pmonitor/api/tps}。 */
    String path();

    /** 註冊路徑後面的部分，例如前綴 {@code api} 收到 {@code /pmonitor/api/tps} 時為 {@code /tps}；完全相符時為空字串。 */
    String subPath();

    /** 查詢參數的第一個值，沒有時回傳 null。 */
    String query(String name);

    /** 所有查詢參數（已解碼）。 */
    Map<String, List<String>> queries();

    /** 請求標頭（名稱不分大小寫），沒有時回傳 null。 */
    String header(String name);

    /** POST 的內容；GET / HEAD 為空陣列。大小不會超過 config 的 max-body-kb。 */
    byte[] body();

    /** 用戶端 IP（在 trusted-proxies 後面時為 X-Forwarded-For 的位址）。 */
    String remoteAddress();
}
