package com.meisijiya.campusfood.module.recommend.schema;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.meisijiya.campusfood.common.exception.ApiException;

/**
 * JSON Schema 校验失败异常(F-3;对应 ticket acceptance #2)。
 *
 * <p>{@link ReflectiveRetryAdvisor} 与 {@link RuleBasedFallbackAdvisor} 编排下,
 * 校验失败先在 advisor 链里被捕获 + 重试 / 降级;理论上不会逃逸到 controller。
 * 选用 {@link HttpStatus#BAD_REQUEST} 而非 {@code INTERNAL_ERROR} —
 * 校验失败意味着响应体内容不合契约,语义上与"请求体校验失败"对等。
 *
 * @author meisijiya
 */
public class SchemaViolationException extends ApiException {

    private final List<String> violations;

    public SchemaViolationException(List<String> violations) {
        super(HttpStatus.BAD_REQUEST,
                "AI 响应不符合 RecommendationSchema: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    /** 单条违规消息的便利构造器。 */
    public SchemaViolationException(String violation) {
        this(List.of(violation));
    }

    /** 校验失败消息列表(只读),便于 advisor / 测试断言。 */
    public List<String> violations() {
        return violations;
    }
}