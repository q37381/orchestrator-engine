package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.mapper.ProcessInstMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调度器(#2 改造: 流程级认领)。
 *
 * 原则: DB 是真值, 线程池是加速器。轮询 PENDING 流程 -> CAS 认领(PENDING->RUNNING) -> 交给并行池
 * 驱动整棵树(engine.start 内部递归)。认领数量受池剩余容量约束, DB 队列当缓冲。
 *
 * 取舍: 单 owner 驱动整流程(节点内并行/lane 仍用 owner 自己的池铺开)。
 *  - 优点: 简单, 无跨节点协调。
 *  - 代价: owner 进程崩溃, 流程僵在 RUNNING, 需人工干预翻回 PENDING(心跳/lease 留作后续扩展)。
 * node/lane 级认领(跨机并行)作为备用方案保留在 Mapper, 默认不启用。
 *
 * 由 ProcessEngineConfig.orchestratorDispatcher 工厂方法创建, 配置值构造时传入
 * (那条 {@code @Bean} 当前注释着: 需要主动调度的部署显式打开, 免得宿主一起容器就多出个后台线程)。
 */
public class Dispatcher implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final ProcessInstMapper processMapper;
    private final ExecutionEngine engine;
    private final ThreadPoolTaskExecutor parallelExecutePool;
    private final boolean enabled;
    private final long pollIntervalMs;
    private final int claimBatch;

    // 分布式路由能力: unit 空 = 全局模式(捞全部 pending, 不看调度表, 保留单机/存量行为);
    // 配了 unit 才走路由模式(只主动认领调度表里命中本能力的流程)。zone 可选: 不配则不按区域收窄。
    private final String unit;
    private final String zone;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public Dispatcher(ProcessInstMapper processMapper, ExecutionEngine engine,
                      ThreadPoolTaskExecutor parallelExecutePool,
                      boolean enabled, long pollIntervalMs, int claimBatch) {
        this(processMapper, engine, parallelExecutePool, enabled, pollIntervalMs, claimBatch,
                null, null);
    }

    public Dispatcher(ProcessInstMapper processMapper, ExecutionEngine engine,
                      ThreadPoolTaskExecutor parallelExecutePool,
                      boolean enabled, long pollIntervalMs, int claimBatch,
                      String unit, String zone) {
        this.processMapper = processMapper;
        this.engine = engine;
        this.parallelExecutePool = parallelExecutePool;
        this.enabled = enabled;
        this.pollIntervalMs = pollIntervalMs;
        this.claimBatch = claimBatch;
        this.unit = unit;
        this.zone = zone;
    }

    /** 是否路由模式: 配了 unit 就按能力过滤(zone 可选, 不配则不按区域收窄); 否则全局认领。 */
    private boolean routed() {
        return unit != null && !unit.isEmpty();
    }

    // ========== 生命周期 ==========

    @Override
    public void afterSingletonsInstantiated() {
        if (!enabled) {
            log.info("Dispatcher 已禁用 (dispatcherEnabled=false)");
            return;
        }
        startLoop();
    }

    public void startLoop() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::loop, "orch-dispatcher");
        t.setDaemon(true);
        t.start();
        log.info("Dispatcher 启动(流程级认领) pollIntervalMs={} claimBatch={}", pollIntervalMs, claimBatch);
    }

    public void stop() {
        running.set(false);
    }

    // ========== 调度循环 ==========

    private void loop() {
        while (running.get()) {
            try {
                int worked = dispatchOnce();
                if (worked == 0) {
                    Thread.sleep(pollIntervalMs);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Dispatcher 循环异常", e);
                sleepQuietly(pollIntervalMs);
            }
        }
    }

    /** 认领一批 PENDING 流程, 各提交到并行池驱动; 返回本轮认领数。 */
    private int dispatchOnce() {
        int slots = Math.max(freeSlots(), 1);
        int batch = Math.min(claimBatch, slots);
        // 路由模式: 只主动认领调度表里命中本 worker 能力的流程; 全局模式: 捞全部 pending(老行为)。
        java.util.List<String> candidates = routed()
                ? processMapper.findClaimableIdsByRoute(unit, zone, batch)
                : processMapper.findClaimableIds(batch);
        int claimed = 0;
        for (String id : candidates) {
            if (processMapper.claim(id, null) != 1) {
                continue;   // CAS 抢占; 失败=别的 owner 拿了。无人值守认领不刷 updator_id(驱动期再按创建人刷)
            }
            claimed++;
            parallelExecutePool.execute(() -> engine.driveClaimed(id));
            if (claimed >= slots) {
                break;   // 不超发, 留容量给流程内并行
            }
        }
        return claimed;
    }

    private int freeSlots() {
        int active = parallelExecutePool.getActiveCount();
        int queue = parallelExecutePool.getThreadPoolExecutor().getQueue().size();
        return Math.max(0, parallelExecutePool.getMaxPoolSize() - active - queue);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
