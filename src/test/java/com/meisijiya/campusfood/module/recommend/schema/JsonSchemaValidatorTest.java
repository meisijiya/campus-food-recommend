package com.meisijiya.campusfood.module.recommend.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.meisijiya.campusfood.module.recommend.schema.JsonSchemaValidator;
import com.meisijiya.campusfood.module.recommend.schema.RecommendationSchema;
import com.meisijiya.campusfood.module.recommend.schema.SchemaViolationException;

/**
 * JsonSchemaValidator 单元测试(F-3;对应 ticket acceptance #8 "valid / 失败 / 嵌套错误 路径")。
 *
 * <p>策略:用真实的 {@link RecommendationSchema} + 真实的 networknt validator,
 * 覆盖通过 / 缺字段 / 类型错 / 越界 confidence / 嵌套路径(merchantId 数组内元素)
 * / markdown 围栏容忍 / 非法 JSON 几类样本。
 *
 * @author meisijiya
 */
class JsonSchemaValidatorTest {

    private JsonSchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JsonSchemaValidator(new RecommendationSchema());
    }

    @Test
    @DisplayName("valid:符合 schema 的最小可用 JSON 通过")
    void valid_passes() {
        String json = """
                {"merchantId":["m-001"],"reason":"test","confidence":0.85}
                """;
        validator.validateOrThrow(json);
        assertThat(validator.isValid(json)).isTrue();
    }

    @Test
    @DisplayName("valid:多商户(在 maxItems=20 内)+ confidence=0 通过")
    void valid_multiMerchants_passes() {
        String json = """
                {"merchantId":["m-001","m-002","m-abc"],"reason":"r","confidence":0}
                """;
        validator.validateOrThrow(json);
    }

    @Test
    @DisplayName("valid:置信度上下界 0 与 1 通过")
    void valid_confidenceBoundary_passes() {
        validator.validateOrThrow("""
                {"merchantId":["m-1"],"reason":"x","confidence":0}""");
        validator.validateOrThrow("""
                {"merchantId":["m-1"],"reason":"x","confidence":1}""");
    }

    @Test
    @DisplayName("valid:容忍 markdown ```json ... ``` 围栏")
    void valid_markdownFence_tolerated() {
        String fenced = "```json\n"
                + "{\"merchantId\":[\"m-1\"],\"reason\":\"x\",\"confidence\":0.5}\n"
                + "```";
        validator.validateOrThrow(fenced);
        assertThat(validator.isValid(fenced)).isTrue();
    }

    @Test
    @DisplayName("invalid:缺 required 字段(merchantId)抛 SchemaViolationException")
    void invalid_missingMerchantId_throws() {
        String json = """
                {"reason":"r","confidence":0.5}
                """;
        assertThatThrownBy(() -> validator.validateOrThrow(json))
                .isInstanceOf(SchemaViolationException.class)
                .hasMessageContaining("merchantId");
    }

    @Test
    @DisplayName("invalid:缺 required 字段(reason)抛异常")
    void invalid_missingReason_throws() {
        String json = """
                {"merchantId":["m-1"],"confidence":0.5}
                """;
        assertThatThrownBy(() -> validator.validateOrThrow(json))
                .isInstanceOf(SchemaViolationException.class)
                .hasMessageContaining("reason");
    }

    @Test
    @DisplayName("invalid:confidence 越界(>1)抛异常")
    void invalid_confidenceAboveOne_throws() {
        String json = """
                {"merchantId":["m-1"],"reason":"r","confidence":1.5}
                """;
        assertThatThrownBy(() -> validator.validateOrThrow(json))
                .isInstanceOf(SchemaViolationException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    @DisplayName("invalid:confidence 越界(<0)抛异常")
    void invalid_confidenceBelowZero_throws() {
        String json = """
                {"merchantId":["m-1"],"reason":"r","confidence":-0.1}
                """;
        assertThatThrownBy(() -> validator.validateOrThrow(json))
                .isInstanceOf(SchemaViolationException.class);
    }

    @Test
    @DisplayName("invalid:merchantId pattern 不匹配(缺少 m- 前缀)抛异常 — 嵌套路径报告")
    void invalid_merchantPattern_throws() {
        String json = """
                {"merchantId":["001"],"reason":"r","confidence":0.5}
                """;
        assertThatThrownBy(() -> validator.validateOrThrow(json))
                .isInstanceOf(SchemaViolationException.class)
                // 路径包含 merchantId 字段(嵌套错误路径报告)
                .satisfies(ex -> assertThat(((SchemaViolationException) ex).violations())
                        .anySatisfy(msg -> assertThat(msg).contains("merchantId")));
    }

    @Test
    @DisplayName("invalid:merchantId 空数组(违反 minItems=1)抛异常")
    void invalid_emptyMerchantArray_throws() {
        String json = """
                {"merchantId":[],"reason":"r","confidence":0.5}
                """;
        assertThatThrownBy(() -> validator.validateOrThrow(json))
                .isInstanceOf(SchemaViolationException.class);
    }

    @Test
    @DisplayName("invalid:merchantId 元素不是 string(嵌套类型错误)抛异常")
    void invalid_merchantTypeNested_throws() {
        String json = """
                {"merchantId":[123],"reason":"r","confidence":0.5}
                """;
        assertThatThrownBy(() -> validator.validateOrThrow(json))
                .isInstanceOf(SchemaViolationException.class)
                .hasMessageContaining("merchantId");
    }

    @Test
    @DisplayName("invalid:reason 为空字符串(违反 minLength=1)抛异常")
    void invalid_emptyReason_throws() {
        String json = """
                {"merchantId":["m-1"],"reason":"","confidence":0.5}
                """;
        assertThatThrownBy(() -> validator.validateOrThrow(json))
                .isInstanceOf(SchemaViolationException.class);
    }

    @Test
    @DisplayName("invalid:additionalProperties 字段(extra)抛异常")
    void invalid_additionalProperty_throws() {
        String json = """
                {"merchantId":["m-1"],"reason":"r","confidence":0.5,"extra":"x"}
                """;
        assertThatThrownBy(() -> validator.validateOrThrow(json))
                .isInstanceOf(SchemaViolationException.class);
    }

    @Test
    @DisplayName("invalid:不是合法 JSON 抛 SchemaViolationException(非解析异常)")
    void invalid_garbageJson_throws() {
        assertThatThrownBy(() -> validator.validateOrThrow("not json at all"))
                .isInstanceOf(SchemaViolationException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    @DisplayName("isValid:不抛异常,仅返回 boolean")
    void isValid_doesNotThrow() {
        assertThat(validator.isValid("not json")).isFalse();
        assertThat(validator.isValid("""
                {"merchantId":["m-1"],"reason":"r","confidence":0.5}""")).isTrue();
    }

    @Test
    @DisplayName("invalid:SchemaViolationException.violations() 返回非空列表(测试嵌套错误聚合)")
    void invalid_violationsCollected() {
        // 同时触发两个问题:merchantId pattern + 缺 reason
        String json = """
                {"merchantId":["bad"],"confidence":0.5}
                """;
        try {
            validator.validateOrThrow(json);
        } catch (SchemaViolationException ex) {
            assertThat(ex.violations()).isNotEmpty();
            // 至少一个违规提到 merchantId(嵌套路径)
            assertThat(ex.violations()).anySatisfy(msg -> assertThat(msg).contains("merchantId"));
        }
    }
}