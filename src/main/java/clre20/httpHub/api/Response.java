package clre20.httpHub.api;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** HTTP 回應。 */
public final class Response {

    private static final byte[] EMPTY = new byte[0];
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final int status;
    private final byte[] body;
    private final String contentType;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private Response(int status, byte[] body, String contentType) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status: " + status);
        }
        this.status = status;
        this.body = body == null ? EMPTY : body;
        this.contentType = clean(contentType == null ? "application/octet-stream" : contentType);
    }

    public static Response of(int status, byte[] body, String contentType) {
        return new Response(status, body, contentType);
    }

    public static Response text(int status, String text) {
        return new Response(status, bytes(text), "text/plain; charset=utf-8");
    }

    public static Response html(int status, String html) {
        return new Response(status, bytes(html), "text/html; charset=utf-8");
    }

    public static Response json(int status, String json) {
        return new Response(status, bytes(json), "application/json; charset=utf-8");
    }

    /** 沒有內容的回應，例如 204、404。 */
    public static Response status(int status) {
        return new Response(status, EMPTY, "text/plain; charset=utf-8");
    }

    /**
     * 加上回應標頭。名稱只能用英數字與 -；值裡的換行字元會被移除（避免標頭注入）。
     * Content-Length、Content-Type、Connection 由 HttpHub 處理，設定了也會被忽略。
     */
    public Response header(String name, String value) {
        if (name == null || !HEADER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("header name: " + name);
        }
        headers.put(name, clean(value == null ? "" : value));
        return this;
    }

    public int status() {
        return status;
    }

    public byte[] body() {
        return body;
    }

    public String contentType() {
        return contentType;
    }

    public Map<String, String> headers() {
        return Collections.unmodifiableMap(headers);
    }

    private static byte[] bytes(String s) {
        return s == null ? EMPTY : s.getBytes(StandardCharsets.UTF_8);
    }

    static String clean(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
