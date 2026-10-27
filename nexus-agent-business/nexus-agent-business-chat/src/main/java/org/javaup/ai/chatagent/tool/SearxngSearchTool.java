package org.javaup.ai.chatagent.tool;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.config.SearxngSearchProperties;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.model.debug.ChatToolTrace;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.javaup.ai.chatagent.support.RestClientFactorySupport;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventMetadata;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Sinks;

@Slf4j
@Component
public class SearxngSearchTool {

    public static final String TOOL_NAME = "web_search";
    private static final int MAX_SNIPPET_CHARS = 1000;

    private final SearxngSearchProperties properties;
    private final StreamEventWriter streamEventWriter;
    private final RestClient restClient;

    public SearxngSearchTool(SearxngSearchProperties properties, StreamEventWriter streamEventWriter) {
        this.properties = properties;
        this.streamEventWriter = streamEventWriter;
        this.restClient = RestClientFactorySupport.create(
            properties.getBaseUrl(), properties.getConnectTimeoutMs(), properties.getReadTimeoutMs());
    }

    public WebSearchToolResult search(WebSearchRequest request, ToolContext toolContext) {
        String rawQuery = request == null ? "" : StrUtil.trimToEmpty(request.getQuery());
        if (rawQuery.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (!properties.isEnabled()) {
            throw new IllegalStateException("SearXNG 搜索工具当前已禁用");
        }

        long startedAt = System.currentTimeMillis();
        String effectiveQuery = TimeSensitiveQueryHelper.buildEffectiveSearchQuery(rawQuery, resolveCurrentDate(toolContext));
        ChatToolTrace trace = registerToolTrace(toolContext, ChatToolTrace.builder()
            .toolName(TOOL_NAME)
            .status("RUNNING")
            .inputSummary(rawQuery)
            .effectiveInput(effectiveQuery)
            .build());
        markToolUsed(toolContext);
        publishThinking(toolContext, "🔍 正在联网搜索: " + rawQuery);

        try {
            SearxngResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(properties.getSearchPath())
                    .queryParam("q", effectiveQuery)
                    .queryParam("format", "json")
                    .queryParam("language", properties.getLanguage())
                    .build())
                .retrieve()
                .body(SearxngResponse.class);
            if (response == null || response.results() == null) {
                throw new IllegalStateException("SearXNG 未返回有效的 JSON 搜索结果");
            }

            int configuredLimit = Math.max(1, properties.getMaxResults());
            int requestedLimit = request.getMaxResults() == null || request.getMaxResults() <= 0
                ? configuredLimit : request.getMaxResults();
            List<SearchReference> references = toReferences(response.results(), Math.min(requestedLimit, configuredLimit));
            appendReferences(toolContext, references);
            publishThinking(toolContext, "📚 搜索完成，找到 " + references.size() + " 条候选来源");
            completeToolTrace(trace, references.size(), startedAt);
            return new WebSearchToolResult(effectiveQuery, "", List.copyOf(references));
        }
        catch (HttpClientErrorException.Forbidden exception) {
            IllegalStateException failure = new IllegalStateException(
                "SearXNG 拒绝 JSON 搜索，请在 settings.yml 的 search.formats 中启用 json", exception);
            failToolTrace(trace, failure, startedAt);
            publishThinking(toolContext, "⚠️ 搜索失败: " + failure.getMessage());
            throw failure;
        }
        catch (RuntimeException exception) {
            failToolTrace(trace, exception, startedAt);
            publishThinking(toolContext, "⚠️ 搜索失败: " + exception.getMessage());
            log.warn("SearXNG 搜索失败, query={}", rawQuery, exception);
            throw exception;
        }
    }

    private List<SearchReference> toReferences(List<SearxngResult> results, int limit) {
        List<SearchReference> references = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (SearxngResult item : results) {
            if (item == null || StrUtil.isBlank(item.url())) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(item.url().trim());
            }
            catch (IllegalArgumentException exception) {
                continue;
            }
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
                continue;
            }
            String url = uri.toString();
            if (!seenUrls.add(url)) {
                continue;
            }
            String title = StrUtil.blankToDefault(item.title(), url);
            String content = StrUtil.trimToEmpty(item.content());
            if (content.length() > MAX_SNIPPET_CHARS) {
                content = content.substring(0, MAX_SNIPPET_CHARS) + "...";
            }
            SearchReference reference = new SearchReference(title, url, content);
            reference.setToolName(TOOL_NAME);
            references.add(reference);
            if (references.size() >= limit) {
                break;
            }
        }
        return references;
    }

    private RunnableConfig runnableConfig(ToolContext toolContext) {
        return toolContext == null ? null : ToolContextHelper.getConfig(toolContext).orElse(null);
    }

    private String resolveCurrentDate(ToolContext toolContext) {
        RunnableConfig config = runnableConfig(toolContext);
        if (config == null) {
            return "";
        }
        Object date = config.context().get(ChatContextKeys.CURRENT_DATE);
        return date instanceof String text ? text.trim() : "";
    }

    @SuppressWarnings("unchecked")
    private void appendReferences(ToolContext toolContext, List<SearchReference> references) {
        RunnableConfig config = runnableConfig(toolContext);
        if (config != null && config.context().get(ChatContextKeys.REFERENCES) instanceof List<?> list) {
            ((List<SearchReference>) list).addAll(references);
        }
    }

    @SuppressWarnings("unchecked")
    private void markToolUsed(ToolContext toolContext) {
        RunnableConfig config = runnableConfig(toolContext);
        if (config != null && config.context().get(ChatContextKeys.USED_TOOLS) instanceof Set<?> tools) {
            ((Set<String>) tools).add(TOOL_NAME);
        }
    }

    @SuppressWarnings("unchecked")
    private void publishThinking(ToolContext toolContext, String text) {
        RunnableConfig config = runnableConfig(toolContext);
        if (config == null) {
            return;
        }
        Object sink = config.context().get(ChatContextKeys.EVENT_SINK);
        Object metadata = config.context().get(ChatContextKeys.EVENT_METADATA);
        if (sink instanceof Sinks.Many<?> many) {
            SinkEmitHelper.emitNext((Sinks.Many<String>) many,
                streamEventWriter.thinking(text, metadata instanceof StreamEventMetadata eventMetadata ? eventMetadata : null));
        }
        if (config.context().get(ChatContextKeys.THINKING_STEPS) instanceof List<?> steps) {
            ((List<String>) steps).add(text);
        }
    }

    private ChatToolTrace registerToolTrace(ToolContext toolContext, ChatToolTrace trace) {
        RunnableConfig config = runnableConfig(toolContext);
        if (config != null && config.context().get(ChatContextKeys.DEBUG_TRACE) instanceof ChatDebugTrace debugTrace) {
            debugTrace.getToolTraces().add(trace);
        }
        return trace;
    }

    private void completeToolTrace(ChatToolTrace trace, int count, long startedAt) {
        trace.setStatus("COMPLETED");
        trace.setReferenceCount(count);
        trace.setDurationMs(Math.max(0L, System.currentTimeMillis() - startedAt));
        trace.setOutputSummary("联网结果已返回，候选来源 " + count + " 条");
    }

    private void failToolTrace(ChatToolTrace trace, RuntimeException exception, long startedAt) {
        trace.setStatus("FAILED");
        trace.setDurationMs(Math.max(0L, System.currentTimeMillis() - startedAt));
        trace.setErrorMessage(StrUtil.blankToDefault(exception.getMessage(), ""));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearxngResponse(List<SearxngResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearxngResult(String title, String url, String content) {
    }
}
