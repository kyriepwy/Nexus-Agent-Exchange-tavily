package org.javaup.ai.chatagent.config;

import javax.sql.DataSource;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.javaup.ai.chatagent.support.DashScopeCompatibilityInterceptor;
import org.javaup.ai.chatagent.support.WebSearchToolInputFallbackInterceptor;
import org.javaup.ai.chatagent.tool.SearxngSearchTool;
import org.javaup.ai.chatagent.tool.WebSearchRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 配置类
 * @author: 阿星不是程序员
 **/
@Configuration
@EnableConfigurationProperties({ChatAgentProperties.class, SearxngSearchProperties.class})
public class ChatAgentConfiguration {

    @Bean
    public MysqlSaver mysqlCheckpointSaver(DataSource dataSource) {

        return MysqlSaver.builder()
            .dataSource(dataSource)
            .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
            .build();
    }

    @Bean
    public ToolCallback webSearchToolCallback(SearxngSearchTool searxngSearchTool) {

        return FunctionToolCallback
            .builder(SearxngSearchTool.TOOL_NAME, searxngSearchTool::search)
            .description("联网搜索最新信息、事实资料和网页来源。调用时传 JSON 参数，必须包含非空 query，可选 maxResults。")
            .inputType(WebSearchRequest.class)
            .build();
    }

    @Bean
    public ReactAgent businessChatReactAgent(ChatModel chatModel,
                                             MysqlSaver mysqlCheckpointSaver,
                                             ToolCallback webSearchToolCallback,
                                             ChatAgentProperties chatAgentProperties,
                                             DashScopeCompatibilityInterceptor dashScopeCompatibilityInterceptor,
                                             WebSearchToolInputFallbackInterceptor webSearchToolInputFallbackInterceptor) {
        return ReactAgent.builder()

            .name("business_chat_agent")
            .model(chatModel)
            .instruction(chatAgentProperties.getSystemPrompt())

            .tools(webSearchToolCallback)
            .saver(mysqlCheckpointSaver)

            .parallelToolExecution(true)
            .maxParallelTools(4)

            .hooks(
                ModelCallLimitHook.builder()
                    .runLimit(chatAgentProperties.getMaxModelCallsPerRun())
                    .threadLimit(chatAgentProperties.getMaxModelCallsPerThread())
                    .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                    .build(),
                ToolCallLimitHook.builder()
                    .toolName(SearxngSearchTool.TOOL_NAME)
                    .runLimit(chatAgentProperties.getMaxToolCallsPerRun())
                    .threadLimit(chatAgentProperties.getMaxToolCallsPerThread())
                    .exitBehavior(ToolCallLimitHook.ExitBehavior.END)
                    .build()
            )

            .interceptors(
                dashScopeCompatibilityInterceptor,
                webSearchToolInputFallbackInterceptor,
                ToolRetryInterceptor.builder()
                    .toolName(SearxngSearchTool.TOOL_NAME)
                    .maxRetries(2)
                    .initialDelay(200L)
                    .maxDelay(1200L)
                    .jitter(true)
                    .onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
                    .build(),
                ToolErrorInterceptor.builder().build()
            )
            .build();
    }
}
