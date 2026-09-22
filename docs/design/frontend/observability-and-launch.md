# Demo 前端 design.md · 可观测性 + 上线清单 + Demo 脚本

> **本文件**:自绘 Recharts 指标页(Q7 锁定)+ 上线 checklist(Q11 加 init.ps1 stage 7)+ 招实习现场 demo 脚本(Q2 主场景)+ 简历截图清单。
> **配套**:`overview.md`(原则) / `visual-system.md`(截图清单) / `api-contract.md`(/actuator/prometheus parse)

---

## 1. 自绘 Recharts 指标页(Q7 锁定)

### 1.1 选 Recharts 不选 iframe Grafana(ADR-0011 决策 7)

| 维度 | 自绘 Recharts | iframe Grafana |
|---|---|---|
| 招实习现场打分 | **9.9** ⭐ | 6.0 |
| 简历截图(README)| **9.9** ⭐(自解释)| 5.0(灰盒子)|
| 远程面试录屏 | **9.0**(录屏可读)| 4.0(Grafana 加载慢)|
| 实施成本 | 4 小时(1 个 .vue + 1 个 parser) | 0(直接嵌)|
| 离线截图可读 | **9.9** ⭐ | 2.0 |

**结论**:Recharts 自绘,不嵌 Grafana。本设计稿**必须**让前端离开 Grafana 服务也能完整展示指标。

### 1.2 指标数据源

```
GET /actuator/prometheus
   ↓
prometheus-parser.ts(parsePrometheus)
   ↓
Metric[] { name, type, tags, value }
   ↓
按 name 分组 → 4 业务 + 6 技术两类卡片
   ↓
Recharts LineChart / BarChart 渲染
```

### 1.3 4 业务指标卡片(Q7 锁定)

| 卡片 | 指标 | 渲染 | tag 维度 |
|---|---|---|---|
| **点赞累计** | `like_count_total` | 数字 + 折线(近 60 秒) | `endpoint` 切分 |
| **推荐延迟** | `recommend_latency_seconds` | 直方图(`{endpoint, hit_tier}`) | `hit_tier ∈ {mock, dashscope, fallback}` |
| **缓存命中率** | `cache_hit_ratio` | 堆叠条(`{cache_name, hit_tier}`) | `hit_tier ∈ {L0, L1, L2, miss}` |
| **会话阶段分布** | `session_stage_distribution` | 饼图(`{stage}`) | `stage ∈ {INIT, ZONE, CUISINE, MERCHANT}` |

### 1.4 6 技术指标卡片

| 卡片 | 指标 | 渲染 |
|---|---|---|
| **Tomcat 线程** | `tomcat_threads_busy` | 折线 |
| **Hikari 连接池** | `hikari_pool_active` | 折线 |
| **Redis 连接池** | `redis_pool_active` | 折线 |
| **JVM 堆内存** | `jvm_memory_used_bytes` | 折线(MB) |
| **GC 暂停** | `gc_pause_seconds` | 折线(ms,P99) |
| **HTTP 请求分布** | `http_server_requests_seconds_count` | 堆叠条(2xx/4xx/5xx) |

### 1.5 视图骨架

```vue
<!-- ObservabilityView.vue(节选) -->
<template>
  <div class="p-6 space-y-6 max-w-content">
    <header class="flex items-center justify-between">
      <h1 class="text-2xl font-semibold">可观测性</h1>
      <label class="flex items-center gap-2">
        <input type="checkbox" v-model="autoRefresh" />
        <span class="text-sm">自动刷新(5 秒)</span>
      </label>
    </header>

    <section>
      <h2 class="text-lg font-medium mb-3">业务指标</h2>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <BaseCard title="点赞累计">
          <MetricLine :series="series.likeCount" />
          <MetricNumber :value="series.likeCount.current" />
        </BaseCard>
        <BaseCard title="推荐延迟">
          <MetricHistogram :series="series.recommendLatency" :tags="['mock','dashscope','fallback']" />
        </BaseCard>
        <BaseCard title="缓存命中率">
          <MetricStackedBar :series="series.cacheHit" :tags="['L0','L1','L2','miss']" />
        </BaseCard>
        <BaseCard title="会话阶段分布">
          <MetricPie :series="series.sessionStage" :tags="['INIT','ZONE','CUISINE','MERCHANT']" />
        </BaseCard>
      </div>
    </section>

    <section>
      <h2 class="text-lg font-medium mb-3">技术指标</h2>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
        <BaseCard v-for="tech in techCards" :key="tech.name" :title="tech.title">
          <MetricLine :series="tech.series" />
        </BaseCard>
      </div>
    </section>
  </div>
</template>
```

### 1.6 prometheus-parser.ts

```ts
// lib/prometheus-parser.ts
export interface Metric {
  name: string;
  type: "counter" | "gauge" | "histogram" | "summary" | "unknown";
  tags: Record<string, string>;
  value: number;
}

export function parsePrometheus(text: string): Metric[] {
  const out: Metric[] = [];
  const lines = text.split("\n");
  let currentType = "unknown";
  for (const raw of lines) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) {
      const m = line.match(/^#\s*TYPE\s+(\S+)\s+(\S+)/);
      if (m) currentType = m[2];
      continue;
    }
    const m = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+([\d.eE+\-]+|NaN|\+Inf|-Inf)\s*(\d+)?$/);
    if (!m) continue;
    const [, name, , tagsStr, valStr] = m;
    const tags: Record<string, string> = {};
    if (tagsStr) {
      for (const pair of tagsStr.split(",")) {
        const [k, v] = pair.split("=");
        tags[k.trim()] = v.replace(/^"|"$/g, "");
      }
    }
    const value = Number(valStr);
    out.push({ name, type: currentType as Metric["type"], tags, value });
  }
  return out;
}

// 按 name 聚合
export function groupByName(metrics: Metric[]): Record<string, Metric[]> {
  return metrics.reduce((acc, m) => {
    (acc[m.name] ??= []).push(m);
    return acc;
  }, {} as Record<string, Metric[]>);
}
```

### 1.7 自动刷新策略

```ts
const data = ref<Metric[]>([]);
const error = ref<string | null>(null);
const autoRefresh = ref(true);

const fetch = async () => {
  try {
    const text = await (await fetch("/actuator/prometheus")).text();
    data.value = parsePrometheus(text);
    error.value = null;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "unknown";
  }
};

onMounted(() => {
  fetch();
  const id = setInterval(() => autoRefresh.value && fetch(), 5000);
  onUnmounted(() => clearInterval(id));
});
```

---

## 2. 上线清单(Q11 锁定,init.ps1 stage 7 校验)

### 2.1 init.ps1 stage 7 逻辑

```powershell
# === 7/7 ===
Write-Host "[init.sh] 阶段 7/7: frontend-design 文档校验 ..."
$designRoot = "docs/design/frontend"
$adrPath    = "docs/adr/0011-frontend-design-decisions.md"

$required = @(
    @{ p = "$designRoot/overview.md";                     min = 80 },
    @{ p = "$designRoot/visual-system.md";                min = 150 },
    @{ p = "$designRoot/architecture.md";                 min = 150 },
    @{ p = "$designRoot/api-contract.md";                 min = 120 },
    @{ p = "$designRoot/observability-and-launch.md";     min = 120 },
    @{ p = "$designRoot/mock/login.html";                 min = 0 },   # 存在即可
    @{ p = $adrPath;                                      min = 50 },
)
$failed = $false
foreach ($r in $required) {
    if (-not (Test-Path $r.p)) {
        Write-Host "[init.sh] FAIL: missing $($r.p)"; $failed = $true; continue
    }
    if ($r.min -gt 0) {
        $lineCount = (Get-Content $r.p).Count
        if ($lineCount -lt $r.min) {
            Write-Host "[init.sh] FAIL: $($r.p) lines=$lineCount < min=$($r.min)"
            $failed = $true
        } else {
            Write-Host "[init.sh] OK:   $($r.p) lines=$lineCount"
        }
    } else {
        Write-Host "[init.sh] OK:   $($r.p) exists"
    }
}

# visual-system.md 必须包含 --color-primary 与 --space-4 grep 关键字
$vsPath = "$designRoot/visual-system.md"
$vsContent = Get-Content $vsPath -Raw
foreach ($kw in @("--color-primary:", "--space-4:")) {
    if (-not $vsContent.Contains($kw)) {
        Write-Host "[init.sh] FAIL: visual-system.md 缺关键字 $kw"
        $failed = $true
    }
}

if ($failed) { exit 1 }
Write-Host "[init.sh] PASS: 阶段 7/7 frontend-design 校验通过"
```

### 2.2 上线 checklist(实施时人工对照)

#### 2.2.1 文档(Q11 完成定义)

- [ ] `docs/design/frontend/overview.md` ≥ 80 行
- [ ] `docs/design/frontend/visual-system.md` ≥ 150 行 + 含 `--color-primary:` / `--space-4:` 关键字
- [ ] `docs/design/frontend/architecture.md` ≥ 150 行
- [ ] `docs/design/frontend/api-contract.md` ≥ 120 行
- [ ] `docs/design/frontend/observability-and-launch.md` ≥ 120 行
- [ ] `docs/design/frontend/mock/login.html` 存在 + 纯静态 + 无 `<script>` 标签
- [ ] `docs/adr/0011-frontend-design-decisions.md` ≥ 9 项决策 + 每项"被推翻方案"段
- [ ] `init.ps1` stage 7/7 退出码 0

#### 2.2.2 视觉

- [ ] 暖白背景 `#FAF7F2` 全局
- [ ] 主色 `#3D8B5F`(校园绿)用于主按钮 + 主链接
- [ ] 暖橙 `#E0A458` 限 1 处 / 视图(焦点元素)
- [ ] 圆角 `0.5rem` 主体 + `0.75rem` 卡片,**不**用 `rounded-2xl`
- [ ] 阴影最多 1 层
- [ ] WCAG AA:正文 4.5:1 / 大字 3:1 全通过
- [ ] 焦点环可见(`focus-visible:ring-2`)
- [ ] 屏幕阅读器:标题层级不跳级 + 图标按钮 `aria-label`

#### 2.2.3 工程

- [ ] `frontend/` 目录不进入 `git`(`.gitignore` 已加 `frontend/node_modules/` + `frontend/dist/`)
- [ ] Vue3 + Vite + Pinia + Vue Router 4 + Axios + Recharts + lucide-vue-next + Tailwind CSS
- [ ] TypeScript strict 必开
- [ ] Pinia 切片 ≤ 8 个 + 每个 ≤ 200 行
- [ ] 组件 ≤ 200 行
- [ ] 路由懒加载(`() => import(...)`)
- [ ] Bundle gzipped < 200KB(不含 Recharts)

#### 2.2.4 鉴权 / 错误

- [ ] JWT 拦截器在 axios request 加 `Authorization: Bearer <token>`
- [ ] 401 自动 refresh 串行化(模块级 `refreshing` Promise)
- [ ] refresh 失败清 store + 跳 `/#/login`
- [ ] `localStorage` 三 key(accessToken / refreshToken / user)
- [ ] 限流 429 → CountdownButton + 1 秒倒计时
- [ ] 业务错误 toast(`code != 0`)
- [ ] 401 自动 refresh toast 不弹(避免长错误链噪声)

#### 2.2.5 F-3 卖点

- [ ] 推荐结果卡片右上角 HitTierBadge(mock / dashscope / fallback 三档)
- [ ] 推荐结果下方 TokenCounter(prompt + completion tokens)
- [ ] 颜色严格按 `visual-system.md §2.6` 三档映射

#### 2.2.6 可观测性(Q7)

- [ ] 4 业务指标卡 + 6 技术指标卡(§1.3 / §1.4)
- [ ] `/actuator/prometheus` 用自研 `parsePrometheus()` 解析
- [ ] 自动刷新开关默认开,5 秒间隔
- [ ] 数据源失败 ErrorState + 重试
- [ ] 不嵌 iframe Grafana

---

## 3. 招实习现场 demo 脚本(Q2 主场景)

### 3.1 时长预算

| 阶段 | 时长 | 节奏 |
|---|---|---|
| 登录 + 进主路径 | 30s | 慢节奏,**重点讲 F-1 鉴权** |
| 主路径选 zone→cuisine→推荐 | 60s | **重点讲 F-2 槽位 + F-3 hit_tier 卖点** |
| 点 like + 看详情 | 30s | **重点讲 F-5 幂等点赞** |
| 切到 Admin tab | 60s | **讲 F-11 灰度** + **F-7 限流触发** |
| 切到指标 tab | 30s | 视觉冲顶,**讲 F-9 可观测性** |
| 提问 / 答疑 | 60s | — |
| **合计** | **~5 分钟** | 留给招聘方 |

### 3.2 讲解台词(逐节点)

> 以下脚本假设招聘方问"你这个项目做了什么",从登录开始串到指标。

**登录(30s)**
> "这是校园美食推荐平台,**无状态架构**,JWT 鉴权。后端 Spring Boot 3.5 + JDK 21,前端 Vue3 + Vite。我先用 `demo / demo` 登录,看右上角 user 信息出现,这是 `authHeader` 派生的。"

**主路径(60s)**
> "登录后进主路径。这里我用**会话槽位**四阶段,**严格单向流转** INIT → Zone → Cuisine → Merchant。
> 选东区食堂(Z-3)后,槽位进 ZONE;再选川菜(C-12),进 CUISINE。
> 点'推荐',调用 `/api/recommend`。**注意右上角角标** — 现在是 `mock` 命中层,意思是 dev profile 下用 MockChatModel,没调真实百炼 API。如果切到 bench profile,会显示 `dashscope`;如果反思重试 2 次都不合规,会显示 `fallback`,走规则兜底。这是 F-3 **结构化输出 + 反思重试 + fallback** 的完整链路。
> 下方还有 prompt / completion token 数,这是 F-2 的量化对比口径。"

**点赞(30s)**
> "现在点 like。**注意 60 秒内重复点不会触发第二次** — 这是 F-5 的 Redis SETNX 幂等拦截,业务量大的场景下非常关键。"

**Admin tab(60s)**
> "切到 Admin tab,先看 preheat,**凌晨 3 点定时跑的多实例防重**走 Redis SETNX 锁。
> 再看 feature-flag,改成 PERCENTAGE 25%。这是 F-11 的灰度能力,**不用重启,Redis hash 立即生效**。
> 最后看 rate-limit-debug,**点'触发限流'** — 在 1 秒内发 30 个请求。**看!成功 X 个,限流拒绝 Y 个**。按钮变'1 秒后重试' — 这是 429 + Retry-After 的前端兜底。F-7 的**双层令牌桶**,用户级桶 + API 全局桶。"

**指标 tab(30s)**
> "切到指标 tab。**这里完全脱离 Grafana**,前端直接 parse `/actuator/prometheus`,自绘 Recharts。**4 个业务指标 + 6 个技术指标** — 点赞累计、推荐延迟按 hit_tier 切、缓存命中率按 L0/L1/L2 切、会话阶段分布。技术指标含 GC 暂停和 Hikari 连接池,**SLO 关键指标**都能在这里看到。"

**收尾**
> "简历上 5 + 2 = 7 bullet 都对应到这里,F-1 到 F-9 全 evidence 化。这个项目从 2026-09-06 到 09-22 大约 16 天,期间 18 个 ticket。代码与文档都在仓内 `AGENTS.md` 起,有兴趣可以翻 `docs/adr/` 看决策记录。"

### 3.3 备用方案

| 现场状况 | 应对 |
|---|---|
| 后端未启 | `docker compose up -d app` + 演示降级,只讲登录 + 主路径 |
| 招实习现场没网 | 提前录 5 分钟演示视频(录屏 tool),现场播放 + 翻代码 |
| 招聘方追问 token 计算口径 | 翻 `CONTEXT.md §9`,有 50 轮同任务的对比脚本 |
| 招聘方追问 fallback 触发条件 | 翻 `docs/api/api-reference.md §3.1` advisor chain 段 |
| 招聘方要求看压测数据 | 翻 `evidence/f1-jmeter.jtl` + `evidence/f5-p99_stats.csv` |
| 招聘方要求看真实 AI 调用 | 切换 `SPRING_PROFILES_ACTIVE=bench` 重启后端(需要 API key) |

### 3.4 现场设备清单

- 笔记本(已装 Docker Desktop / Node 20+ / 后端镜像)
- 提前 5 分钟跑 `docker compose up -d app` 启动
- 提前打开 5 个 tab:`/#/login` / `/#/main` / `/#/admin` / `/#/observability` / 仓库 GitHub
- 屏幕分辨率 ≥ 1440×900(展示 3 tab 主路径)

---

## 4. README 截图清单(Q2 次场景)

### 4.1 截图规格

| 项 | 值 |
|---|---|
| 尺寸 | 1440×900(桌面) |
| 比例 | 16:10 |
| 工具 | macOS `cmd+shift+4` / Windows `Snipping Tool` |
| 格式 | PNG,无压缩 |
| 命名 | `docs/design/frontend/screenshots/01-login.png` 等 |
| 入仓 | 否(`.gitignore` 加 `docs/design/frontend/screenshots/*.png`),本地维护 |

### 4.2 截图内容

1. **登录页**(`/login`) — README 头图
2. **主路径选 zone**(`/main`) — 讲 F-2
3. **推荐结果 + hit_tier + break**(`/admin/rate-limit`) — 讲 F-7
4. **触发限流 429**(`/admin/rate-limit`) — 讲 F-7 UX
5. **指标页**(`/observability`) — 讲 F-9

---

## 5. 风险与未决

| 风险 | 严重度 | 缓解 |
|---|---|---|
| hit_tier 启发式推断与后端日志不一致 | 中 | 招实习现场借机讲"前端能 UX 兜住,但生产看后端日志" |
| 自绘 Recharts 指标项 ≥ 10 个卡片,首屏慢 | 低 | 懒加载 + 自动刷新默认 5 秒 |
| Bundle 大(Recharts + Vue + Router + Pinia ≈ 300KB gzipped)| 中 | Recharts 按需引;目标 < 200KB 不含 Recharts |
| CORS 跨域(开发期 localhost)| 低 | 已配 allowed-origins |
| dev profile 下 `/actuator/prometheus` 关闭,前端空白 | 低 | ErrorState 提示"prod profile 才可看" |
| 远程面试录屏时长限制 | 低 | 5 分钟脚本压得住 |

---

## 6. 长期演进(不在本轮范围)

| 阶段 | 触发 | 内容 |
|---|---|---|
| F-16.1 骨架 | 用户授权开干 | Vite + Vue3 + Pinia + Router 4 起骨架 + 路由懒加载 + JWT 拦截器 |
| F-16.2 核心流 | F-16.1 done | 5 个核心组件 + 4 主路径视图 |
| F-16.3 指标页 | F-16.2 done | Recharts 自绘 + 自动刷新 + ErrorState |
| F-16.4 Admin | F-16.2 done | Preheat + FeatureFlag + RateLimit 3 sub-tab |
| F-16.5 部署 | F-16.4 done | nginx.conf 静态 serve + docker compose 端到端 |
| F-16.6 截图 | F-16.5 done | README 5 张截图入仓 |
| F-17.x 后端契约变更追踪 | 接口文档就绪后 | 同 §10 接口契约变更追踪 |

---

## 7. 实施触发条件(本 demo 不实施,留作信号)

本 design.md **不是**实施许可。下一轮若开干,必须:

1. 用户开新一轮显式说"开干前端" / "实施 F-16" / "开 F-16.1"
2. 走 grill-with-docs skill 重启设计访谈(本 design.md 是参考,不替代)
3. 开 ADR-0012 "全栈 demo 二次决议"
4. 按 ADR-0012 决议建立 F-16.1+ 工单体系

不满足以上 4 条,**不实施**。