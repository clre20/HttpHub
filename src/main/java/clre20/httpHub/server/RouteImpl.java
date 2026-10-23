package clre20.httpHub.server;

import clre20.httpHub.api.RequestHandler;
import clre20.httpHub.api.Route;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/** 已註冊的路徑。內容以 volatile 保存，HTTP 執行緒讀取時不用加鎖。 */
final class RouteImpl implements Route {

    enum Kind { STATIC, EXACT, PREFIX }

    /** 靜態內容與類型放在一起換，避免讀到新內容配舊類型。 */
    record Content(byte[] bytes, String type) {
    }

    private final RouteTable table;
    private final Plugin owner;
    private final String path;
    private final Kind kind;
    private final RequestHandler handler;
    private volatile Content content;
    private volatile boolean active = true;
    /** 上次記錄處理錯誤的時間（避免被人刻意觸發錯誤洗後台）。 */
    volatile long lastErrorLog;

    RouteImpl(RouteTable table, Plugin owner, String path, Kind kind, RequestHandler handler, Content content) {
        this.table = table;
        this.owner = owner;
        this.path = path;
        this.kind = kind;
        this.handler = handler;
        this.content = content;
    }

    Kind kind() {
        return kind;
    }

    RequestHandler handler() {
        return handler;
    }

    Content content() {
        return content;
    }

    void deactivate() {
        active = false;
    }

    @Override
    public Plugin owner() {
        return owner;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public void update(byte[] bytes) {
        Content c = content;
        update(bytes, c == null ? "application/octet-stream" : c.type());
    }

    @Override
    public void update(byte[] bytes, String contentType) {
        if (kind != Kind.STATIC) {
            throw new UnsupportedOperationException("only routes created by serve() hold content");
        }
        content = new Content(Objects.requireNonNull(bytes, "content"), RouteTable.contentType(contentType));
    }

    @Override
    public void remove() {
        table.remove(this);
    }
}
