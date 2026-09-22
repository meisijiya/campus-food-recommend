/**
 * router/types.d.ts
 * ---------------------------------------------------------------------------
 * 扩展 RouteMeta,允许自定义 meta 字段。来源:Vue Router 官方文档
 * https://router.vuejs.org/guide/advanced/meta.html#TypeScript
 * ---------------------------------------------------------------------------
 */
import "vue-router";

declare module "vue-router" {
  interface RouteMeta {
    /** 是否需要登录才能访问 */
    requiresAuth?: boolean;
    /** 是否需要 ADMIN 角色 */
    requiresAdmin?: boolean;
    /** 公开路由(如登录页),未登录也可访问 */
    public?: boolean;
    /** 是否隐藏 TabBar(顶部 3 tab),用于登录页等全屏视图 */
    hideTabBar?: boolean;
    /** Tab 标识: 主路径 / 管理后台 / 可观测性 */
    tab?: "main" | "admin" | "observability";
    /** 浏览器标题 */
    title?: string;
  }
}

export {};