# 本地 SearXNG 联网搜索

聊天 Agent 注册的联网工具为 `web_search`，由 `SearxngSearchTool` 请求 SearXNG 的 JSON 搜索接口。工具把结果中的标题、网页地址和摘要转换为 `SearchReference`，并沿用现有的 SSE 思考事件、引用、工具追踪、调用上限和重试机制。当前版本使用搜索摘要，不主动抓取结果页全文。

1. 在 SearXNG 的 `settings.yml` 中确认 `search.formats` 包含 `json`。可先访问 `http://127.0.0.1:8080/search?q=java&format=json`，应返回带 `results` 数组的 JSON。
2. 应用默认连接 `http://127.0.0.1:8080`。如实例地址不同，设置环境变量 `SEARXNG_BASE_URL`；Java 服务在容器内时，该地址应为容器可访问的主机名或网络地址。
3. 启动应用后，在开放式聊天模式询问一个需要联网核实的问题。调试面板应看到 `web_search`，回答结束后应有网页引用。

搜索接口请求为 `GET /search?q=...&format=json&language=zh-CN`。配置位于 `app.searxng`，包括 `base-url`、`search-path`、`language`、`max-results`、连接和读取超时。`max-results` 是返回给 Agent 的上限，工具会过滤非 HTTP(S) 地址、按 URL 去重，并截断过长摘要。若接口返回 403，检查 SearXNG 的 JSON 格式配置。

项目根目录的 `Searchxng-test.http` 可直接在 IDE 中运行。SearXNG 的官方接口说明见 <https://docs.searxng.org/dev/search_api.html>。
