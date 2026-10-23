package clre20.httpHub.server;

import clre20.httpHub.api.HttpHubService;
import clre20.httpHub.api.RequestHandler;
import clre20.httpHub.api.Route;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** {@link HttpHubService} 的實作。 */
public final class HubService implements HttpHubService {

    /** 網域名稱或 IPv4。 */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    /** IPv6。 */
    private static final Pattern HOST_V6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final RouteTable routes;
    private final HubServer server;
    private final Supplier<String> publicUrl;
    private final IntSupplier port;

    public HubService(RouteTable routes, HubServer server, Supplier<String> publicUrl, IntSupplier port) {
        this.routes = routes;
        this.server = server;
        this.publicUrl = publicUrl;
        this.port = port;
    }

    @Override
    public Route serve(Plugin owner, String path, byte[] content, String contentType) {
        Objects.requireNonNull(content, "content");
        return routes.add(owner, path, RouteImpl.Kind.STATIC, null,
                new RouteImpl.Content(content, RouteTable.contentType(contentType)));
    }

    @Override
    public Route handle(Plugin owner, String path, RequestHandler handler) {
        return routes.add(owner, path, RouteImpl.Kind.EXACT, Objects.requireNonNull(handler, "handler"), null);
    }

    @Override
    public Route handlePrefix(Plugin owner, String prefix, RequestHandler handler) {
        return routes.add(owner, prefix, RouteImpl.Kind.PREFIX, Objects.requireNonNull(handler, "handler"), null);
    }

    @Override
    public void unregisterAll(Plugin owner) {
        routes.removeAll(owner);
    }

    @Override
    public String baseUrl(Player player) {
        String base = publicUrl.get();
        if (base != null) {
            return base;
        }
        String host = connectHost(player);
        if (host == null) {
            return null;
        }
        return "http://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port.getAsInt();
    }

    @Override
    public String url(Player player, Route route) {
        String base = baseUrl(player);
        return base == null ? null : base + route.path();
    }

    @Override
    public boolean running() {
        return server.running();
    }

    @Override
    public int port() {
        return port.getAsInt();
    }

    /**
     * 玩家連線時輸入的位址（握手封包裡的主機名稱）。
     * 只接受網域名稱、IPv4、IPv6 的字元，其他內容（例如 Forge 附加的標記）一律不用。
     */
    static String connectHost(Player player) {
        if (player == null) {
            return null;
        }
        InetSocketAddress vh;
        try {
            vh = player.getVirtualHost();
        } catch (Throwable t) {
            return null;
        }
        if (vh == null || vh.getHostString() == null) {
            return null;
        }
        String host = vh.getHostString();
        int nul = host.indexOf('\0');
        if (nul >= 0) {
            host = host.substring(0, nul);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (HOST.matcher(host).matches() || (host.indexOf(':') >= 0 && HOST_V6.matcher(host).matches())) {
            return host;
        }
        return null;
    }
}
