package com.meisijiya.campusfood.module.recommend;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mock ChatModel(F-1 占位,F-3 完整实现)。
 *
 * <p>输入任意 Prompt,返固定 schema 合规 JSON。F-3 量化证据采集阶段才会切到真实百炼。
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
        return new ChatResponse(List.of(new Generation(message)));
    }

    @Override
    public String call(Message... messages) {
        ChatResponse response = call(new Prompt(List.of(messages)));
        return response.getResult().getOutput().getText();
    }
}