# HttpHub

HttpHub 是一款專為 Paper 伺服器設計的共用 HTTP 伺服器插件。很多插件需要 HTTP（發送資源包、網頁面板、API），如果每個插件各自開一個端口，不但要開一堆防火牆，還很容易互相搶同一個端口而啟動失敗。裝了 HttpHub 之後，所有支援的插件都掛在同一個端口底下，各自使用自己的路徑，並由 HttpHub 統一處理連線安全與防護。

## 功能特性

* **一個端口給所有插件**：
  * 每個插件的內容放在 `/<插件名稱>/` 底下（例如 `/customdisc/pack.zip`），不會互相覆蓋。
  * 插件停用時，它註冊的路徑自動移除。
  * `/httphub reload` 換端口時直接重開伺服器，已註冊的路徑全部保留。
* **網址自動跟著玩家**：
  * `public-url` 預設為 `auto`：玩家用什麼位址連進伺服器，下載網址就用那個位址，不用寫死 IP 或網域。
  * 放在 Nginx 等反向代理後面時，填完整網址即可。
* **內建防護**：
  * 同一 IP 的同時連線數、每秒請求數都有上限，超過直接拒絕。
  * 請求標頭有大小與時間限制，傳送或處理中連續沒有進度就切斷，防止故意慢慢傳的連線佔住伺服器。
  * 有連線在排隊時自動進入壓力模式，縮短時限讓卡住的連線更快讓出位置；正常下載不受影響。
  * 處理執行緒與排隊數量固定，不會因為大量連線而無限增加。
  * 只提供插件放在記憶體裡的內容，不開放伺服器上的任何資料夾。
* **所有文字都可自訂**：
  * 聊天訊息、瀏覽器看到的錯誤頁面、後台訊息全部放在 `messages.yml`。
  * `config.yml` 與 `messages.yml` 缺少的項目會在啟動與 `/httphub reload` 時自動補上，保留原本的值與註解。
* **版本檢查**：
  * 啟動時在後台向 GitHub Releases 檢查一次，也可以用 `/httphub version` 手動檢查；結果快取 10 分鐘，不會定時輪詢。
  * 有新版本時，具有 `httphub.version` 權限的管理員登入 3 秒後會收到提醒；兩項功能都能在 `config.yml` 關閉。

## 指令與權限

本插件主指令為 `/httphub`。

| 指令 | 說明 | 執行端 | 權限節點 | 預設 |
| :--- | :--- | :--- | :--- | :--- |
| `/httphub` 或 `/httphub help` | 顯示指令說明清單 | 遊戲內 / 控制台 | `httphub.help` | 所有人 |
| `/httphub version` | 查看插件版本並檢查更新，有新版時登入會收到提醒 | 遊戲內 / 控制台 | `httphub.version` | OP |
| `/httphub status` | 查看伺服器狀態、流量統計與各插件註冊的路徑 | 遊戲內 / 控制台 | `httphub.status` | OP |
| `/httphub reload` | 重新載入設定，端口有變時重開伺服器 | 遊戲內 / 控制台 | `httphub.reload` | OP |

> 指令別名：`/hhub` 等同 `/httphub`。`httphub.*` 包含以上所有權限。

## 安裝與設定

1. 把 jar 放進 `plugins/`，啟動一次伺服器。
2. 在防火牆開放 `server.port`（預設 `52010`，TCP）。
3. 支援 HttpHub 的插件會自動改用它，不需要另外設定。

| 設定 | 說明 |
| :--- | :--- |
| `prefix` | 所有訊息的開頭文字（其他文字都在 `messages.yml`） |
| `server.host` / `port` | 綁定的位址與端口 |
| `server.public-url` | 對外網址；`auto` = 使用玩家連線的位址 |
| `server.threads` / `queue` | 處理執行緒數量、最多排隊的連線數 |
| `limits.max-connections-per-ip` | 同一 IP 同時最多幾個連線 |
| `limits.requests-per-second` | 同一 IP 每秒最多幾個請求 |
| `limits.stall-seconds` | 連續幾秒沒有進度就切斷 |
| `limits.header-timeout-seconds` | 讀取請求標頭的時限 |
| `limits.max-body-kb` | POST 內容上限 |
| `limits.trusted-proxies` | 反向代理的 IP（沒有使用反向代理請保持空白） |
| `update-checker.check-on-startup` | 啟動時是否檢查更新 |
| `update-checker.notify-on-join` | 有新版時是否在管理員登入後提醒 |

## 開發者：讓你的插件使用 HttpHub

1. 加入依賴（[JitPack](https://jitpack.io/#clre20/HttpHub)）：

```xml
<repository>
    <id>jitpack</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.clre20</groupId>
    <artifactId>HttpHub</artifactId>
    <version>v1.0.0</version>
    <scope>provided</scope>
</dependency>
```

2. 在 `plugin.yml` 加上 `softdepend: [ HttpHub ]`（一定要裝才能用的話改成 `depend`）。

3. 把用到 HttpHub 的程式碼放在獨立的類別裡，確認 HttpHub 已啟用才建立它，沒裝 HttpHub 時才不會出現 `NoClassDefFoundError`：

```java
// 主類別：不要直接 import HttpHub 的類別
Plugin hub = getServer().getPluginManager().getPlugin("HttpHub");
if (hub != null && hub.isEnabled()) {
    web = new HubWeb(this);      // 只有這個類別用到 HttpHub
} else {
    // 沒裝 HttpHub：自己開伺服器或停用網頁功能
}
```

```java
import clre20.httpHub.api.*;

final class HubWeb {
    private final HttpHubService hub = HttpHubService.get();
    private final Route pack;

    HubWeb(JavaPlugin plugin) {
        // 靜態內容：網址是 /<插件名稱小寫>/pack.zip
        pack = hub.serve(plugin, "pack.zip", bytes, "application/zip");

        // 動態內容（完全相同的路徑）
        hub.handle(plugin, "status.json", req -> Response.json(200, "{\"online\":" + online + "}"));

        // 動態內容（含底下所有路徑）：/<插件>/api/...，req.subPath() 是後面的部分
        hub.handlePrefix(plugin, "api", req -> Response.text(200, "你要的是 " + req.subPath()));
    }

    void update(byte[] newBytes) {
        pack.update(newBytes);       // 換內容，網址不變
    }

    String urlFor(Player player) {
        return hub.url(player, pack); // 例如 http://play.example.com:52010/myplugin/pack.zip，取不到位址時為 null
    }
}
```

注意事項：

* 路徑只能用英數字與 `. _ - ~ /`，不能有 `..`、`//`，否則丟出 `IllegalArgumentException`。同一個插件註冊相同路徑時，新的取代舊的。
* 插件停用時路徑會自動移除，不需要自己清。
* 動態處理器在 HttpHub 的執行緒上執行，**不是主執行緒**：要讀取世界、玩家等資料時，請在主執行緒先準備好快取。處理超過 `stall-seconds` 會被中斷，丟出例外則回應 500。
* `Request` 的所有內容（路徑、參數、標頭、內容）都來自外部，使用前請自行驗證；需要權限的頁面請自行做驗證。
* 資源包網址可以在後面加上 `?v=<雜湊值>`，內容更新時網址也會不同。

---

# HttpHub

HttpHub is a shared HTTP server plugin for Paper . Many plugins need HTTP (resource pack delivery, web panels, APIs). When each plugin opens its own port you have to open many firewall ports, and plugins easily fail to start because they fight over the same one. With HttpHub, every supporting plugin runs under one port with its own path, and HttpHub handles connection safety and protection for all of them.

## Features

* **One port for every plugin**:
  * Each plugin's content lives under `/<plugin name>/` (e.g. `/customdisc/pack.zip`), so plugins never overwrite each other.
  * When a plugin is disabled, its routes are removed automatically.
  * `/httphub reload` restarts the server when the port changes; all registered routes are kept.
* **URLs follow the player**:
  * `public-url` defaults to `auto`: download URLs use whatever address the player connected with, so you never hard-code an IP or domain.
  * Behind a reverse proxy such as Nginx, just set the full URL.
* **Built-in protection**:
  * Concurrent connections and requests per second are capped per IP; excess requests are rejected.
  * Request headers are limited in size and time, and connections that make no progress while sending or processing are cut, so deliberately slow clients cannot tie up the server.
  * When connections are queued, a pressure mode shortens the time limits so stuck connections give up their slot sooner; normal downloads are not affected.
  * Worker threads and the queue have a fixed size and never grow with connection count.
  * Only content that plugins hold in memory is served; no folder on the server is ever exposed.
* **Every message is customizable**:
  * Chat messages, error pages shown in browsers, and console messages all live in `messages.yml`.
  * Missing keys in `config.yml` and `messages.yml` are filled in on startup and `/httphub reload`, keeping your values and comments.
* **Update checker**:
  * Checks GitHub Releases once in the background on startup, or manually with `/httphub version`; results are cached for 10 minutes and never polled.
  * When a new version exists, admins with `httphub.version` are notified 3 seconds after joining; both features can be turned off in `config.yml`.

## Supported Plugins

* [CustomDisc](https://github.com/clre20/CustomDisc): resource pack delivery for custom music discs and horns.

## Commands & Permissions

The main command is `/httphub`.

| Command | Description | Executor | Permission | Default |
| :--- | :--- | :--- | :--- | :--- |
| `/httphub` or `/httphub help` | Show the command list | In-game / Console | `httphub.help` | Everyone |
| `/httphub version` | Show the plugin version and check for updates; admins are notified on join | In-game / Console | `httphub.version` | OP |
| `/httphub status` | Show server state, traffic stats and every plugin's routes | In-game / Console | `httphub.status` | OP |
| `/httphub reload` | Reload settings; restarts the server if the port changed | In-game / Console | `httphub.reload` | OP |

> Alias: `/hhub` is the same as `/httphub`. `httphub.*` grants all permissions above.

## Installation & Configuration

1. Put the jar in `plugins/` and start the server once.
2. Open `server.port` (default `52010`, TCP) in your firewall.
3. Plugins that support HttpHub switch to it automatically; nothing else to configure.

| Option | Description |
| :--- | :--- |
| `prefix` | Prefix for all plugin messages (all other text is in `messages.yml`) |
| `server.host` / `port` | Bind address and port |
| `server.public-url` | Public URL; `auto` = the player's connection address |
| `server.threads` / `queue` | Worker thread count and maximum queued connections |
| `limits.max-connections-per-ip` | Maximum concurrent connections per IP |
| `limits.requests-per-second` | Maximum requests per second per IP |
| `limits.stall-seconds` | Cut a connection after this many seconds without progress |
| `limits.header-timeout-seconds` | Time limit for reading request headers |
| `limits.max-body-kb` | Maximum POST body size |
| `limits.trusted-proxies` | Reverse proxy IPs (leave empty if you don't use one) |
| `update-checker.check-on-startup` | Check for updates on startup |
| `update-checker.notify-on-join` | Remind admins on join when a new version exists |

## Developers: Using HttpHub in Your Plugin

1. Add the dependency ([JitPack](https://jitpack.io/#clre20/HttpHub)):

```xml
<repository>
    <id>jitpack</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.clre20</groupId>
    <artifactId>HttpHub</artifactId>
    <version>v1.0.0</version>
    <scope>provided</scope>
</dependency>
```

2. Add `softdepend: [ HttpHub ]` to `plugin.yml` (use `depend` if your plugin requires it).

3. Keep the code that uses HttpHub in a separate class and only create it once HttpHub is enabled, so servers without HttpHub never hit `NoClassDefFoundError`:

```java
// Main class: do not import HttpHub classes here
Plugin hub = getServer().getPluginManager().getPlugin("HttpHub");
if (hub != null && hub.isEnabled()) {
    web = new HubWeb(this);      // the only class that touches HttpHub
} else {
    // No HttpHub: run your own server or disable web features
}
```

```java
import clre20.httpHub.api.*;

final class HubWeb {
    private final HttpHubService hub = HttpHubService.get();
    private final Route pack;

    HubWeb(JavaPlugin plugin) {
        // Static content: served at /<lowercase plugin name>/pack.zip
        pack = hub.serve(plugin, "pack.zip", bytes, "application/zip");

        // Dynamic content (exact path)
        hub.handle(plugin, "status.json", req -> Response.json(200, "{\"online\":" + online + "}"));

        // Dynamic content (path and everything below): /<plugin>/api/..., req.subPath() is the rest
        hub.handlePrefix(plugin, "api", req -> Response.text(200, "You asked for " + req.subPath()));
    }

    void update(byte[] newBytes) {
        pack.update(newBytes);       // replace the content; the URL stays the same
    }

    String urlFor(Player player) {
        return hub.url(player, pack); // e.g. http://play.example.com:52010/myplugin/pack.zip, or null if unknown
    }
}
```

Notes:

* Paths may only use letters, digits and `. _ - ~ /`, and may not contain `..` or `//`; otherwise `IllegalArgumentException` is thrown. Registering the same path again from the same plugin replaces the old route.
* Routes are removed automatically when your plugin is disabled.
* Dynamic handlers run on HttpHub's threads, **not the main thread**: prepare a cache on the main thread for world or player data. Handlers running longer than `stall-seconds` are interrupted, and exceptions produce a 500 response.
* Everything in `Request` (path, query, headers, body) comes from outside; validate it before use, and add your own authentication for protected pages.
* You can append `?v=<hash>` to resource pack URLs so the URL changes whenever the content does.
