-- lock-extend.lua
-- F-8 Distributed Lock:看门狗续期(原子)
-- KEYS[1] = 锁 key
-- ARGV[1] = ownerToken(必须与 tryLock 时传入的 token 一致)
-- ARGV[2] = ttlMs(毫秒,默认 30000 = 30s,Watchdog 每 10s 调用一次)
-- 返回:1 = 续期成功(确认是 owner);0 = 不是 owner / key 已过期 / key 不存在
--
-- 为什么用 PEXPIRE 而非 EXPIRE:
--   续期间隔是 10s,精度更细才不至于把 TTL 重置成 30s 时精度损失;
--   毫秒级续期让看门狗可以把 TTL 维持在"10s ≤ TTL ≤ 30s"的安全区间。
--
-- 与 release 同样的 GET 校验:续期前先确认 owner 身份,防止 A 续期 B 的锁。
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('PEXPIRE', KEYS[1], ARGV[2])
else
    return 0
end