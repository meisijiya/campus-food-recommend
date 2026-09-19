-- lock-try.lua
-- F-8 Distributed Lock:原子获取锁
-- KEYS[1] = 锁 key
-- ARGV[1] = ownerToken(UUID,锁定者身份标识,防止 A 释放 B 的锁)
-- ARGV[2] = ttlSec(秒,默认 30)
-- 返回:1 = 成功获取锁;0 = key 已存在,获取失败
--
-- 实现选择:用 SETNX + EXPIRE 两步走(Lua 内原子)而不是 SET ... NX EX,
-- 因为 SET NX EX 在 Redis < 2.6.12 不可用,且两步走对 ownerToken 的可见性更清晰
-- (SETNX 成功后立即 EXPIRE,即使脚本中途断电 Redis 也只丢锁不会丢 TTL)。
--
-- 不变量:
--   1. 整个脚本在 Redis 单线程内串行执行,无并发问题
--   2. SETNX 成功但 EXPIRE 失败 → key 永久存在(防御策略:由 Watchdog 在每轮 PEXPIRE 兜底)
if redis.call('SETNX', KEYS[1], ARGV[1]) == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
    return 1
else
    return 0
end