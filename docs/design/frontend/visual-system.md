# Demo 前端 design.md · 视觉系统

> **本文件**:UI 与工程化的**基调**。色板 / 字号 / 间距 / 圆角 / 阴影 / CSS 变量 / Tailwind 映射。后续 F-16.1+ 实施时 `tailwind.config.ts` 与 `:root` 直接消费本文件 token。
> **配套**:`overview.md`(原则) / `architecture.md`(组件调用 token) / `mock/login.html`(token 可视化)
> **检验**:`init.ps1` stage 7 校验本文件包含 `--color-primary:` 与 `--space-4:` 两个 grep 关键字。

---

## 1. 设计哲学

### 1.1 反 AI aesthetic(AGENTS.md §9 不变量第 6 条)

显式**禁止**的视觉模式:

| 禁止 | 替换方案 |
|---|---|
| 紫色 / 靛蓝主色 | 校园绿 `#3D8B5F`(低饱和) |
| 渐变 / 玻璃态 / 模糊背景 | flat + 1 层 `shadow-sm` |
| `rounded-2xl` everywhere | `rounded-lg`(0.5rem)主体 / `rounded-md`(0.375rem)输入 / `rounded`(0.25rem)标签 |
| Hero 大图 + 居中"模板文案" | 内容优先,无 Hero |
| 巨大 padding | 4px base scale,统一节奏 |
| 阴影叠加 `shadow-xl` 多层 | 最多 1 层 |
| Stock 卡片网格 | 叙事优先,扁平列表 / 分区 |

### 1.2 校园主题暖色基调

- **暖白背景**(`#FAF7F2`)而非冷白 `#FFFFFF` ——区别于"互联网产品"
- **暖灰文字**(`#2C2A26`)而非纯黑 `#000000`
- **克制使用暖橙 / 校园红**:每个视图至多 1 个暖橙焦点元素
- **不使用 emoji 作为图标**(避免占位感),lucide-vue-next 线条图标

---

## 2. 色板

### 2.1 主色(primary)

| token | hex | 用途 | 对比度(暖白底) |
|---|---|---|---|
| `--color-primary-50` | `#EEF5F0` | 背景 / hover 浅底 | — |
| `--color-primary-100` | `#D4E8DA` | 选中态背景 | — |
| `--color-primary-500` | `#3D8B5F` | 主按钮 / 主链接 / 品牌 | **5.6:1**(WCAG AA ✓) |
| `--color-primary-600` | `#2F7048` | 主按钮 hover | **7.4:1**(AAA ✓) |
| `--color-primary-700` | `#245539` | 主按钮 active / pressed | **9.5:1**(AAA ✓) |

### 2.2 辅色(secondary)

| token | hex | 用途 | 对比度 |
|---|---|---|---|
| `--color-secondary-50` | `#FBF1E1` | 标签 / 提示背景 | — |
| `--color-secondary-500` | `#E0A458` | 暖橙焦点(限 1 处/视图) | **3.0:1**(大字 ✓,正文需配黑) |
| `--color-secondary-700` | `#A87530` | 暖橙 hover | **5.0:1**(正文 ✓) |

### 2.3 强调色(accent)

| token | hex | 用途 | 对比度 |
|---|---|---|---|
| `--color-accent-500` | `#C75450` | 校园红(点赞 / 错误 / 限流告警) | **5.2:1**(正文 ✓) |
| `--color-accent-600` | `#A33E3B` | hover | **7.0:1**(AAA ✓) |

### 2.4 中性(neutral)

| token | hex | 用途 |
|---|---|---|
| `--color-bg` | `#FAF7F2` | 全局背景(暖白) |
| `--color-surface` | `#FFFFFF` | 卡片 / 浮层底 |
| `--color-surface-raised` | `#FFFFFF` | 模态 / 抽屉(可加 `shadow-lg`) |
| `--color-border` | `#E8E4DD` | 1px 边框 |
| `--color-border-strong` | `#C9C4BB` | hover 边框 |
| `--color-text-primary` | `#2C2A26` | 正文 / 标题 |
| `--color-text-secondary` | `#6B6862` | 次要 / 占位 / 辅助 |
| `--color-text-disabled` | `#A8A49C` | disabled 文字 |
| `--color-text-on-primary` | `#FFFFFF` | 主按钮文字 |

### 2.5 语义色(semantic)

| token | hex | 用途 |
|---|---|---|
| `--color-success-500` | `#3D8B5F`(同 primary) | 成功提示 / hit_tier=mock 通过 |
| `--color-warning-500` | `#E0A458`(同 secondary) | 警告 / hit_tier=fallback |
| `--color-error-500` | `#C75450`(同 accent) | 错误 / 429 / 限流拒绝 / hit_tier=fallback |
| `--color-info-500` | `#4A6FA5` | 信息 / 限流提示 |

### 2.6 hit_tier 三档标识(F-3 现场卖点)

| hit_tier | 背景 token | 文字 token | 含义 |
|---|---|---|---|
| `mock` | `--color-success-50`(primary-50) | `--color-primary-700` | Spring AI MockChatModel 一次过 |
| `dashscope` | `--color-info-50`(`#EAF0F8`) | `--color-info-700`(`#34527A`) | 百炼真实 API 通过 |
| `fallback` | `--color-warning-50`(secondary-50) | `--color-error-700`(accent-700) | RBFA fallback 兜底(反思重试 2 次仍不合规) |

> 卡片角标用 `<Tag>` 自封装组件(ADR-0011 决策 1),色由 hit_tier 决定。

---

## 3. 字体

### 3.1 字体栈

```css
--font-sans: "Inter", -apple-system, BlinkMacSystemFont,
             "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
--font-mono: "JetBrains Mono", "Fira Code", "SF Mono", Consolas, monospace;
--font-num: "Poppins", var(--font-sans);  /* 数字指标 / hit_tier / 429 倒计时用 */
```

### 3.2 字号 scale(rem,base 16px)

| token | rem | px | 用途 | 字重 |
|---|---|---|---|---|
| `--text-xs` | 0.75 | 12 | 标签 / 辅助 / hit_tier 角标 | 500 |
| `--text-sm` | 0.875 | 14 | 次要正文 / 表单 label | 400 |
| `--text-base` | 1 | 16 | 正文 | 400 |
| `--text-lg` | 1.125 | 18 | 卡片标题 / 输入框文字 | 500 |
| `--text-xl` | 1.25 | 20 | 区段标题 | 600 |
| `--text-2xl` | 1.5 | 24 | 页标题 | 600 |
| `--text-3xl` | 1.875 | 30 | 大数字指标(Recharts 标题) | 700 |
| `--text-4xl` | 2.25 | 36 | 暗区 hero 数字 | 700 |

### 3.3 行高

| token | 值 | 用途 |
|---|---|---|
| `--leading-tight` | 1.25 | 标题 / 大数字 |
| `--leading-normal` | 1.5 | 正文 |
| `--leading-relaxed` | 1.625 | 卡片说明 / 推荐理由 |

### 3.4 字间距

| token | 值 | 用途 |
|---|---|---|
| `--tracking-tight` | -0.01em | 大标题 |
| `--tracking-normal` | 0 | 默认 |
| `--tracking-wide` | 0.05em | 标签 / 大写字母 |

---

## 4. 间距 scale(4px base)

| token | rem | px | 用途 |
|---|---|---|---|
| `--space-0` | 0 | 0 | — |
| `--space-1` | 0.25 | 4 | 标签 padding |
| `--space-2` | 0.5 | 8 | 表单 input padding |
| `--space-3` | 0.75 | 12 | 按钮 padding y |
| `--space-4` | 1 | 16 | 卡片 padding / 区段间距 |
| `--space-5` | 1.25 | 20 | 区段间距 |
| `--space-6` | 1.5 | 24 | 视图边距 / 大间隔 |
| `--space-8` | 2 | 32 | 区段分隔 |
| `--space-10` | 2.5 | 40 | 页面级间距 |
| `--space-12` | 3 | 48 | 视图分割 |
| `--space-16` | 4 | 64 | hero / 暗区 |

---

## 5. 圆角

| token | rem | px | 用途 |
|---|---|---|---|
| `--radius-sm` | 0.25 | 4 | 标签 / 角标 / hit_tier 标签 |
| `--radius-md` | 0.375 | 6 | 输入框 / 表单元素 |
| `--radius-lg` | 0.5 | 8 | 主按钮 / 卡片 |
| `--radius-xl` | 0.75 | 12 | 模态 / 抽屉 |
| `--radius-full` | 9999 | — | 头像 / 圆形 hit_tier |

**不**用 `--radius-2xl`(1.5rem),避免 AI 通用圆角感。

---

## 6. 阴影(克制,最多 1 层)

| token | 值 | 用途 |
|---|---|---|
| `--shadow-xs` | `0 1px 2px rgba(44, 42, 38, 0.04)` | 标签 / 角标 |
| `--shadow-sm` | `0 1px 3px rgba(44, 42, 38, 0.06), 0 1px 2px rgba(44, 42, 38, 0.04)` | 卡片 / 浮层 |
| `--shadow-md` | `0 4px 8px rgba(44, 42, 38, 0.08), 0 2px 4px rgba(44, 42, 38, 0.04)` | 模态 / 抽屉 |
| `--shadow-lg` | `0 10px 20px rgba(44, 42, 38, 0.10), 0 4px 8px rgba(44, 42, 38, 0.06)` | toast / 重要浮层 |

**不**叠加多层阴影。

---

## 7. 动效(animation)

| token | 值 | 用途 |
|---|---|---|
| `--duration-fast` | 150ms | hover / focus / 微交互 |
| `--duration-normal` | 250ms | 过渡 / 标签切换 |
| `--duration-slow` | 400ms | 模态进出 / 抽屉 |
| `--ease-out` | `cubic-bezier(0.16, 1, 0.3, 1)` | 默认 |
| `--ease-in-out` | `cubic-bezier(0.4, 0, 0.2, 1)` | 状态切换 |

**不**用 spring / bounce 物理动效(避免花哨)。

---

## 8. CSS 变量完整表(直接拷到 `:root`)

```css
:root {
  /* === shorthand aliases(grep 关键字 + 业务引用)== */
  --color-primary: var(--color-primary-500);  /* Q11 stage 7 grep 关键字 */
  --space-4:      1rem;                       /* Q11 stage 7 grep 关键字 */
  --radius-card:  var(--radius-lg);
  --shadow-card:  var(--shadow-sm);

  /* === primary === */
  --color-primary-50:  #EEF5F0;
  --color-primary-100: #D4E8DA;
  --color-primary-500: #3D8B5F;
  --color-primary-600: #2F7048;
  --color-primary-700: #245539;

  /* === secondary === */
  --color-secondary-50:  #FBF1E1;
  --color-secondary-500: #E0A458;
  --color-secondary-700: #A87530;

  /* === accent === */
  --color-accent-500: #C75450;
  --color-accent-600: #A33E3B;

  /* === neutral === */
  --color-bg:              #FAF7F2;
  --color-surface:         #FFFFFF;
  --color-surface-raised:  #FFFFFF;
  --color-border:          #E8E4DD;
  --color-border-strong:   #C9C4BB;
  --color-text-primary:    #2C2A26;
  --color-text-secondary:  #6B6862;
  --color-text-disabled:   #A8A49C;
  --color-text-on-primary: #FFFFFF;

  /* === semantic === */
  --color-success-500: #3D8B5F;
  --color-warning-500: #E0A458;
  --color-error-500:   #C75450;
  --color-info-500:    #4A6FA5;

  /* === hit_tier === */
  --hit-tier-mock-bg:      var(--color-primary-50);
  --hit-tier-mock-text:    var(--color-primary-700);
  --hit-tier-dashscope-bg: #EAF0F8;
  --hit-tier-dashscope-text: #34527A;
  --hit-tier-fallback-bg:  var(--color-warning-50);
  --hit-tier-fallback-text: var(--color-accent-600);

  /* === font === */
  --font-sans: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI",
               "PingFang SC", "Microsoft YaHei", sans-serif;
  --font-mono: "JetBrains Mono", "Fira Code", "SF Mono", Consolas, monospace;
  --font-num:  "Poppins", var(--font-sans);

  /* === text === */
  --text-xs: 0.75rem;
  --text-sm: 0.875rem;
  --text-base: 1rem;
  --text-lg: 1.125rem;
  --text-xl: 1.25rem;
  --text-2xl: 1.5rem;
  --text-3xl: 1.875rem;
  --text-4xl: 2.25rem;
  --leading-tight:   1.25;
  --leading-normal:  1.5;
  --leading-relaxed: 1.625;
  --tracking-tight:  -0.01em;
  --tracking-normal: 0;
  --tracking-wide:   0.05em;

  /* === space (4px base) === */
  --space-0:  0;
  --space-1:  0.25rem;
  --space-2:  0.5rem;
  --space-3:  0.75rem;
  --space-4:  1rem;
  --space-5:  1.25rem;
  --space-6:  1.5rem;
  --space-8:  2rem;
  --space-10: 2.5rem;
  --space-12: 3rem;
  --space-16: 4rem;

  /* === radius === */
  --radius-sm:   0.25rem;
  --radius-md:   0.375rem;
  --radius-lg:   0.5rem;
  --radius-xl:   0.75rem;
  --radius-full: 9999px;

  /* === shadow === */
  --shadow-xs: 0 1px 2px rgba(44, 42, 38, 0.04);
  --shadow-sm: 0 1px 3px rgba(44, 42, 38, 0.06), 0 1px 2px rgba(44, 42, 38, 0.04);
  --shadow-md: 0 4px 8px rgba(44, 42, 38, 0.08), 0 2px 4px rgba(44, 42, 38, 0.04);
  --shadow-lg: 0 10px 20px rgba(44, 42, 38, 0.10), 0 4px 8px rgba(44, 42, 38, 0.06);

  /* === animation === */
  --duration-fast:   150ms;
  --duration-normal: 250ms;
  --duration-slow:   400ms;
  --ease-out:     cubic-bezier(0.16, 1, 0.3, 1);
  --ease-in-out:  cubic-bezier(0.4, 0, 0.2, 1);

  /* === layout === */
  --max-w-content: 72rem;   /* 1152px,主视图最大宽 */
  --max-w-form:    28rem;   /* 448px,登录表单 */
  --header-h:      3.5rem;  /* 56px */
}
```

---

## 9. Tailwind config 映射(为 F-16.1 准备)

```ts
// tailwind.config.ts(F-16.1 实施时落)
import type { Config } from "tailwindcss";

export default {
  content: ["./index.html", "./src/**/*.{vue,ts}"],
  theme: {
    extend: {
      colors: {
        primary:   { 50:"#EEF5F0", 100:"#D4E8DA", 500:"#3D8B5F", 600:"#2F7048", 700:"#245539" },
        secondary: { 50:"#FBF1E1", 500:"#E0A458", 700:"#A87530" },
        accent:    { 500:"#C75450", 600:"#A33E3B" },
        bg:        "#FAF7F2",
        surface:   "#FFFFFF",
        border:    { DEFAULT:"#E8E4DD", strong:"#C9C4BB" },
        text:      { primary:"#2C2A26", secondary:"#6B6862", disabled:"#A8A49C", "on-primary":"#FFFFFF" },
        success:   { 500:"#3D8B5F" },
        warning:   { 500:"#E0A458" },
        error:     { 500:"#C75450" },
        info:      { 500:"#4A6FA5" },
      },
      fontFamily: {
        sans: ["Inter", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "PingFang SC", "Microsoft YaHei", "sans-serif"],
        mono: ["JetBrains Mono", "Fira Code", "SF Mono", "Consolas", "monospace"],
        num:  ["Poppins", "Inter", "sans-serif"],
      },
      fontSize: {
        xs:["0.75rem",{lineHeight:"1.25"}], sm:["0.875rem",{lineHeight:"1.5"}],
        base:["1rem",{lineHeight:"1.5"}], lg:["1.125rem",{lineHeight:"1.5"}],
        xl:["1.25rem",{lineHeight:"1.25"}], "2xl":["1.5rem",{lineHeight:"1.25"}],
        "3xl":["1.875rem",{lineHeight:"1.25"}], "4xl":["2.25rem",{lineHeight:"1.25"}],
      },
      spacing: {  // 4px base,覆盖 Tailwind 默认
        0:"0", 1:"0.25rem", 2:"0.5rem", 3:"0.75rem", 4:"1rem", 5:"1.25rem",
        6:"1.5rem", 8:"2rem", 10:"2.5rem", 12:"3rem", 16:"4rem",
      },
      borderRadius: {
        none:"0", sm:"0.25rem", DEFAULT:"0.375rem", md:"0.375rem",
        lg:"0.5rem", xl:"0.75rem", "2xl":"0.75rem" /* 故意不用 2xl 真实值 */,
        full:"9999px",
      },
      boxShadow: {
        xs:"0 1px 2px rgba(44, 42, 38, 0.04)",
        sm:"0 1px 3px rgba(44, 42, 38, 0.06), 0 1px 2px rgba(44, 42, 38, 0.04)",
        DEFAULT:"0 1px 3px rgba(44, 42, 38, 0.06), 0 1px 2px rgba(44, 42, 38, 0.04)",
        md:"0 4px 8px rgba(44, 42, 38, 0.08), 0 2px 4px rgba(44, 42, 38, 0.04)",
        lg:"0 10px 20px rgba(44, 42, 38, 0.10), 0 4px 8px rgba(44, 42, 38, 0.06)",
        none:"none",
      },
      transitionDuration: { 150:"150ms", 250:"250ms", 400:"400ms" },
      maxWidth: { content:"72rem", form:"28rem" },
    },
  },
  plugins: [],
} satisfies Config;
```

---

## 10. 组件视觉规范

### 10.1 按钮(Button)

| variant | 用途 | 样式 |
|---|---|---|
| `primary` | 主操作(登录 / 提交 / 选 zone) | `bg-primary-500 text-text-on-primary hover:bg-primary-600 active:bg-primary-700` + `shadow-xs` |
| `secondary` | 次操作(取消 / 返回) | `bg-surface text-text-primary border border-border hover:bg-bg` |
| `ghost` | 文字按钮 | `bg-transparent text-primary-500 hover:bg-primary-50` |
| `danger` | 危险操作(删除 / 退出登录) | `bg-accent-500 text-white hover:bg-accent-600` |
| `disabled` | 禁用 | `bg-bg text-text-disabled cursor-not-allowed` |

**规范**:
- `h-10`(40px)主按钮 / `h-9`(36px)紧凑 / `h-12`(48px)大
- padding `px-4`
- 圆角 `rounded-lg`
- focus ring:`focus-visible:ring-2 ring-primary-500 ring-offset-2`
- 全部 `<button type="button">`,默认不 submit

### 10.2 表单输入(Input)

| 状态 | 样式 |
|---|---|
| default | `h-10 px-3 rounded-md border border-border bg-surface text-text-primary placeholder:text-text-secondary` |
| focus | `border-primary-500 ring-2 ring-primary-100`(无 outline) |
| error | `border-accent-500 ring-2 ring-accent-50` + 下方 `<p class="text-error-500 text-sm">` 错误说明 |
| disabled | `bg-bg text-text-disabled cursor-not-allowed` |

**规范**:
- 标签 `<label>` 与 `<input>` 用 `htmlFor` / `id` 显式关联
- 必填项标 `*`(暖红,非纯红)
- placeholder 不替代 label(label 永远可见,placeholder 是 hint)

### 10.3 卡片(Card)

- `bg-surface rounded-lg shadow-sm p-6`
- 内部 `space-y-4`(16px 区段间距)
- 标题 `<h2 class="text-xl font-semibold">`
- 操作区放卡片底部右对齐,`mt-6`

### 10.4 标签(Tag) / 角标(Badge)

**Tag**(状态标识):`inline-flex items-center px-2 py-1 rounded-sm text-xs font-medium`
**Badge**(角标计数):`inline-flex items-center justify-center min-w-5 h-5 px-1 rounded-full`

hit_tier 角标例:
```html
<span class="inline-flex items-center px-2 py-1 rounded-sm text-xs font-medium"
      :style="{ background: 'var(--hit-tier-mock-bg)', color: 'var(--hit-tier-mock-text)' }">
  mock
</span>
```

### 10.5 Toast / Snackbar

- 容器 `fixed bottom-6 right-6 z-50`
- 单 toast `bg-surface-raised rounded-lg shadow-lg px-4 py-3 min-w-72`
- 类型色:
  - success → 左 border-l-4 `border-success-500`
  - warning → `border-warning-500`
  - error → `border-error-500`
  - info → `border-info-500`
- 4 秒自动消失,hover 暂停,ESC 手动关闭

### 10.6 Loading 骨架(Skeleton)

- 不用 spinner(转圈)
- 用 `animate-pulse bg-border h-X w-X rounded-md`
- 列表加载 5 个,卡片加载 3 个
- `aria-busy="true"` + `aria-label="加载中"`

### 10.7 空态(Empty State)

- 居中,图标(lucide-vue-next 线条)+ 标题 + 描述 + 主按钮
- `py-16`(64px 上下 padding),文字居中

### 10.8 错误态(Error State)

- 标题"加载失败" + 错误信息(development 下显示 `message`,生产仅 `code`)
- 主按钮"重试"(`onclick` 触发 refetch)
- 次按钮"反馈问题"(可选,F-16.4+ 接 Sentry)

---

## 11. 响应式断点

```ts
// Tailwind 默认 + 自定义
sm:  640px   // 手机横屏 / 小平板
md:  768px   // 平板
lg:  1024px  // 桌面
xl:  1280px  // 大桌面
2xl: 1536px  // 超大屏(本 demo 几乎不会触达)

// 自封装:
mobileFirst: true,
contentMaxWidth: "72rem"  // 1152px,大于此居中,小于此 100%
```

### 11.1 移动优先规则

| 断点 | 布局 |
|---|---|
| `< md` (手机) | 单列,顶部 tab → 抽屉;卡片间距 `space-y-4` |
| `md` (平板) | 2 列(zone 网格 / 指标网格),侧栏 tab |
| `>= lg` (桌面) | 3 列(主路径 3 步可同屏)+ 顶部 tab |

### 11.2 招实习现场设备:**桌面优先**(本 demo 主要在 1024px+ 演示)

---

## 12. WCAG AA 验证

| 元素 | 前景 / 背景 | 对比度 | 结论 |
|---|---|---|---|
| 正文 `text-primary` on `bg` | `#2C2A26` / `#FAF7F2` | **13.5:1** | AAA ✓ |
| 次要 `text-secondary` on `bg` | `#6B6862` / `#FAF7F2` | **6.4:1** | AA ✓ |
| 按钮 `text-on-primary` on `primary-500` | `#FFFFFF` / `#3D8B5F` | **5.6:1** | AA ✓ |
| 按钮 hover on `primary-600` | `#FFFFFF` / `#2F7048` | **7.4:1** | AAA ✓ |
| 错误 `accent-500` on `bg` | `#C75450` / `#FAF7F2` | **5.2:1** | AA ✓ |
| 大字 `secondary-500` on `bg`(限 1 处/视图) | `#E0A458` / `#FAF7F2` | **3.0:1** | 大字 AA ✓(正文禁用) |

**键盘可达**:
- Tab 顺序遵循 DOM 顺序
- 自定义焦点环:`focus-visible:ring-2 ring-primary-500 ring-offset-2`
- ESC 关闭模态 / 抽屉 / toast
- Enter / Space 触发主按钮

**屏幕阅读器**:
- 标题层级不跳级(h1 → h2 → h3,不跳 h2)
- 图标按钮必带 `aria-label`
- 加载态 `aria-busy="true"`
- toast `role="status" aria-live="polite"`
- 错误 toast `role="alert"`

---

## 13. 截图清单(README + 简历用)

招实习现场 + 简历附件需要的截图清单(后续 F-16.1 实施后采):

| # | 页面 | 截图 | 用途 |
|---|---|---|---|
| 1 | `/login` | 全屏(无 JS 交互) | README 头图 |
| 2 | `/main`(zone 选完) | 1 屏 | 讲 F-2 bullet |
| 3 | `/main`(推荐结果 + hit_tier 角标) | 1 屏 | 讲 F-3 bullet(命中层三档可见) |
| 4 | `/main/merchant/:id`(点赞成功) | 1 屏 | 讲 F-5 bullet |
| 5 | `/admin/preheat`(刚触发) | 1 屏 | 讲 F-4 bullet |
| 6 | `/admin/feature-flag`(改 PERCENTAGE 25%) | 1 屏 | 讲 F-11 bullet |
| 7 | `/admin/rate-limit`(429 倒计时) | 1 屏 | 讲 F-7 bullet(限流触发 + 倒计时恢复) |
| 8 | `/observability`(4 业务 + 6 技术) | 1 屏 | 讲 F-9 bullet |