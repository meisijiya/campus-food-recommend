package com.meisijiya.campusfood.module.catalog.session;

import org.springframework.stereotype.Component;

/**
 * 会话槽位状态机校验(F-2 核心组件)。
 *
 * <p>纯逻辑组件 — 不读 Redis、不依赖 Spring 上下文(便于单测)。
 *
 * <h2>合法规则</h2>
 * <ul>
 *   <li>{@code from == null} 且 {@code to == INIT} → 重置 / 冷启动,合法</li>
 *   <li>{@code from == INIT} 且 {@code to == ZONE} → 合法</li>
 *   <li>{@code from == ZONE} 且 {@code to == CUISINE} → 合法</li>
 *   <li>{@code from == CUISINE} 且 {@code to == MERCHANT} → 合法</li>
 *   <li>{@code from == MERCHANT} 且 {@code to == MERCHANT}(幂等重写) → 合法</li>
 * </ul>
 *
 * <h2>非法规则(全部抛 {@link IllegalSlotTransitionException})</h2>
 * <ul>
 *   <li>越级(如 INIT → CUISINE / INIT → MERCHANT)</li>
 *   <li>回退(MERCHANT → ZONE 等任意回到前一阶段)</li>
 *   <li>跳到终止态之外的阶段(MERCHANT 后只有重置合法)</li>
 *   <li>{@code from} 不在 {@link SessionStage#ALL} 范围</li>
 * </ul>
 *
 * @author meisijiya
 */
@Component
public class SessionSlotStateMachine {

    /**
     * 校验 {@code from → to} 跳转是否合法;非法抛 {@link IllegalSlotTransitionException}。
     *
     * <p>合法集合(仅适用于"前进"语义):
     * <ul>
     *   <li>{@code from == null} → {@code to == INIT}(冷启动)</li>
     *   <li>{@code from == to}(幂等重写)</li>
     *   <li>{@code from.next() == to}(单向链 INIT→ZONE→CUISINE→MERCHANT)</li>
     * </ul>
     *
     * <p>回退到 INIT 必须走 {@link #validateReset(SessionStage)},不归本方法负责。
     *
     * @param from 当前阶段(允许 null,表示全新会话)
     * @param to   目标阶段(必填)
     */
    public void validateTransition(SessionStage from, SessionStage to) {
        if (to == null) {
            throw new IllegalSlotTransitionException(from, null);
        }
        // 全新会话:只允许 INIT
        if (from == null) {
            if (to != SessionStage.INIT) {
                throw new IllegalSlotTransitionException(null, to);
            }
            return;
        }
        // 幂等重写:同一阶段重写(Redis SET 同一个 key),允许
        if (from == to) {
            return;
        }
        // 单向流转:必须等于 from.next()
        if (from.next() != to) {
            throw new IllegalSlotTransitionException(from, to);
        }
    }

    /**
     * 校验重置到 INIT 是否合法。
     *
     * <p>任意阶段(包括 null)重置回 INIT 都合法 — 对应 CONTEXT §2 "回退只能回到 INIT
     * (超时或用户主动重置)"。
     */
    public void validateReset(SessionStage from) {
        // reset 永远合法 — by contract
    }

    /**
     * 校验给定 stage 是否允许接受某一槽位值写入。
     * 例如 ZONE 阶段写 zone 合法,ZONE 阶段写 cuisine 非法(应先 ZONE→CUISINE 升级)。
     *
     * @param stage 当前阶段
     * @param slot  要写入的槽位
     */
    public void validateSlot(SessionStage stage, Slot slot) {
        if (stage == null || slot == null) {
            throw new IllegalSlotTransitionException(stage, slot == null ? null : stageFor(slot));
        }
        SessionStage expected = stageFor(slot);
        if (expected != stage) {
            throw new IllegalSlotTransitionException(stage, expected);
        }
    }

    private static SessionStage stageFor(Slot slot) {
        return switch (slot) {
            case ZONE -> SessionStage.ZONE;
            case CUISINE -> SessionStage.CUISINE;
            case MERCHANT -> SessionStage.MERCHANT;
        };
    }

    /** 槽位枚举 — 对应 CONTEXT.md §2 三种持久化字段。 */
    public enum Slot {
        ZONE,
        CUISINE,
        MERCHANT
    }
}