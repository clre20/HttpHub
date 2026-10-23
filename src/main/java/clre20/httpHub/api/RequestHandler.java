package clre20.httpHub.api;

/**
 * 產生動態回應。
 *
 * <p>在 HttpHub 的 HTTP 執行緒上執行，不是主執行緒：要讀寫 Bukkit 世界、玩家等資料時，
 * 請先在主執行緒準備好快取，或用 {@code Bukkit.getScheduler().callSyncMethod(...)} 取得。
 * 處理時間超過 config 的 stall-seconds 會被中斷。丟出例外時回應 500。</p>
 */
@FunctionalInterface
public interface RequestHandler {

    Response handle(Request request) throws Exception;
}
