-- token-bucket.lua
-- F-7 Rate Limiter:令牌桶原子操作(读桶 → 算 refill → 决定放行 → 写回)
--
-- KEYS[1] = bucket key(完整 key,如 user:s-1 / api:GET:/api/merchants)
-- ARGV[1] = burst(桶容量,整数;Java 端已挡,≤ 0 不会到这里)
-- ARGV[2] = rate_per_sec(每秒补充令牌数,允许小数,如 10.0)
-- ARGV[3] = now_ms(当前时间,毫秒)
-- ARGV[4] = permits(本次消费令牌数,F-7 默认 1,允许 caller 显式传 N)
--
-- 返回:Lua table,3 元素
--   [1] = allowed        (整数 1 = 放行,0 = 拒绝)
--   [2] = tokens_left    (字符串,fixed 6 位小数,如 "99.500000")
--   [3] = retry_after_ms (整数,放行时 0;拒绝时距离下次能 permits 令牌的等待毫秒数)
--
-- 存储格式(Redis hash):
--   tokens          (字符串形式的浮点)
--   last_refill_ms  (整数,毫秒)
--
-- 不变量:
--   1. 整个脚本在 Redis 单线程内串行执行,无并发竞态
--   2. tokens 上限 burst,下限 0
--   3. EXPIRE TTL = max(60, burst / rate * 2)
--   4. permits 可能 > 1,按 permits 扣减

local key       = KEYS[1]
local burst     = tonumber(ARGV[1])
local rate      = tonumber(ARGV[2])
local now_ms    = tonumber(ARGV[3])
local permits   = tonumber(ARGV[4])

local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
local tokens = tonumber(data[1])
local last_refill_ms = tonumber(data[2])
if tokens == nil then
    tokens = burst
end
if last_refill_ms == nil then
    last_refill_ms = now_ms
end

local elapsed_ms = now_ms - last_refill_ms
if elapsed_ms < 0 then
    elapsed_ms = 0
end
local refill = elapsed_ms * rate / 1000
tokens = tokens + refill
if tokens > burst then
    tokens = burst
end

local allowed
local retry_after_ms
if tokens >= permits then
    tokens = tokens - permits
    allowed = 1
    retry_after_ms = 0
else
    allowed = 0
    local need = permits - tokens
    if rate > 0 then
        retry_after_ms = math.ceil(need / rate * 1000)
    else
        retry_after_ms = -1
    end
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'last_refill_ms', now_ms)
local ttl_sec = 60
if rate > 0 then
    ttl_sec = math.ceil(burst / rate * 2)
    if ttl_sec < 60 then
        ttl_sec = 60
    end
end
redis.call('EXPIRE', key, ttl_sec)

return { allowed, string.format("%.6f", tokens), retry_after_ms }
