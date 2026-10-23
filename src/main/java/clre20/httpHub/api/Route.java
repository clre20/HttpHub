package clre20.httpHub.api;

import org.bukkit.plugin.Plugin;

/** 一個已註冊的路徑。 */
public interface Route {

    Plugin owner();

    /** 完整路徑，例如 {@code /customdisc/pack.zip}。 */
    String path();

    /** 是否還有效（被移除或插件停用後為 false）。 */
    boolean active();

    /** 換掉內容（只適用於 {@link HttpHubService#serve}，其他類型會丟出 UnsupportedOperationException）。 */
    void update(byte[] content);

    /** 換掉內容與類型（只適用於 {@link HttpHubService#serve}）。 */
    void update(byte[] content, String contentType);

    /** 移除這個路徑。 */
    void remove();
}
