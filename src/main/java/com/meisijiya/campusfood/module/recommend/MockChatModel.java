package com.meisijiya.campusfood.module.recommend;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.meisijiya.campusfood.common.TokenEstimator;

/**
 * Mock ChatModel(F-1 占位,F-2 增强暴露 prompt token,F-3 完整实现)。
 *
 * <p>F-2 增强:调用 {@link #call(Prompt)} 时按
 * {@link TokenEstimator#estimateTokens} 估算 prompt token 数,塞进
 * {@link ChatResponse#getMetadata()}{@code .usage.promptTokens}。
 * F-2 evidence 采集阶段用此口径计算"引入 Skill 前后 prompt token 数"。
 *
 * <p>F-3 真实接入百炼时此估算由真实 API 的 metadata 覆盖;Mock profile 不消耗 token。
 *
 * <p>实现注意:Spring AI 1.1.x 的 {@code ChatModel} 接口约定
 * {@link #call(Prompt)} 返回 {@link ChatResponse},{@link #call(Message...)} 返回 {@link String}。
 *
 * @author meisijiya
 */
@Component
public class MockChatModel implements ChatModel {

    private static final String MOCK_RECOMMENDATION = """
            {
              "merchantId": ["m-001"],
              "reason": "Mock 推荐:F-1 占位返回,F-3 阶段会接入真实百炼",
              "confidence": 0.85
            }
            """;

    @Override
    public ChatResponse call(Prompt prompt) {
        AssistantMessage message = new AssistantMessage(MOCK_RECOMMENDATION);

        int promptTokens = TokenEstimator.estimateTokens(prompt.getInstructions());
        int completionTokens = TokenEstimator.estimateTokens(MOCK_RECOMMENDATION);
        Usage usage = new DefaultUsage(promptTokens, completionTokens);

        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id("mock-" + Long.toHexString(System.nanoTime()))
                .model("mock-chat-model")
                .usage(usage)
                .build();

        return new ChatResponse(List.of(new Generation(message)), metadata);
    }

    @Override
    public String call(Message... messages) {
        ChatResponse response = call(new Prompt(List.of(messages)));
        return response.getResult().getOutput().getText();
    }
}