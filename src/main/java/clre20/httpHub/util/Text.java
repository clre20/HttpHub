package clre20.httpHub.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** 文字工具：& 顏色碼（含 &#RRGGBB）轉成 Adventure Component。 */
public final class Text {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Text() {
    }

    public static Component color(String text) {
        return AMPERSAND.deserialize(text == null ? "" : text);
    }

    /** 去掉顏色碼後的純文字。 */
    public static String plain(String text) {
        return PlainTextComponentSerializer.plainText().serialize(color(text));
    }
}
