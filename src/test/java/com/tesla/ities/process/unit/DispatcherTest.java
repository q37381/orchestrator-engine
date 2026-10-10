package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.engine.Dispatcher;
import com.tesla.ities.process.core.engine.ExecutionEngine;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 调度器单测(不启 Spring): 认领循环的核心 dispatchOnce 用反射直调, 覆盖
 * 空槽计算 / CAS 抢占成功与失败 / 提交驱动 / 不超发 break; 生命周期方法幂等。
 */
public class DispatcherTest {

    /** 记录 driveClaimed 调用次数的引擎替身(避免真实认领打库)。 */
    private static ExecutionEngine countingEngine(AtomicInteger calls, CountDownLatch latch) {
        return new ExecutionEngine(null, null, null, null, null, null, null, null, null) {
            @Override
            public Status driveClaimed(String procInstId) {
                calls.incrementAndGet();
                if (latch != null) {
                    latch.countDown();
                }
                return Status.SUCCESS;
            }
        };
    }

    /** 只回放 findClaimableIds / claim 两个方法的 mapper 替身。 */
    private static ProcessInstMapper mapper(List<String> claimable, AtomicInteger claimResult) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "findClaimableIds":
                    return new ArrayList<>(claimable);
                case "claim":
                    return claimResult.get();
                default:
                    Class<?> rt = method.getReturnType();
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == boolean.class) {
                        return false;
                    }
                    return rt == long.class ? 0L : null;
            }
        };
        return (ProcessInstMapper) Proxy.newProxyInstance(
                ProcessInstMapper.class.getClassLoader(), new Class<?>[]{ProcessInstMapper.class}, h);
    }

    /** 池满: 覆盖 getMaxPoolSize/getActiveCount 让 freeSlots=0 -> slots=1, 用来触发"不超发"break。 */
    private static ThreadPoolTaskExecutor tinyPool() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor() {
            @Override public int getMaxPoolSize() {
                return 1;
            }
            @Override public int getActiveCount() {
                return 1; // 视为满 -> freeSlots=0
            }
        };
        ex.initialize();
        return ex;
    }

    private static int invokeDispatchOnce(Dispatcher d) throws Exception {
        Method m = Dispatcher.class.getDeclaredMethod("dispatchOnce");
        m.setAccessible(true);
        return (int) m.invoke(d);
    }

    @Test
    public void dispatchOnceClaimsAndDrivesEachPending() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger drove = new AtomicInteger();
        Dispatcher d = new Dispatcher(
                mapper(Arrays.asList("P1"), new AtomicInteger(1)),        // claim 命中
                countingEngine(drove, latch),
                tinyPool(), false, 10, 16);

        int worked = invokeDispatchOnce(d);
        Assert.assertEquals("认领一条", 1, worked);
        Assert.assertTrue("提交线程池驱动", latch.await(2, TimeUnit.SECONDS));
        Assert.assertEquals(1, drove.get());
    }

    @Test
    public void dispatchOnceSkipsWhenCasPreemptedByOthers() throws Exception {
        Dispatcher d = new Dispatcher(
                mapper(Arrays.asList("P1"), new AtomicInteger(0)),        // claim 未命中(别人抢了)
                countingEngine(new AtomicInteger(), null),
                tinyPool(), false, 10, 16);
        Assert.assertEquals(0, invokeDispatchOnce(d));
    }

    @Test
    public void dispatchOnceStopsAtFreeSlotsLimit() throws Exception {
        // 两条待认领, 但 slots=1(池满) -> 第一条认领后 claimed>=slots break, 不再认领第二条
        AtomicInteger drove = new AtomicInteger();
        Dispatcher d = new Dispatcher(
                mapper(Arrays.asList("P1", "P2"), new AtomicInteger(1)),
                countingEngine(drove, null),
                tinyPool(), false, 10, 16);
        int worked = invokeDispatchOnce(d);
        Assert.assertEquals("不超发, 只认领到 slots 上限", 1, worked);
    }

    @Test
    public void disabledDispatcherDoesNotStartLoop() throws Exception {
        Dispatcher d = new Dispatcher(mapper(new ArrayList<>(), new AtomicInteger(0)),
                countingEngine(new AtomicInteger(), null), tinyPool(), false, 10, 16);
        d.afterSingletonsInstantiated();   // enabled=false -> 只记日志, 不起线程
        Assert.assertFalse(isRunning(d));
    }

    @Test
    public void startLoopIsIdempotentAndStopClears() throws Exception {
        Dispatcher d = new Dispatcher(mapper(new ArrayList<>(), new AtomicInteger(0)),
                countingEngine(new AtomicInteger(), null), tinyPool(), true, 1000, 16);
        d.startLoop();
        Assert.assertTrue(isRunning(d));
        d.startLoop();                       // 已在跑 -> compareAndSet 失败, 直接返回
        Assert.assertTrue(isRunning(d));
        d.stop();
        Assert.assertFalse(isRunning(d));
    }

    /** enabled=true 触发 afterSingletons -> 起守护线程跑循环; 空队列下 worked=0 -> sleep; 停后线程退出。 */
    @Test
    public void enabledAfterSingletonsStartsPollingLoop() throws Exception {
        Dispatcher d = new Dispatcher(mapper(new ArrayList<>(), new AtomicInteger(0)),
                countingEngine(new AtomicInteger(), null), tinyPool(), true, 5, 16);
        d.afterSingletonsInstantiated();     // 走 enabled 分支 -> startLoop
        Assert.assertTrue(isRunning(d));
        Thread.sleep(60);                     // 让它空转几轮(worked=0 -> Thread.sleep)
        d.stop();
        Thread.sleep(30);
        Assert.assertFalse(isRunning(d));
    }

    /** dispatchOnce 抛异常 -> loop 捕获 -> sleepQuietly 继续; 第二次迭代前把 running 置灰退出。 */
    @Test
    public void loopSwallowsDispatchErrorsAndKeepsRunning() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Dispatcher[] holder = new Dispatcher[1];
        ProcessInstMapper boom = (ProcessInstMapper) Proxy.newProxyInstance(
                ProcessInstMapper.class.getClassLoader(), new Class<?>[]{ProcessInstMapper.class},
                (p, m, args) -> {
                    if ("findClaimableIds".equals(m.getName())) {
                        if (calls.getAndIncrement() == 0) {
                            throw new RuntimeException("dispatch boom");   // 触发 loop 的 catch(Exception) -> sleepQuietly
                        }
                        // 第二轮前关掉循环
                        java.lang.reflect.Field rf = Dispatcher.class.getDeclaredField("running");
                        rf.setAccessible(true);
                        ((java.util.concurrent.atomic.AtomicBoolean) rf.get(holder[0])).set(false);
                        return new ArrayList<String>();
                    }
                    Class<?> rt = m.getReturnType();
                    return rt == int.class ? 0 : rt == boolean.class ? false : rt == long.class ? 0L : null;
                });
        Dispatcher d = new Dispatcher(boom, countingEngine(new AtomicInteger(), null), tinyPool(), true, 1, 16);
        holder[0] = d;
        d.afterSingletonsInstantiated();      // enabled -> 起线程; 异常->sleepQuietly->下一轮退出
        Thread.sleep(120);
        d.stop();
        Assert.assertTrue("至少经历了一次异常吞并", calls.get() >= 1);
    }

    private static boolean isRunning(Dispatcher d) throws Exception {
        java.lang.reflect.Field f = Dispatcher.class.getDeclaredField("running");
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicBoolean) f.get(d)).get();
    }
}
