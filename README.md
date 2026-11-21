# HttpHub

HttpHub 是一款專為 Paper 26.x 伺服器設計的共用 HTTP 伺服器與集中外聯 HTTP 網關插件。

在伺服器運作中，許多插件需要提供 HTTP 服務（例如發送自訂唱片資源包、提供網頁面板、Webhook 接收）或對外發送 HTTP 請求（例如檢查 GitHub Releases 更新、呼叫第三方 API）。若各插件各自開啟一個端口，將造成防火牆設定混亂並容易引發端口衝突；若各插件各自維護獨立的 `HttpClient` 與執行緒池，更會導致 JVM 虛擬執行緒與 Selector 數量失控。

裝了 HttpHub 之後：
1. **內部掛載**：所有支援的插件都共用同一個 HTTP 服務端口，各自掛載於專屬子路徑（如 `/<插件名稱>/...`）。
2. **集中外聯**：所有外部插件皆可透過 `HttpHubAPI` 發起非同步 HTTP 請求與登記更新檢查，全服共用由 **Java 25 原生虛擬執行緒池（Virtual Threads）** 驅動的單例 `HttpClient`，大幅節省線程開銷與記憶體。

---

## 功能特性

* **一個端口給所有插件**：
  * 每個插件的內容放在 `/<插件名稱>/` 底下（例如 `/customdisc/pack.zip`），路徑互不干擾。
  * 插件停用時，它註冊的路徑會自動安全卸載。
  * 支援 `/httphub reload`，更換端口時無縫重載。
* **集中外聯 HTTP 請求接口（API）**：
  * 提供 `HttpHubAPI.getStringAsync()` 與 `HttpHubAPI.sendAsync()`。
  * 全伺服器共用單一 `HttpClient` 與 Java 25 虛擬執行緒池，徹底杜絕各插件自建 Selector 線程池造成的線程爆炸。
* **集中版本檢查登記（Update Checker Hub）**：
  * 提供 `HttpHubAPI.registerUpdateCheck()`，其他外掛無須自行維護連線輪詢與背景線程。
  * 由 HttpHub 統一在後台向 GitHub Releases 查詢最新發行版，並由 HttpHub 自動於具備權限之管理員登入 3 秒後發送聊天室提醒。
* **操作與調用稽核日誌（Audit Logging）**：
  * 外部插件透過 HttpHub 進行的外聯請求與更新登記均會輸出清晰的 `[HttpHub] [Audit]` 日誌，透明紀錄連線來源與目標。
* **`/httphub status` 狀態面板**：
  * 即時列出伺服器運作狀態、各插件掛載端點、目前已連線/調用 API 的外部插件清單、請求計數與版本檢查結果。
* **動態網址自動適配**：
  * `public-url` 預設為 `auto`：玩家用什麼 IP 或網域連入伺服器，下載資源包就使用該網址，無須手動寫死。
  * 支援放在 Nginx、Cloudflare 等反向代理後方。
* **內建防護機制**：
  * 單一 IP 連線速率限制（Rate Limit）、請求標頭超時斷線機制、固定執行緒保護，防止惡意連線拖垮伺服器。

---

## 支援環境

* **伺服器核心**：Paper 26.3（標準穩定版）及更高版本（Java 25）
* **相容核心**：Purpur 等標準 Paper 分支

---

## 指令與權限

本插件主指令為 `/httphub`（別名 `/hhub`）。

| 指令 | 說明 | 執行端 | 權限節點 | 預設 |
| :--- | :--- | :--- | :--- | :--- |
| `/httphub` 或 `/httphub help` | 顯示指令說明清單 | 遊戲內 / 控制台 | `httphub.help` | 所有人 |
| `/httphub status` | 查看伺服器狀態、掛載路徑、已連接外部插件與更新檢查統計 | 遊戲內 / 控制台 | `httphub.status` | OP |
| `/httphub version` | 查看插件版本並檢查更新 | 遊戲內 / 控制台 | `httphub.version` | OP |
| `/httphub reload` | 重新載入設定檔 | 遊戲內 / 控制台 | `httphub.reload` | OP |

---

## 開發者 API：讓你的插件使用 HttpHub

外部插件可以將 HttpHub 作為共用的 HTTP 服務端（提供檔案下載）與集中外聯 HTTP 客戶端（發起請求與檢查更新）。

### 1. Maven 依賴配置

在你的插件 `pom.xml` 中加入：

```xml
<dependency>
    <groupId>clre20</groupId>
    <artifactId>HttpHub</artifactId>
    <version>1.0.1</version>
    <scope>provided</scope>
</dependency>
```

### 2. plugin.yml 配置

加入軟依賴（Softdepend）：

```yaml
softdepend: [ HttpHub ]
```

### 3. Java 程式碼調用範例

#### A. 集中版本檢查登記（推薦所有外掛使用）
省去外掛各自開啟背景連線線程，統一由 HttpHub 查詢並自動在管理員登入 3 秒後通知：

```java
import clre20.httpHub.api.HttpHubAPI;

public class MyPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        if (getServer().getPluginManager().isPluginEnabled("HttpHub") && HttpHubAPI.isAvailable()) {
            HttpHubAPI.registerUpdateCheck(this, "clre20/MyPlugin", getPluginMeta().getVersion(), "myplugin.version", info -> {
                if (info.hasUpdate()) {
                    getLogger().warning("發現新版本: " + info.latestVersion() + " (目前: " + info.currentVersion() + ")");
                }
            });
        }
    }
}
```

#### B. 非同步發送 HTTP GET 請求
使用伺服器共用的虛擬執行緒 HttpClient 發起請求：

```java
HttpHubAPI.getStringAsync(this, "https://api.github.com/zen")
    .thenAccept(body -> {
        getLogger().info("GitHub 回傳: " + body);
    })
    .exceptionally(err -> {
        getLogger().warning("請求失敗: " + err.getMessage());
        return null;
    });
```

#### C. 掛載提供資源包或網頁端點
```java
import clre20.httpHub.api.HttpHubProvider;
import clre20.httpHub.api.HttpServerHub;

HttpServerHub hub = HttpHubProvider.get();
if (hub != null) {
    // 註冊 /myplugin/pack.zip 端點
    hub.registerBytes("myplugin", "pack.zip", "application/zip", zipByteArray);
}
```

---

# HttpHub (English)

HttpHub is a shared HTTP server and centralized outbound HTTP gateway plugin designed for Paper 26.x servers.

In modern Minecraft servers, many plugins require HTTP capabilities (such as serving custom music packs, web dashboards, receiving webhooks) or making outbound HTTP calls (such as GitHub update checks and REST APIs). Running multiple standalone HTTP servers causes port conflicts and firewall overhead; similarly, maintaining independent `HttpClient` instances and thread pools leads to excessive memory consumption and thread explosion.

With HttpHub:
1. **Inbound Routing**: All plugins share a single port, safely routed under dedicated prefixes (e.g. `/<plugin-name>/...`).
2. **Outbound Gateway**: External plugins leverage `HttpHubAPI` for asynchronous requests and centralized update checking, powered by a singleton `HttpClient` on **Java 25 native Virtual Threads**.

---

## Features

* **Single Port for All Plugins**:
  * Dedicated route prefixes per plugin (`/<plugin-name>/...`).
  * Automatic unregistration upon plugin disable.
  * Seamless port reloading via `/httphub reload`.
* **Centralized Outbound HTTP Gateway (API)**:
  * Exposes `HttpHubAPI.getStringAsync()` and `HttpHubAPI.sendAsync()`.
  * Single shared `HttpClient` backed by Java 25 Virtual Threads, preventing OS thread exhaustion.
* **Centralized Update Checking**:
  * Exposes `HttpHubAPI.registerUpdateCheck()`.
  * Background querying via virtual threads with automatic operator login alerts after 3 seconds.
* **Audit Logging**:
  * Outbound requests and registrations generate clear `[HttpHub] [Audit]` console logs for monitoring.
* **`/httphub status` Dashboard**:
  * Real-time metrics showing server status, registered endpoints, connected plugins, outbound request counts, and update statuses.
* **Dynamic Auto URL Adaptation**:
  * Automatic detection of player connection hostnames for seamless resource pack downloading without hardcoded IPs.

---

## Requirements

* **Server Core**: Paper 26.3 (Stable) or higher (Java 25).
* **Compatible Forks**: Purpur and other standard Paper forks.

---

## Commands & Permissions

Primary command: `/httphub` (alias `/hhub`).

| Command | Description | Sender | Permission | Default |
| :--- | :--- | :--- | :--- | :--- |
| `/httphub help` | View command help list | Player / Console | `httphub.help` | Everyone |
| `/httphub status` | View server status, endpoints, connected plugins and update stats | Player / Console | `httphub.status` | OP |
| `/httphub version` | View version and check for updates | Player / Console | `httphub.version` | OP |
| `/httphub reload` | Reload configuration | Player / Console | `httphub.reload` | OP |

---

## Developer API: Using HttpHub in Your Plugin

External plugins can leverage HttpHub as both an inbound file server and an outbound HTTP client.

### 1. Maven Dependency

```xml
<dependency>
    <groupId>clre20</groupId>
    <artifactId>HttpHub</artifactId>
    <version>1.0.1</version>
    <scope>provided</scope>
</dependency>
```

### 2. plugin.yml Configuration

```yaml
softdepend: [ HttpHub ]
```

### 3. Java Usage Examples

#### Centralized Update Check Registration
```java
import clre20.httpHub.api.HttpHubAPI;

if (getServer().getPluginManager().isPluginEnabled("HttpHub") && HttpHubAPI.isAvailable()) {
    HttpHubAPI.registerUpdateCheck(this, "clre20/MyPlugin", getPluginMeta().getVersion(), "myplugin.version", info -> {
        if (info.hasUpdate()) {
            getLogger().warning("New version available: " + info.latestVersion());
        }
    });
}
```

#### Shared Outbound GET Request
```java
HttpHubAPI.getStringAsync(this, "https://api.example.com/data")
    .thenAccept(body -> getLogger().info("Response: " + body));
```
