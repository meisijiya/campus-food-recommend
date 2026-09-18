package com.meisijiya.campusfood.module.catalog.session;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 会话槽位业务服务 — 组合状态机校验与 Redis 读写。
 *
 * <h2>Redis key 命名(严格按 CONTEXT.md §2)</h2>
 * <pre>
 *   session:&lt;sid&gt;:stage     → INIT | ZONE | CUISINE | MERCHANT
 *   session:&lt;sid&gt;:zone      → zoneId
 *   session:&lt;sid&gt;:cuisine   → cuisineId
 *   session:&lt;sid&gt;:merchant  → merchantId
 * </pre>
 *
 * <p>每个 key 写入后都 {@code EXPIRE 1800} — 即 30 分钟空闲超时。空 key(从未写入)
 * 不存在 EXPIRE,查询时视作"该槽位未填"。
 *
 * <h2>重置语义</h2>
 * 调用 {@link #init(String)} 会把 stage 强制重置为 INIT,并清掉所有已写入槽位,
 * 实现 CONTEXT §2 描述的"回退只能回到 INIT"。
 *
 * @author meisijiya
 */
@Service
public class SessionService {

    /** 30 分钟空闲超时(秒)。 */
    static final long SLOT_TTL_SECONDS = 1800L;

    private static final String KEY_PREFIX = "session:";

    private final SessionSlotStateMachine stateMachine;
    private final StringRedisTemplate redis;

    public SessionService(SessionSlotStateMachine stateMachine, StringRedisTemplate redis) {
        this.stateMachine = stateMachine;
        this.redis = redis;
    }

    // ---------- 公共 API ----------

    /**
     * 初始化(或重置)会话:stage=INIT,清空所有槽位。每次 SET 后 EXPIRE 1800。
     *
     * <p>任意 stage 都能重置回 INIT(对应 CONTEXT §2 "用户主动重置")。
     */
    public SessionContext init(String sid) {
        validateSid(sid);
        SessionStage from = readStage(sid);
        stateMachine.validateReset(from);
        // 强制写 stage=INIT + 清空三个槽位
        redis.opsForValue().set(stageKey(sid), SessionStage.INIT.name(), SLOT_TTL_SECONDS, TimeUnit.SECONDS);
        redis.delete(java.util.List.of(zoneKey(sid), cuisineKey(sid), merchantKey(sid)));
        return SessionContext.empty();
    }

    /** 写入 zoneId;当前 stage 必须是 ZONE(否则状态机校验抛 IllegalSlotTransitionException)。 */
    public SessionContext setZone(String sid, String zoneId) {
        validateSid(sid);
        requireNonBlank(zoneId, "zoneId");
        SessionStage current = ensureStageAtLeast(sid, SessionStage.ZONE);
        // Defense-in-depth: ensureStageAtLeast already validated the transition to ZONE,
        // but kept so future refactors cannot bypass the slot↔stage invariant.
        stateMachine.validateSlot(current, SessionSlotStateMachine.Slot.ZONE);
        redis.opsForValue().set(zoneKey(sid), zoneId, SLOT_TTL_SECONDS, TimeUnit.SECONDS);
        // 写槽位后也要刷新 stage 的 TTL,确保后续 stage 仍是 INIT 时不会过早过期
        redis.expire(stageKey(sid), SLOT_TTL_SECONDS, TimeUnit.SECONDS);
        return readContext(sid);
    }

    /** 写入 cuisineId;当前 stage 必须是 CUISINE。 */
    public SessionContext setCuisine(String sid, String cuisineId) {
        validateSid(sid);
        requireNonBlank(cuisineId, "cuisineId");
        SessionStage current = ensureStageAtLeast(sid, SessionStage.CUISINE);
        stateMachine.validateSlot(current, SessionSlotStateMachine.Slot.CUISINE);
        redis.opsForValue().set(cuisineKey(sid), cuisineId, SLOT_TTL_SECONDS, TimeUnit.SECONDS);
        redis.expire(stageKey(sid), SLOT_TTL_SECONDS, TimeUnit.SECONDS);
        return readContext(sid);
    }

    /** 写入 merchantId;当前 stage 必须是 MERCHANT。 */
    public SessionContext setMerchant(String sid, String merchantId) {
        validateSid(sid);
        requireNonBlank(merchantId, "merchantId");
        SessionStage current = ensureStageAtLeast(sid, SessionStage.MERCHANT);
        stateMachine.validateSlot(current, SessionSlotStateMachine.Slot.MERCHANT);
        redis.opsForValue().set(merchantKey(sid), merchantId, SLOT_TTL_SECONDS, TimeUnit.SECONDS);
        redis.expire(stageKey(sid), SLOT_TTL_SECONDS, TimeUnit.SECONDS);
        return readContext(sid);
    }

    /** 读全量上下文(stage + 已填槽位)。stage 不存在视作 INIT。 */
    public SessionContext readContext(String sid) {
        validateSid(sid);
        SessionStage stage = readStage(sid);
        if (stage == null) {
            stage = SessionStage.INIT;
        }
        return new SessionContext(
                stage,
                redis.opsForValue().get(zoneKey(sid)),
                redis.opsForValue().get(cuisineKey(sid)),
                redis.opsForValue().get(merchantKey(sid)));
    }

    // ---------- 内部 ----------

    /**
     * 校验当前 stage 是否已到达 {@code target} — 推进时调用方决定是否在 setXxx 前手动
     * 改 stage;这里只校验"当前 stage 至少是 target",如果不足则抛 IllegalSlotTransitionException。
     */
    private SessionStage ensureStageAtLeast(String sid, SessionStage target) {
        SessionStage current = readStage(sid);
        if (current == null) {
            current = SessionStage.INIT;
        }
        // target 必须等于 current.next() — 即"恰好推进一格"
        stateMachine.validateTransition(current, target);
        // 把 stage 推进到 target 并刷新 TTL
        redis.opsForValue().set(stageKey(sid), target.name(), SLOT_TTL_SECONDS, TimeUnit.SECONDS);
        return target;
    }

    private SessionStage readStage(String sid) {
        String raw = redis.opsForValue().get(stageKey(sid));
        return raw == null ? null : SessionStage.valueOf(raw);
    }

    private static String stageKey(String sid) {
        return KEY_PREFIX + sid + ":stage";
    }

    private static String zoneKey(String sid) {
        return KEY_PREFIX + sid + ":zone";
    }

    private static String cuisineKey(String sid) {
        return KEY_PREFIX + sid + ":cuisine";
    }

    private static String merchantKey(String sid) {
        return KEY_PREFIX + sid + ":merchant";
    }

    private static void validateSid(String sid) {
        if (sid == null || sid.isBlank()) {
            throw new IllegalArgumentException("sid 不能为空");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    /** 暴露给上层的时间常量,便于测试断言。 */
    public static Duration slotTtl() {
        return Duration.ofSeconds(SLOT_TTL_SECONDS);
    }
}