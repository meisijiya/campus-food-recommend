package com.meisijiya.campusfood.module.preheat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meisijiya.campusfood.module.preheat.assembler.MerchantCatalog;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;

/**
 * {@link RedisShardedWriter} 单元测试 — 覆盖 zone 分片写入 / key 格式 / TTL /
 * 极热商户写入 / 空集也写入 / 序列化失败包装 5 路径(总计 ≥ 5 测试)。
 *
 * <p>测试纪律:Mock {@link StringRedisTemplate} + {@link ObjectMapper},不连真实 Redis;
 * 关键参数(key / TTL / JSON)用 {@link ArgumentCaptor} 抓取断言,避免脆弱的字符串匹配。
 *
 * @author meisijiya
 */
class RedisShardedWriterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ObjectMapper mapper;
    private RedisShardedWriter writer;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        mapper = mock(ObjectMapper.class);
        when(redis.opsForValue()).thenReturn(ops);
        doNothing().when(ops).set(anyString(), anyString(), any(Duration.class));
        writer = new RedisShardedWriter(redis, mapper);
    }

    @Test
    @DisplayName("writeZoneCatalog_调用set_并传入600秒TTL")
    void writeZoneCatalog_callsSetExWith600s_andSerializesJson() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("{}");
        MerchantCatalog catalog = new MerchantCatalog();

        writer.writeZoneCatalog("Z-1", catalog);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("catalog:zone:Z-1"), eq("{}"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    @DisplayName("writeZoneCatalog_key格式_catalog冒号zone冒号zoneId")
    void writeZoneCatalog_keyFormat_catalogColonZoneColonZoneId() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("{}");

        writer.writeZoneCatalog("Z-EAST", new MerchantCatalog());

        verify(ops).set(eq("catalog:zone:Z-EAST"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("writeHotMerchants_调用set_并传入12小时TTL")
    void writeHotMerchants_callsSetWith12h_andSerializesJson() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("[\"M-1\",\"M-2\"]");
        Set<String> hotIds = new LinkedHashSet<>();
        hotIds.add("M-1");
        hotIds.add("M-2");

        writer.writeHotMerchants(hotIds);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("catalog:hot:merchants"), eq("[\"M-1\",\"M-2\"]"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    @DisplayName("writeHotMerchants_空集合_仍然写入且TTL一致")
    void writeHotMerchants_emptySet_stillCallsSet() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("[]");

        writer.writeHotMerchants(Collections.emptySet());

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(ops, times(1)).set(eq("catalog:hot:merchants"), eq("[]"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    @DisplayName("writeHotMerchants_null集合_仍然写入空JSON")
    void writeHotMerchants_nullSet_stillCallsSet() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("[]");

        writer.writeHotMerchants(null);

        verify(ops, times(1)).set(eq("catalog:hot:merchants"), eq("[]"), any(Duration.class));
    }

    @Test
    @DisplayName("writeZoneCatalog_序列化失败_包装IllegalStateException")
    void writeZoneCatalog_serializeFails_throwsIllegalState() throws Exception {
        JsonProcessingException jpe = mock(JsonProcessingException.class);
        when(mapper.writeValueAsString(any())).thenThrow(jpe);
        MerchantCatalog catalog = new MerchantCatalog();

        assertThatThrownBy(() -> writer.writeZoneCatalog("Z-1", catalog))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis sharded writer serialize failed")
                .hasCause(jpe);
    }

    @Test
    @DisplayName("writeZoneCatalog_null目录_写入空zones仍写Redis")
    void writeZoneCatalog_nullCatalog_stillCallsSet() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("{\"zones\":[]}");

        writer.writeZoneCatalog("Z-1", null);

        verify(ops).set(eq("catalog:zone:Z-1"), eq("{\"zones\":[]}"), any(Duration.class));
    }

    @Test
    @DisplayName("writeZoneCatalog_空zoneId_抛IllegalArgumentException不写Redis")
    void writeZoneCatalog_blankZoneId_throwsIllegalArgument() {
        assertThatThrownBy(() -> writer.writeZoneCatalog("", new MerchantCatalog()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.writeZoneCatalog(null, new MerchantCatalog()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- F-4 review fix: writeMerchantCache ----------

    @Test
    @DisplayName("writeMerchantCache_已知merchant_写入单merchant键并TTL=10min")
    void writeMerchantCache_presentMerchant_sets600sTtl() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("{\"id\":\"M-1\"}");
        Merchant m = new Merchant("M-1", "Z-1", "C-1", "noodle", "", 1.0);

        writer.writeMerchantCache("M-1", m);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("catalog:merchant:M-1"), eq("{\"id\":\"M-1\"}"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("writeMerchantCache_nullMerchant_写入负缓存marker_且TTL一致")
    void writeMerchantCache_nullMerchant_setsNegativeMarker() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("{\"__null__\":true}");

        writer.writeMerchantCache("M-NONEXISTENT", null);

        verify(ops).set(eq("catalog:merchant:M-NONEXISTENT"),
                eq("{\"__null__\":true}"), any(Duration.class));
    }

    @Test
    @DisplayName("writeMerchantCache_空或超长或非法字符merchantId_抛IllegalArgumentException")
    void writeMerchantCache_invalidMerchantId_throwsIllegalArgument() {
        Merchant m = new Merchant("M-1", "Z-1", "C-1", "noodle", "", 1.0);
        assertThatThrownBy(() -> writer.writeMerchantCache("", m))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.writeMerchantCache(null, m))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.writeMerchantCache("a".repeat(65), m))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.writeMerchantCache("has\r\nspace", m))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.writeMerchantCache("has space", m))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("writeZoneCatalog_超长或非法字符zoneId_抛IllegalArgumentException")
    void writeZoneCatalog_invalidZoneId_throwsIllegalArgument() {
        assertThatThrownBy(() -> writer.writeZoneCatalog("a".repeat(65), new MerchantCatalog()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.writeZoneCatalog("has\r\ninject", new MerchantCatalog()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}