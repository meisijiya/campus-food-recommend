package com.meisijiya.campusfood.module.recommend.schema;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

/**
 * Recommendation JSON Schema 单例加载器(F-3;对应 ticket acceptance #1)。
 *
 * <p>从 classpath {@code schema/recommendation.json} 读取 draft-2020-12 schema,
 * 用 {@link JsonSchemaFactory} 编译为不可变 {@link JsonSchema} 单例。
 * 资源文件 UTF-8 完整可读,F-3 阶段只读不改 description 字段。
 *
 * @author meisijiya
 */
@Component
public class RecommendationSchema {

    /** classpath 资源路径(与 {@code src/main/resources/schema/recommendation.json} 对应)。 */
    static final String SCHEMA_RESOURCE = "schema/recommendation.json";

    private final JsonSchema schema;
    private final String rawJson;

    public RecommendationSchema() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
            JsonNode schemaNode = mapper.readTree(in);
            this.rawJson = mapper.writeValueAsString(schemaNode);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            this.schema = factory.getSchema(schemaNode);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法加载 RecommendationSchema 资源: " + SCHEMA_RESOURCE, e);
        }
    }

    /** 编译后的 networknt {@link JsonSchema}(线程安全,可共享)。 */
    public JsonSchema schema() {
        return schema;
    }

    /** 原始 schema JSON 文本(供 prompt 注入 + 测试断言)。 */
    public String rawJson() {
        return rawJson;
    }
}