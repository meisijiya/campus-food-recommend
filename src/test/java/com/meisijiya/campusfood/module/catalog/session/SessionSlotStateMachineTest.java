package com.meisijiya.campusfood.module.catalog.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SessionSlotStateMachine 单元测试(F-2;对应 ticket acceptance #12 "覆盖 7 条合法/非法跳转组合")。
 *
 * <p>覆盖:
 * <ul>
 *   <li>4 条合法(null→INIT / INIT→ZONE / ZONE→CUISINE / CUISINE→MERCHANT / MERCHANT→MERCHANT 幂等重写)</li>
 *   <li>3 条非法(INIT→CUISINE / INIT→MERCHANT / MERCHANT→ZONE 回退)</li>
 * </ul>
 *
 * @author meisijiya
 */
class SessionSlotStateMachineTest {

    private final SessionSlotStateMachine sm = new SessionSlotStateMachine();

    // ---------- 合法跳转 ----------

    @Test
    @DisplayName("null → INIT:冷启动合法")
    void nullToInit_isLegal() {
        assertThatCode(() -> sm.validateTransition(null, SessionStage.INIT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("INIT → ZONE:第一阶推进合法")
    void initToZone_isLegal() {
        assertThatCode(() -> sm.validateTransition(SessionStage.INIT, SessionStage.ZONE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ZONE → CUISINE:第二阶推进合法")
    void zoneToCuisine_isLegal() {
        assertThatCode(() -> sm.validateTransition(SessionStage.ZONE, SessionStage.CUISINE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CUISINE → MERCHANT:第三阶推进合法")
    void cuisineToMerchant_isLegal() {
        assertThatCode(() -> sm.validateTransition(SessionStage.CUISINE, SessionStage.MERCHANT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MERCHANT → MERCHANT:幂等重写合法(同一个 stage 重复写入)")
    void merchantToMerchant_idempotent() {
        assertThatCode(() -> sm.validateTransition(SessionStage.MERCHANT, SessionStage.MERCHANT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateReset:从任意 stage 重置回 INIT 都合法")
    void reset_alwaysLegal() {
        assertThatCode(() -> sm.validateReset(null)).doesNotThrowAnyException();
        for (SessionStage s : SessionStage.values()) {
            assertThatCode(() -> sm.validateReset(s)).doesNotThrowAnyException();
        }
    }

    // ---------- 非法跳转 ----------

    @Test
    @DisplayName("INIT → CUISINE:跨级非法")
    void initToCuisine_isIllegal() {
        assertThatThrownBy(() -> sm.validateTransition(SessionStage.INIT, SessionStage.CUISINE))
                .isInstanceOf(IllegalSlotTransitionException.class)
                .hasMessageContaining("INIT")
                .hasMessageContaining("CUISINE");
    }

    @Test
    @DisplayName("INIT → MERCHANT:跨多级非法")
    void initToMerchant_isIllegal() {
        assertThatThrownBy(() -> sm.validateTransition(SessionStage.INIT, SessionStage.MERCHANT))
                .isInstanceOf(IllegalSlotTransitionException.class);
    }

    @Test
    @DisplayName("MERCHANT → ZONE:回退非法(只能重置回 INIT)")
    void merchantToZone_isIllegal() {
        assertThatThrownBy(() -> sm.validateTransition(SessionStage.MERCHANT, SessionStage.ZONE))
                .isInstanceOf(IllegalSlotTransitionException.class);
    }

    @Test
    @DisplayName("ZONE → ZONE:幂等重写合法(同 stage 重复写入)")
    void zoneToZone_idempotent() {
        assertThatCode(() -> sm.validateTransition(SessionStage.ZONE, SessionStage.ZONE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MERCHANT → CUISINE:回退一格非法")
    void merchantToCuIsine_isIllegal() {
        assertThatThrownBy(() -> sm.validateTransition(SessionStage.MERCHANT, SessionStage.CUISINE))
                .isInstanceOf(IllegalSlotTransitionException.class);
    }

    @Test
    @DisplayName("null → 非 INIT:冷启动只能进 INIT")
    void nullToZone_isIllegal() {
        assertThatThrownBy(() -> sm.validateTransition(null, SessionStage.ZONE))
                .isInstanceOf(IllegalSlotTransitionException.class);
    }

    @Test
    @DisplayName("to == null:非法")
    void toNull_isIllegal() {
        assertThatThrownBy(() -> sm.validateTransition(SessionStage.INIT, null))
                .isInstanceOf(IllegalSlotTransitionException.class);
    }

    // ---------- validateSlot(写入槽位时的 stage 校验) ----------

    @Test
    @DisplayName("validateSlot:ZONE 阶段写 zone 合法")
    void validateSlot_zoneAtZoneStage_isLegal() {
        assertThatCode(() -> sm.validateSlot(SessionStage.ZONE, SessionSlotStateMachine.Slot.ZONE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateSlot:ZONE 阶段写 cuisine 非法(应先推进 stage)")
    void validateSlot_cuisineAtZoneStage_isIllegal() {
        assertThatThrownBy(() -> sm.validateSlot(SessionStage.ZONE, SessionSlotStateMachine.Slot.CUISINE))
                .isInstanceOf(IllegalSlotTransitionException.class);
    }

    @Test
    @DisplayName("validateSlot:MERCHANT 阶段写 merchant 合法")
    void validateSlot_merchantAtMerchantStage_isLegal() {
        assertThatCode(() -> sm.validateSlot(SessionStage.MERCHANT, SessionSlotStateMachine.Slot.MERCHANT))
                .doesNotThrowAnyException();
    }

    // ---------- Stage.next() 边界 ----------

    @Test
    @DisplayName("SessionStage.MERCHANT.next() 返 null:终止态")
    void merchantNext_isNull() {
        assertThat(SessionStage.MERCHANT.next()).isNull();
    }

    @Test
    @DisplayName("SessionStage.isTerminal(MERCHANT) 返 true")
    void isTerminal_merchant() {
        assertThat(SessionStage.isTerminal(SessionStage.MERCHANT)).isTrue();
        assertThat(SessionStage.isTerminal(SessionStage.INIT)).isFalse();
        assertThat(SessionStage.isTerminal(SessionStage.ZONE)).isFalse();
        assertThat(SessionStage.isTerminal(SessionStage.CUISINE)).isFalse();
    }
}