package com.meisijiya.campusfood.module.catalog.session;

/**
 * 会话上下文快照 — 内存态,记录当前 stage + 已填槽位。
 *
 * <p>服务层用 record 形式在方法间传递;Redis 持久化由 {@code SessionService} 负责。
 * 槽位值为 {@code null} 表示该阶段尚未填写;stage 一定非空。
 *
 * @param stage   当前阶段(必填)
 * @param zoneId  INIT 之后第二阶段填入
 * @param cuisineId INIT 之后第三阶段填入
 * @param merchantId INIT 之后第四阶段填入
 * @author meisijiya
 */
public record SessionContext(
        SessionStage stage,
        String zoneId,
        String cuisineId,
        String merchantId) {

    /** 空会话(INIT 阶段,槽位全空)。 */
    public static SessionContext empty() {
        return new SessionContext(SessionStage.INIT, null, null, null);
    }

    /** 把当前 stage 推进一格(只换 stage,槽位在调用方填入)。 */
    public SessionContext advanceTo(SessionStage target) {
        return new SessionContext(target, zoneId, cuisineId, merchantId);
    }

    public SessionContext withZone(String id) {
        return new SessionContext(SessionStage.ZONE, id, cuisineId, merchantId);
    }

    public SessionContext withCuisine(String id) {
        return new SessionContext(SessionStage.CUISINE, zoneId, id, merchantId);
    }

    public SessionContext withMerchant(String id) {
        return new SessionContext(SessionStage.MERCHANT, zoneId, cuisineId, id);
    }
}