package clre20.httpHub.server;

import clre20.httpHub.api.RequestHandler;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 所有插件註冊的路徑。完全相符優先，其次是最長的前綴。 */
public final class RouteTable {

    private static final Pattern SUB_PATH = Pattern.compile("[A-Za-z0-9._~/-]{0,200}");
    private static final int MAX_ROUTES = 10_000;

    private final Map<String, RouteImpl> exact = new ConcurrentHashMap<>();
    private final Map<String, RouteImpl> prefixes = new ConcurrentHashMap<>();

    record Match(RouteImpl route, String subPath) {
    }

    /** 插件的路徑名稱：名稱轉小寫，英數字、_、- 以外的字元換成 -。 */
    public static String namespace(Plugin plugin) {
        String name = plugin.getName().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '-');
        }
        return sb.toString();
    }

    static String contentType(String type) {
        if (type == null || type.isBlank()) {
            return "application/octet-stream";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : type.toCharArray()) {
            if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 檢查插件傳入的路徑並組成完整路徑。 */
    static String fullPath(Plugin owner, String sub) {
        String s = sub == null ? "" : sub;
        if (!SUB_PATH.matcher(s).matches()) {
            throw new IllegalArgumentException("invalid path: " + sub);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.isEmpty()) {
            for (String seg : s.split("/", -1)) {
                if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                    throw new IllegalArgumentException("invalid path: " + sub);
                }
            }
        }
        String ns = "/" + namespace(owner);
        return s.isEmpty() ? ns : ns + "/" + s;
    }

    RouteImpl add(Plugin owner, String sub, RouteImpl.Kind kind, RequestHandler handler, RouteImpl.Content content) {
        Objects.requireNonNull(owner, "owner");
        if (!owner.isEnabled()) {
            throw new IllegalStateException("plugin is not enabled: " + owner.getName());
        }
        String full = fullPath(owner, sub);
        Map<String, RouteImpl> map = kind == RouteImpl.Kind.PREFIX ? prefixes : exact;
        if (!map.containsKey(full) && exact.size() + prefixes.size() >= MAX_ROUTES) {
            throw new IllegalStateException("too many routes");
        }
        RouteImpl route = new RouteImpl(this, owner, full, kind, handler, content);
        RouteImpl old = map.put(full, route);
        if (old != null) {
            old.deactivate();
        }
        return route;
    }

    void remove(RouteImpl route) {
        Map<String, RouteImpl> map = route.kind() == RouteImpl.Kind.PREFIX ? prefixes : exact;
        map.remove(route.path(), route);
        route.deactivate();
    }

    /** @return 移除的數量 */
    public int removeAll(Plugin owner) {
        int n = 0;
        for (Map<String, RouteImpl> map : List.of(exact, prefixes)) {
            for (RouteImpl r : map.values()) {
                if (r.owner() == owner && map.remove(r.path(), r)) {
                    r.deactivate();
                    n++;
                }
            }
        }
        return n;
    }

    public void clear() {
        for (Map<String, RouteImpl> map : List.of(exact, prefixes)) {
            map.values().forEach(RouteImpl::deactivate);
            map.clear();
        }
    }

    Match find(String path) {
        RouteImpl r = exact.get(path);
        if (r != null) {
            return new Match(r, "");
        }
        if (prefixes.isEmpty()) {
            return null;
        }
        // 由長到短逐段比對，次數最多等於路徑層數
        String p = path;
        while (true) {
            r = prefixes.get(p);
            if (r != null) {
                return new Match(r, path.substring(p.length()));
            }
            int idx = p.lastIndexOf('/');
            if (idx <= 0) {
                return null;
            }
            p = p.substring(0, idx);
        }
    }

    /** 給 /httphub status 用，依路徑排序。 */
    public List<Info> snapshot() {
        List<Info> out = new ArrayList<>();
        for (Map<String, RouteImpl> map : List.of(exact, prefixes)) {
            for (RouteImpl r : map.values()) {
                RouteImpl.Content c = r.content();
                out.add(new Info(r.path(), r.owner().getName(), r.kind().name(), c == null ? -1 : c.bytes().length));
            }
        }
        out.sort(Comparator.comparing(Info::path));
        return out;
    }

    public record Info(String path, String plugin, String kind, long size) {
    }
}
