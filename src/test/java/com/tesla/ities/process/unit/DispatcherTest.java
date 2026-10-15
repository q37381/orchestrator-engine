package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.engine.Dispatcher;
import com.tesla.ities.process.core.engine.ExecutionEngine;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调度器单测(不启 Spring): 认领循环的核心 dispatchOnce 用反射直调, 覆盖
 * 空槽计算 / CAS 抢占成功与失败 / 提交驱动 / 不超发 break; 生命周期方法幂等。
 *
 * 协作对象一律 {@code @Mock} 字段(mapper / 引擎 / 并行池)。被测的 {@link Dispatcher} 不能靠
 * {@code @InjectMocks}: 它的构造子还带 boolean/long/int 三个配置项, Mockito 只会给原始类型传
 * 0/false, 于是 enabled/pollInterval/claimBatch 全部失真 —— 这里按构造器显式 new, 只把替身做成字段。
 * 并行池被 mock 成"同步执行 + 恒满"(freeSlots=0 -> slots=1), 因此驱动落在调用线程上, 用例不必等 latch。
 */
@RunWith(MockitoJUnitRunner.class)
public class DispatcherTest {

    @Mock
    private ProcessInstMapper processMapper;
    @Mock
    private ExecutionEngine engine;
    @Mock
    private ThreadPoolTaskExecutor parallelExecutePool;
    /** 只为报队列长度(dispatchOnce 算剩余容量要读它)。 */
    @Mock
    private ThreadPoolExecutor threadPool;

    /** 池满 + 提交即执行: 用例只关心"认领了几条、有没有驱动", 不关心线程调度。 */
    @Before
    public void fullPoolRunningTasksInline() {
        when(parallelExecutePool.getThreadPoolExecutor()).thenReturn(threadPool);
        when(threadPool.getQueue()).thenReturn(new LinkedBlockingQueue<Runnable>());
        when(parallelExecutePool.getMaxPoolSize()).thenReturn(1);
        when(parallelExecutePool.getActiveCount()).thenReturn(1);   // 视为满 -> freeSlots=0 -> slots=1
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(parallelExecutePool).execute(any(Runnable.class));
    }

    private Dispatcher dispatcher(boolean enabled, long pollIntervalMs, int claimBatch) {
        return new Dispatcher(processMapper, engine, parallelExecutePool, enabled, pollIntervalMs, claimBatch);
    }

    private static int invokeDispatchOnce(Dispatcher d) throws Exception {
        Method m = Dispatcher.class.getDeclaredMethod("dispatchOnce");
        m.setAccessible(true);
        return (int) m.invoke(d);
    }

    @Test
    public void dispatchOnceClaimsAndDrivesEachPending() throws Exception {
        when(processMapper.findClaimableIds(anyInt())).thenReturn(pending("P1"));
        when(processMapper.claim(eq("P1"), isNull())).thenReturn(1);   // CAS 命中: 本 owner 取得驱动权

        int worked = invokeDispatchOnce(dispatcher(false, 10, 16));
        Assert.assertEquals("认领一条", 1, worked);
        verify(engine).driveClaimed("P1");                              // 已提交池驱动(池被 stub 成同步跑)
    }

    @Test
    public void dispatchOnceSkipsWhenCasPreemptedByOthers() throws Exception {
        when(processMapper.findClaimableIds(anyInt())).thenReturn(pending("P1"));
        // claim 不打桩 = mock 默认返回 0, 即"被别人抢先翻走"

        Assert.assertEquals(0, invokeDispatchOnce(dispatcher(false, 10, 16)));
        verify(engine, never()).driveClaimed(anyString());
    }

    @Test
    public void dispatchOnceStopsAtFreeSlotsLimit() throws Exception {
        // 两条待认领, 但 slots=1(池满) -> 第一条认领后 claimed>=slots break, 不再认领第二条
        when(processMapper.findClaimableIds(anyInt())).thenReturn(pending("P1", "P2"));
        when(processMapper.claim(anyString(), isNull())).thenReturn(1);

        int worked = invokeDispatchOnce(dispatcher(false, 10, 16));
        Assert.assertEquals("不超发, 只认领到 slots 上限", 1, worked);
        verify(processMapper, never()).claim(eq("P2"), isNull());
        verify(engine, times(1)).driveClaimed("P1");
    }

    @Test
    public void disabledDispatcherDoesNotStartLoop() throws Exception {
        dispatcher(false, 10, 16).afterSingletonsInstantiated();   // enabled=false -> 只记日志, 不起线程
        verify(processMapper, after(300).never()).findClaimableIds(anyInt());
    }

    @Test
    public void startLoopIsIdempotentAndStopClears() throws Exception {
        Dispatcher d = dispatcher(true, 5, 16);
        d.startLoop();
        Assert.assertTrue(isRunning(d));
        d.startLoop();                       // 已在跑 -> compareAndSet 失败, 直接返回
        Assert.assertTrue(isRunning(d));
        d.stop();
        Assert.assertFalse(isRunning(d));
        Thread.sleep(60);                    // 等循环线程退出, 别在用例结束后还继续打 mock
    }

    /** enabled=true 触发 afterSingletons -> 起守护线程跑循环; 空队列下 worked=0 -> sleep; 停后线程退出。 */
    @Test
    public void enabledAfterSingletonsStartsPollingLoop() throws Exception {
        Dispatcher d = dispatcher(true, 5, 16);
        d.afterSingletonsInstantiated();     // 走 enabled 分支 -> startLoop
        Assert.assertTrue(isRunning(d));
        Thread.sleep(60);                     // 让它空转几轮(无候选 -> worked=0 -> Thread.sleep)
        d.stop();
        Thread.sleep(60);
        Assert.assertFalse(isRunning(d));
    }

    /** dispatchOnce 抛异常 -> loop 捕获 -> sleepQuietly 继续跑下一轮(而不是线程一炸就静默停摆)。 */
    @Test
    public void loopSwallowsDispatchErrorsAndKeepsRunning() throws Exception {
        AtomicInteger rounds = new AtomicInteger();
        when(processMapper.findClaimableIds(anyInt())).thenAnswer(inv -> {
            rounds.incrementAndGet();
            throw new IllegalStateException("dispatch boom");
        });
        Dispatcher d = dispatcher(true, 5, 16);
        d.afterSingletonsInstantiated();
        awaitRounds(rounds, 2);              // 第二轮仍在跑 = 异常确实被吞下并续循环
        d.stop();
        Thread.sleep(60);
        verify(processMapper, atLeast(2)).findClaimableIds(anyInt());
    }

    private static void awaitRounds(AtomicInteger rounds, int expect) throws InterruptedException {
        for (int i = 0; i < 200 && rounds.get() < expect; i++) {
            Thread.sleep(10);
        }
    }

    private static boolean isRunning(Dispatcher d) throws Exception {
        Field f = Dispatcher.class.getDeclaredField("running");
        f.setAccessible(true);
        return ((AtomicBoolean) f.get(d)).get();
    }

    /** 可变候选列表(与 mapper 返回的 List<String> 同形, 不返回不可变视图)。 */
    private static List<String> pending(String... ids) {
        return new ArrayList<>(Arrays.asList(ids));
    }
}
