package com.meisijiya.campusfood.module.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-7 W2:CaffeineLocalBucket 单测 — 覆盖 happy path / 拒绝 / 并发 CAS 三场景。
 *
 * @author meisijiya
 */
class CaffeineLocalBucketTest {

    @Test
    void freshBucket_allowsBurstThenDenies() {
        // capacity=5, refill=1/s → 头 5 个请求全过,第 6 个拒绝
        CaffeineLocalBucket bucket = new CaffeineLocalBucket(5, 1.0);
        String key = "user:test-burst";

        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(key), "request #" + (i + 1) + " should be allowed within burst");
        }
        assertFalse(bucket.tryAcquire(key), "6th request should be denied (bucket empty)");
    }

    @Test
    void refillOverTime_releasesTokens() throws InterruptedException {
        // capacity=2, refill=100/s → 头 2 个过,睡 200ms 后应该至少有 ~20 个 token
        CaffeineLocalBucket bucket = new CaffeineLocalBucket(2, 100.0);
        String key = "user:test-refill";

        assertTrue(bucket.tryAcquire(key));
        assertTrue(bucket.tryAcquire(key));
        assertFalse(bucket.tryAcquire(key), "3rd request immediately after burst should be denied");

        // 睡 200ms:refill 100/s × 0.2s = 20 tokens,但 cap=2 所以只能补到 2
        Thread.sleep(200);
        assertTrue(bucket.tryAcquire(key), "after 200ms refill, 1 token should be available");
        assertTrue(bucket.tryAcquire(key), "after 200ms refill, 2nd token should be available");
        assertFalse(bucket.tryAcquire(key), "after 200ms refill, 3rd request should still be denied (cap=2)");
    }

    @Test
    void differentKeys_haveIndependentBuckets() {
        // 同一 CapacityLocalBucket 实例持有多个桶,各 key 独立
        CaffeineLocalBucket bucket = new CaffeineLocalBucket(1, 0.01);
        assertTrue(bucket.tryAcquire("user:alice"));
        assertTrue(bucket.tryAcquire("user:bob"), "different key should have its own burst");
        assertFalse(bucket.tryAcquire("user:alice"), "alice's bucket is exhausted");
        assertFalse(bucket.tryAcquire("user:bob"), "bob's bucket is exhausted");
    }

    @Test
    void concurrentAcquire_respectsBurstUnderCAS() throws InterruptedException {
        // capacity=100, refill=0.0001(几乎关闭补充)— 100 个并发请求,只允许 100 个通过
        // 注意:refill=0 在构造器会抛 IllegalArgumentException(参数必须 > 0),
        //      这里用极小正数 + 测试运行 < 1s 模拟"关闭补充"语义。
        CaffeineLocalBucket bucket = new CaffeineLocalBucket(100, 0.0001);
        String key = "api:GET:/test/concurrent";

        int threads = 32;
        int requestsPerThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger denied = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (bucket.tryAcquire(key)) {
                            allowed.incrementAndGet();
                        } else {
                            denied.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "concurrent test should complete within 10s");

        // capacity=100,refill=0 → 恰好 100 个放行,其余 220 个拒绝
        assertEquals(100, allowed.get(), "allowed should equal capacity exactly (no over-allow under CAS)");
        assertEquals(threads * requestsPerThread - 100, denied.get(), "denied should equal remainder");
    }

    @Test
    void constructor_rejectsInvalidArgs() {
        // capacity / refill 必须 > 0
        try {
            new CaffeineLocalBucket(0, 1.0);
            assertFalse(true, "should have thrown for capacity=0");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new CaffeineLocalBucket(10, 0.0);
            assertFalse(true, "should have thrown for refill=0");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new CaffeineLocalBucket(-1, 1.0);
            assertFalse(true, "should have thrown for negative capacity");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void tryAcquire_withMultiplePermits_respectsCount() {
        // capacity=5, refill=0.0001(几乎关闭补充)— 一次性 tryAcquire(3) 应扣 3 个 token,剩 2 个
        CaffeineLocalBucket bucket = new CaffeineLocalBucket(5, 0.0001);
        String key = "api:bulk";

        assertTrue(bucket.tryAcquire(key, 3), "first 3-permit request should be allowed");
        assertTrue(bucket.tryAcquire(key, 2), "second 2-permit request should be allowed (5-3=2 remaining)");
        assertFalse(bucket.tryAcquire(key, 1), "3rd 1-permit request should be denied (no tokens left)");
    }

    @Test
    void tryAcquire_zeroPermits_isAlwaysAllowed() {
        // 0 permit 是无操作,直接放行(orchestrator 协调版契约)— 不消耗 token,也不抛异常
        // 注意:CaffeineLocalBucket 协调版对 permits=0 返 true;permits<0 才会抛 IllegalArgumentException
        CaffeineLocalBucket bucket = new CaffeineLocalBucket(1, 0.0001);
        String key = "api:noop";
        assertTrue(bucket.tryAcquire(key, 0));
        assertTrue(bucket.tryAcquire(key, 0));
        // 真实 token 还在(0-permit 不消耗)
        assertTrue(bucket.tryAcquire(key, 1));
    }

    @Test
    void tryAcquire_negativeOrZeroPermits_isAlwaysAllowed() {
        // orchestrator 协调版契约:permits <= 0 都按"放行"语义处理(不抛异常,也不消耗 token)
        // 接口层 default tryAcquire(key) → tryAcquire(key, 1),所以 0 / 负数 是"noop"路径。
        CaffeineLocalBucket bucket = new CaffeineLocalBucket(1, 0.0001);
        // 0-permit:不消耗 token,真实 token 1 还在
        assertTrue(bucket.tryAcquire("k1", 0));
        assertTrue(bucket.tryAcquire("k1", 1));
        // 负数:也按放行(虽然接口层不会传负数,但实现层宽容)
        assertTrue(bucket.tryAcquire("k2", -1));
        assertTrue(bucket.tryAcquire("k2", 1));
    }
}
