package clre20.httpHub.server;

import clre20.httpHub.api.Request;
import clre20.httpHub.api.Response;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/**
 * 精簡的 HTTP/1.1 伺服器（每個請求處理完就關閉連線）。
 *
 * <p>為了讓所有插件共用時不會被拖垮：</p>
 * <ul>
 *   <li>連線一進來就檢查同一 IP 的連線數（含排隊中），超過直接關閉，不佔用處理執行緒。</li>
 *   <li>處理執行緒與排隊數量固定；排滿時直接關閉新連線。</li>
 *   <li>請求標頭有大小上限與總時限（防止慢慢送標頭佔住執行緒）。</li>
 *   <li>讀取內容、程式處理、送出回應時，連續 stall-seconds 秒沒有進度就切斷。</li>
 *   <li>同一 IP 每秒請求數有上限。</li>
 *   <li>有連線在排隊時（壓力模式），標頭時限縮短為 3 秒、無進度時限縮短為 5 秒。</li>
 * </ul>
 */
public final class HubServer {

    private static final int MAX_HEADER_BYTES = 8 * 1024;
    private static final int MAX_HEADERS = 64;
    private static final int CHUNK = 64 * 1024;
    private static final int PRESSURE_HEADER_SECONDS = 3;
    private static final int PRESSURE_STALL_SECONDS = 5;
    private static final long ERROR_LOG_INTERVAL = TimeUnit.SECONDS.toNanos(60);
    private static final Pattern CLIENT_IP = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final RouteTable routes;
    /** 處理器丟出例外時記錄（插件名稱、原因）。 */
    private final BiConsumer<String, String> errorLog;

    private volatile Limits limits;
    /** 錯誤頁面的文字（從 messages.yml 讀取）。 */
    private volatile IntFunction<String> errorText = code -> code + " " + reason(code);

    private ServerSocket socket;
    private ThreadPoolExecutor workers;
    private ScheduledExecutorService watchdog;
    private String boundHost;
    private int boundPort = -1;
    private int boundThreads;
    private int boundQueue;

    private final Map<String, Integer> perIp = new ConcurrentHashMap<>();
    private final Map<String, Window> rate = new ConcurrentHashMap<>();
    private final Set<Conn> active = ConcurrentHashMap.newKeySet();

    private final AtomicLong served = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();

    public HubServer(RouteTable routes, Limits limits, BiConsumer<String, String> errorLog) {
        this.routes = routes;
        this.limits = limits;
        this.errorLog = errorLog;
    }

    public void limits(Limits limits) {
        this.limits = limits;
    }

    public void errorText(IntFunction<String> text) {
        this.errorText = text;
    }

    /** 開啟伺服器；位址、端口、執行緒數都沒變時不會重開。 */
    public synchronized void start(String host, int port, int threads, int queue) throws IOException {
        if (socket != null && host.equals(boundHost) && port == boundPort && threads == boundThreads
                && queue == boundQueue) {
            return;
        }
        stop();
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(host, port), 128);
        } catch (IOException e) {
            ss.close();
            throw e;
        }
        socket = ss;
        workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue), daemon("HttpHub-Worker"), new ThreadPoolExecutor.AbortPolicy());
        watchdog = Executors.newSingleThreadScheduledExecutor(daemon("HttpHub-Watchdog"));
        watchdog.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        Thread acceptor = daemon("HttpHub-Acceptor").newThread(() -> acceptLoop(ss));
        acceptor.start();
        boundHost = host;
        boundPort = port;
        boundThreads = threads;
        boundQueue = queue;
    }

    public synchronized void stop() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
        for (Conn c : active) {
            c.close();
        }
        active.clear();
        perIp.clear();
        rate.clear();
        boundHost = null;
        boundPort = -1;
    }

    public synchronized boolean running() {
        return socket != null;
    }

    public long served() {
        return served.get();
    }

    public long rejected() {
        return rejected.get();
    }

    public long bytesOut() {
        return bytesOut.get();
    }

    public int activeConnections() {
        return active.size();
    }

    // ---------------------------------------------------------------- 連線

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (ss.isClosed()) {
                    return;
                }
                // 例如檔案描述符用完：稍等再試，避免空轉吃滿 CPU
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            handoff(s);
        }
    }

    private void handoff(Socket s) {
        String ip = s.getInetAddress().getHostAddress();
        Limits l = limits;
        boolean proxy = l.trusted(ip);
        if (!proxy && !acquire(ip, l.maxConnectionsPerIp())) {
            rejected.incrementAndGet();
            closeQuietly(s);
            return;
        }
        Conn conn = new Conn(s, ip, proxy);
        ThreadPoolExecutor w = workers;
        try {
            if (w == null) {
                throw new RejectedExecutionException();
            }
            w.execute(conn);
        } catch (RejectedExecutionException e) {
            conn.release();
            rejected.incrementAndGet();
            closeQuietly(s);
        }
    }

    private boolean acquire(String ip, int max) {
        boolean[] ok = {false};
        perIp.compute(ip, (k, v) -> {
            int n = v == null ? 0 : v;
            if (n >= max) {
                return v;
            }
            ok[0] = true;
            return n + 1;
        });
        return ok[0];
    }

    private void release(String ip) {
        perIp.computeIfPresent(ip, (k, v) -> v > 1 ? v - 1 : null);
    }

    /** 每秒檢查卡住的連線；每 10 秒清掉舊的流量計數。 */
    private int ticks;

    private void tick() {
        Limits l = limits;
        long now = System.nanoTime();
        long headerLimit = TimeUnit.SECONDS.toNanos(l.headerTimeoutSeconds());
        long stallLimit = TimeUnit.SECONDS.toNanos(l.stallSeconds());
        // 壓力模式：有連線在排隊時縮短時限，讓卡住的連線更快讓出執行緒（正常下載一直有進度，不受影響）
        ThreadPoolExecutor pool = workers;
        if (pool != null && !pool.getQueue().isEmpty()) {
            headerLimit = Math.min(headerLimit, TimeUnit.SECONDS.toNanos(PRESSURE_HEADER_SECONDS));
            stallLimit = Math.min(stallLimit, TimeUnit.SECONDS.toNanos(PRESSURE_STALL_SECONDS));
        }
        for (Conn c : active) {
            if (c.phase == Phase.HEADERS ? now - c.started > headerLimit : now - c.progress > stallLimit) {
                c.abort();
            }
        }
        if (++ticks % 10 == 0) {
            long second = System.currentTimeMillis() / 1000;
            rate.values().removeIf(w -> w.second < second - 1);
        }
    }

    private boolean allowRate(String ip, int perSecond) {
        long second = System.currentTimeMillis() / 1000;
        Window w = rate.computeIfAbsent(ip, k -> new Window());
        synchronized (w) {
            if (w.second != second) {
                w.second = second;
                w.count = 0;
            }
            return ++w.count <= perSecond;
        }
    }

    private static final class Window {
        long second;
        int count;
    }

    private enum Phase { HEADERS, BODY, HANDLER, WRITE }

    private final class Conn implements Runnable {
        final Socket socket;
        final String ip;
        final boolean proxy;
        volatile Phase phase = Phase.HEADERS;
        volatile long started = System.nanoTime();
        volatile long progress = started;
        volatile Thread worker;
        private boolean released;

        Conn(Socket socket, String ip, boolean proxy) {
            this.socket = socket;
            this.ip = ip;
            this.proxy = proxy;
        }

        synchronized void release() {
            if (!released && !proxy) {
                released = true;
                HubServer.this.release(ip);
            }
        }

        void close() {
            closeQuietly(socket);
        }

        /** 逾時：關閉連線讓阻塞中的讀寫結束；處理器執行中則一併中斷。 */
        void abort() {
            synchronized (this) {
                // 加鎖：確保不會中斷到已經改處理下一個連線的執行緒
                if (phase == Phase.HANDLER && worker != null) {
                    worker.interrupt();
                }
            }
            close();
        }

        @Override
        public void run() {
            worker = Thread.currentThread();
            started = progress = System.nanoTime();
            active.add(this);
            try {
                Limits l = limits;
                socket.setSoTimeout(l.headerTimeoutSeconds() * 1000);
                socket.setTcpNoDelay(true);
                serve(this, l);
            } catch (Exception ignored) {
                // 連線被對方關閉、逾時被切斷等，直接結束
            } finally {
                active.remove(this);
                synchronized (this) {
                    worker = null;
                }
                Thread.interrupted();
                close();
                release();
            }
        }
    }

    // ---------------------------------------------------------------- 請求處理

    private void serve(Conn conn, Limits l) throws IOException {
        InputStream in = new BufferedInputStream(conn.socket.getInputStream(), 4096);
        OutputStream out = conn.socket.getOutputStream();
        int[] budget = {MAX_HEADER_BYTES};

        String line = readLine(in, budget);
        if (line == null) {
            return;
        }
        String[] parts = line.split(" ", -1);
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            sendError(conn, out, 400, false);
            return;
        }
        Map<String, String> headers = new HashMap<>();
        while (true) {
            String h = readLine(in, budget);
            if (h == null) {
                sendError(conn, out, budget[0] <= 0 ? 431 : 400, false);
                return;
            }
            if (h.isEmpty()) {
                break;
            }
            int colon = h.indexOf(':');
            if (colon <= 0 || headers.size() >= MAX_HEADERS) {
                sendError(conn, out, headers.size() >= MAX_HEADERS ? 431 : 400, false);
                return;
            }
            headers.merge(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim(),
                    (a, b) -> a + ", " + b);
        }

        String client = conn.ip;
        if (conn.proxy) {
            String xff = headers.get("x-forwarded-for");
            if (xff != null) {
                String last = xff.substring(xff.lastIndexOf(',') + 1).trim();
                if (CLIENT_IP.matcher(last).matches()) {
                    client = last;
                }
            }
        }
        if (!allowRate(client, l.requestsPerSecond())) {
            rejected.incrementAndGet();
            sendError(conn, out, 429, false);
            return;
        }

        String method = parts[0];
        boolean head = method.equals("HEAD");
        if (!method.equals("GET") && !head && !method.equals("POST")) {
            sendError(conn, out, 405, false);
            return;
        }

        String target = parts[1];
        int q = target.indexOf('?');
        String rawPath = q >= 0 ? target.substring(0, q) : target;
        String rawQuery = q >= 0 ? target.substring(q + 1) : null;
        String path = decodePath(rawPath);
        if (path == null) {
            sendError(conn, out, 400, head);
            return;
        }

        byte[] body = new byte[0];
        if (method.equals("POST")) {
            String cl = headers.get("content-length");
            if (cl == null || headers.containsKey("transfer-encoding")) {
                sendError(conn, out, 411, false);
                return;
            }
            long len;
            try {
                len = Long.parseLong(cl);
            } catch (NumberFormatException e) {
                sendError(conn, out, 400, false);
                return;
            }
            if (len < 0 || len > l.maxBodyBytes()) {
                sendError(conn, out, 413, false);
                return;
            }
            conn.phase = Phase.BODY;
            conn.progress = System.nanoTime();
            body = readBody(conn, in, (int) len);
            if (body == null) {
                return;
            }
        }

        RouteTable.Match match = routes.find(path);
        if (match == null || !match.route().active()) {
            sendError(conn, out, 404, head);
            return;
        }
        RouteImpl route = match.route();
        Response response;
        if (route.kind() == RouteImpl.Kind.STATIC) {
            if (method.equals("POST")) {
                sendError(conn, out, 405, false);
                return;
            }
            RouteImpl.Content c = route.content();
            response = Response.of(200, c.bytes(), c.type());
        } else {
            conn.phase = Phase.HANDLER;
            conn.progress = System.nanoTime();
            try {
                response = route.handler().handle(new RequestImpl(method, path, match.subPath(), rawQuery,
                        headers, body, client));
            } catch (Throwable t) {
                response = null;
                logError(route, t);
            }
            if (Thread.interrupted() || conn.socket.isClosed()) {
                return;
            }
            if (response == null) {
                sendError(conn, out, 500, head);
                return;
            }
        }
        write(conn, out, response.status(), response.contentType(), response.headers(), response.body(), head);
    }

    private void logError(RouteImpl route, Throwable t) {
        long now = System.nanoTime();
        if (now - route.lastErrorLog < ERROR_LOG_INTERVAL && route.lastErrorLog != 0) {
            return;
        }
        route.lastErrorLog = now;
        // 錯誤內容可能含有外部輸入：去掉換行等控制字元並限制長度，避免偽造後台紀錄
        String reason = String.valueOf(t).replaceAll("[\\p{Cntrl}]", " ");
        if (reason.length() > 300) {
            reason = reason.substring(0, 300) + "…";
        }
        errorLog.accept(route.owner().getName() + " " + route.path(), reason);
    }

    private byte[] readBody(Conn conn, InputStream in, int len) throws IOException {
        byte[] body = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(body, off, Math.min(CHUNK, len - off));
            if (n < 0) {
                return null;
            }
            off += n;
            conn.progress = System.nanoTime();
        }
        return body;
    }

    /** 讀一行（ISO-8859-1），超過剩餘的標頭大小時回傳 null。 */
    private static String readLine(InputStream in, int[] budget) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            if (--budget[0] < 0) {
                return null;
            }
            if (b == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            sb.append((char) b);
        }
    }

    /** 解碼並檢查路徑；含控制字元、//、.、.. 一律拒絕。 */
    static String decodePath(String raw) {
        if (raw.isEmpty() || raw.charAt(0) != '/') {
            return null;
        }
        String path;
        try {
            path = URI.create("http://h" + raw).getPath();
        } catch (Exception e) {
            return null;
        }
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                return null;
            }
        }
        if (path.contains("//") || path.contains("/./") || path.contains("/../")
                || path.endsWith("/.") || path.endsWith("/..")) {
            return null;
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private void sendError(Conn conn, OutputStream out, int status, boolean head) throws IOException {
        byte[] body = errorText.apply(status).getBytes(StandardCharsets.UTF_8);
        Map<String, String> extra = status == 405 ? Map.of("Allow", "GET, HEAD, POST") : Map.of();
        write(conn, out, status, "text/plain; charset=utf-8", extra, body, head);
    }

    private void write(Conn conn, OutputStream out, int status, String type, Map<String, String> extra,
                       byte[] body, boolean head) throws IOException {
        conn.phase = Phase.WRITE;
        conn.progress = System.nanoTime();
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        for (Map.Entry<String, String> e : extra.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (k.equals("content-length") || k.equals("content-type") || k.equals("connection")
                    || k.equals("transfer-encoding")) {
                continue;
            }
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (!head) {
            for (int off = 0; off < body.length; off += CHUNK) {
                int n = Math.min(CHUNK, body.length - off);
                out.write(body, off, n);
                conn.progress = System.nanoTime();
                bytesOut.addAndGet(n);
            }
        }
        out.flush();
        served.incrementAndGet();
    }

    static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 411 -> "Length Required";
            case 413 -> "Content Too Large";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ---------------------------------------------------------------- Request 實作

    private static final class RequestImpl implements Request {
        private final String method;
        private final String path;
        private final String subPath;
        private final String rawQuery;
        private final Map<String, String> headers;
        private final byte[] body;
        private final String remote;
        private Map<String, List<String>> queries;

        RequestImpl(String method, String path, String subPath, String rawQuery, Map<String, String> headers,
                    byte[] body, String remote) {
            this.method = method;
            this.path = path;
            this.subPath = subPath;
            this.rawQuery = rawQuery;
            this.headers = headers;
            this.body = body;
            this.remote = remote;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String subPath() {
            return subPath;
        }

        @Override
        public String query(String name) {
            List<String> v = queries().get(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        @Override
        public synchronized Map<String, List<String>> queries() {
            if (queries == null) {
                Map<String, List<String>> map = new LinkedHashMap<>();
                if (rawQuery != null && !rawQuery.isEmpty()) {
                    int count = 0;
                    for (String pair : rawQuery.split("&")) {
                        if (pair.isEmpty() || ++count > 100) {
                            continue;
                        }
                        int eq = pair.indexOf('=');
                        String k = decode(eq >= 0 ? pair.substring(0, eq) : pair);
                        String v = decode(eq >= 0 ? pair.substring(eq + 1) : "");
                        if (k != null && v != null) {
                            map.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
                        }
                    }
                }
                map.replaceAll((k, v) -> Collections.unmodifiableList(v));
                queries = Collections.unmodifiableMap(map);
            }
            return queries;
        }

        private static String decode(String s) {
            try {
                return URLDecoder.decode(s, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        @Override
        public String header(String name) {
            return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public byte[] body() {
            return body;
        }

        @Override
        public String remoteAddress() {
            return remote;
        }
    }
}
