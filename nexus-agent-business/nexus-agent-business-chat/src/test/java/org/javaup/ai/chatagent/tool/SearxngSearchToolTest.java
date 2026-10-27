package org.javaup.ai.chatagent.tool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.javaup.ai.chatagent.config.SearxngSearchProperties;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearxngSearchToolTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsJsonResultsAndKeepsOnlyDistinctWebUrlsWithinLimit() throws IOException {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                {"results":[
                  {"title":"第一篇","url":"https://example.org/a","content":"第一条摘要","engine":"duckduckgo"},
                  {"title":"重复","url":"https://example.org/a","content":"重复摘要"},
                  {"title":"本地文件","url":"file:///tmp/a","content":"应被过滤"},
                  {"title":"第二篇","url":"https://example.org/b","content":"第二条摘要"},
                  {"title":"第三篇","url":"https://example.org/c","content":"第三条摘要"}
                ],"number_of_results":5}
                """);
        });
        server.start();

        WebSearchToolResult result = tool().search(new WebSearchRequest("ReAct 智能体", 2), null);

        Map<String, String> parameters = Arrays.stream(rawQuery.get().split("&"))
            .map(part -> part.split("=", 2))
            .collect(Collectors.toMap(part -> part[0], part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
        assertEquals("ReAct 智能体", parameters.get("q"));
        assertEquals("json", parameters.get("format"));
        assertEquals("zh-CN", parameters.get("language"));
        assertEquals(2, result.results().size());
        assertEquals("https://example.org/a", result.results().get(0).getUrl());
        assertEquals("https://example.org/b", result.results().get(1).getUrl());
        assertEquals("web_search", result.results().get(0).getToolName());
        assertEquals("第一条摘要", result.results().get(0).getSnippet());
    }

    @Test
    void explainsWhenJsonOutputIsDisabled() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> respond(exchange, 403, "Forbidden"));
        server.start();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> tool().search(new WebSearchRequest("test", 1), null));
        assertTrue(exception.getMessage().contains("search.formats"));
    }

    private SearxngSearchTool tool() {
        SearxngSearchProperties properties = new SearxngSearchProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new SearxngSearchTool(properties, new StreamEventWriter(new ObjectMapper()));
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
