package com.meisijiya.campusfood.module.recommend.schema;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;

/**
 * JSON Schema 校验器(F-3;对应 ticket acceptance #2)。
 *
 * <p>用 networknt {@code json-schema-validator:1.5.2}(draft-2020-12),把 {@link JsonSchema#validate(JsonNode)}
 * 的 {@link Set} 拍平成消息列表。失败抛 {@link SchemaViolationException} —
 * 由 {@code ReflectiveRetryAdvisor} 捕获并拼到下一次 user 消息自反思。
 *
 * <p>提供两个 API:
 * <ul>
 *   <li>{@link #validateOrThrow(String)} — 内容来自 AI 响应,失败抛异常</li>
 *   <li>{@link #isValid(String)} — 只读探测,不抛异常(给 {@code RuleBasedFallbackAdvisor} 用)</li>
 * </ul>
 *
 * @author meisijiya
 */
@Component
public class JsonSchemaValidator {

    private final RecommendationSchema recommendationSchema;
    private final ObjectMapper objectMapper;

    public JsonSchemaValidator(RecommendationSchema recommendationSchema) {
        this.recommendationSchema = recommendationSchema;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 校验 AI 响应字符串是否符合 {@link RecommendationSchema};失败抛 {@link SchemaViolationException}。
     *
     * @param aiContent AI 返回的原始文本(可能夹带 markdown ```json ... ``` 围栏)
     * @throws SchemaViolationException 内容不合 schema
     */
    public void validateOrThrow(String aiContent) {
        JsonNode payload = parsePayload(aiContent);
        Set<ValidationMessage> messages = recommendationSchema.schema().validate(payload);
        if (!messages.isEmpty()) {
            List<String> summary = messages.stream()
                    .map(this::formatMessage)
                    .toList();
            throw new SchemaViolationException(summary);
        }
    }

    /**
     * 只读探测 — 是否合规;不抛异常。给 {@code RuleBasedFallbackAdvisor} 和单元测试用。
     */
    public boolean isValid(String aiContent) {
        try {
            JsonNode payload = parsePayload(aiContent);
            return recommendationSchema.schema().validate(payload).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把 AI 输出转成 {@link JsonNode}。
     * 容忍 markdown 围栏(```json ... ``` / ``` ... ```)和首尾空白;真 JSON 解析失败抛 {@link SchemaViolationException}。
     */
    JsonNode parsePayload(String aiContent) {
        if (aiContent == null) {
            throw new SchemaViolationException("AI 响应为空");
        }
        String trimmed = aiContent.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception e) {
            throw new SchemaViolationException("AI 响应不是合法 JSON: " + e.getMessage());
        }
    }

    private String formatMessage(ValidationMessage msg) {
        String path = msg.getInstanceLocation() == null ? "" : msg.getInstanceLocation().toString();
        return (path.isBlank() ? "" : path + ": ") + msg.getMessage();
    }
}