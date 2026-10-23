package clre20.httpHub.server;

import java.util.Set;

/**
 * 連線限制（不可變，重載時整份換掉）。
 *
 * @param maxConnectionsPerIp 同一 IP 同時最多幾個連線（含排隊中）
 * @param requestsPerSecond   同一 IP 每秒最多幾個請求
 * @param stallSeconds        傳送或處理中連續幾秒沒有進度就切斷
 * @param headerTimeoutSeconds 讀取請求標頭的總時限
 * @param maxBodyBytes        POST 內容上限
 * @param trustedProxies      反向代理的 IP：不受連線數限制，並以 X-Forwarded-For 當作用戶端 IP
 */
public record Limits(int maxConnectionsPerIp, int requestsPerSecond, int stallSeconds,
                     int headerTimeoutSeconds, int maxBodyBytes, Set<String> trustedProxies) {

    public boolean trusted(String ip) {
        return trustedProxies.contains(ip);
    }
}
