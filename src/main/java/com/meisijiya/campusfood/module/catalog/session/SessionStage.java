package com.meisijiya.campusfood.module.catalog.session;

import java.util.EnumSet;
import java.util.Set;

/**
 * 会话槽位阶段(F-2;对应 CONTEXT.md §2 / 简历 bullet "INIT → 商圈 → 菜系 → 商家")。
 *
 * <p>严格单向流转:每个阶段只有一个 next;非法跳转抛 {@link IllegalSlotTransitionException}。
 * 终止态 {@link #MERCHANT} 不再有 next — 重置必须先回 {@link #INIT}。
 *
 * @author meisijiya
 */
public enum SessionStage {

    INIT,
    ZONE,
    CUISINE,
    MERCHANT;

    /**
     * 当前阶段在严格单向链上的下一阶段;终止态返 {@code null}(调用方需用
     * {@link #isTerminal(SessionStage)} 防御)。
     */
    public SessionStage next() {
        return switch (this) {
            case INIT -> ZONE;
            case ZONE -> CUISINE;
            case CUISINE -> MERCHANT;
            case MERCHANT -> null;
        };
    }

    /** 终止态(MERCHANT 后不能再 next,只能通过超时 / 重置回 INIT)。 */
    public static boolean isTerminal(SessionStage stage) {
        return stage != null && stage == MERCHANT;
    }

    /** 合法起始阶段集合(INIT 单独,ZONE/CUISINE/MERCHANT 由前置阶段写入)。 */
    public static final Set<SessionStage> ALL = EnumSet.allOf(SessionStage.class);
}