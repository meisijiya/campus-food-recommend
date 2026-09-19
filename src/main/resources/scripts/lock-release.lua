-- lock-release.lua
-- F-8 Distributed Lock:原子释放锁(防止误删别人的锁)
-- KEYS[1] = 锁 key
-- ARGV[1] = ownerToken(必须与 tryLock 时传入的 token 一致)
-- 返回:1 = 已释放(确认是 owner);0 = 不是 owner / key 已过期,拒绝释放
--
-- 关键纪律:
--   GET + DEL 之间在 Redis 单线程内串行执行,不会发生"GET 之后被别人续期"的窗口;
--   若 GET 结果 == ownerToken 才 DEL,杜绝 A 释放 B 的锁这种经典 bug。
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end