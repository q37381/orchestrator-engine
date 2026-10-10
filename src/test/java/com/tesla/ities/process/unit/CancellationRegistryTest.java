package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.runtime.CancellationRegistry;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 取消信号单测: 闸门只认 DB(process_inst.status), 本机不留任何状态 ——
 * 多节点部署下别的节点翻闸门(停/暂停/恢复/重新入队)必须当场可见, 既不缓存也不钉死。
 */
public class CancellationRegistryTest {

    private static ProcessInstMapper mapperReturning(AtomicReference<ProcessInstEntity> row) {
        return mapperReturning(row, null);
    }

    /** 内存替身: 只实现 findById, 顺带数读了几次(证明确实每判一次打一次库)。 */
    private static ProcessInstMapper mapperReturning(AtomicReference<ProcessInstEntity> row, AtomicInteger reads) {
        return (ProcessInstMapper) Proxy.newProxyInstance(
                ProcessInstMapper.class.getClassLoader(), new Class<?>[]{ProcessInstMapper.class},
                (p, m, args) -> {
                    if ("findById".equals(m.getName())) {
                        if (reads != null) {
                            reads.incrementAndGet();
                        }
                        ProcessInstEntity r = row.get();
                        return r != null && r.getProcInstId().equals(args[0]) ? r : null;
                    }
                    Class<?> rt = m.getReturnType();
                    return rt == int.class ? 0 : rt == boolean.class ? false : rt == long.class ? 0L : null;
                });
    }

    private static ProcessInstEntity proc(String id, String status) {
        ProcessInstEntity e = new ProcessInstEntity();
        e.setProcInstId(id);
        e.setStatus(status);
        return e;
    }

    @Test
    public void dbCancelStatesAreRecognised() {
        for (Status bad : new Status[]{Status.STOPPED, Status.PAUSED, Status.FAILED}) {
            AtomicReference<ProcessInstEntity> row = new AtomicReference<>(proc("P", bad.getStatus()));
            Assert.assertTrue(bad + " 视为已取消",
                    new CancellationRegistry(mapperReturning(row)).isCancelled("P"));
        }
        // 缺失行也当已取消(拿不到真值, 保守停)
        Assert.assertTrue(new CancellationRegistry(mapperReturning(new AtomicReference<>(null))).isCancelled("X"));
        // 不可识别状态 -> of 返回 null -> 非取消
        AtomicReference<ProcessInstEntity> weird = new AtomicReference<>(proc("P", "weird-status"));
        Assert.assertFalse(new CancellationRegistry(mapperReturning(weird)).isCancelled("P"));
    }

    @Test
    public void peerNodeGateFlipIsVisibleAtOnce() {
        AtomicReference<ProcessInstEntity> row = new AtomicReference<>(proc("P", "running"));
        CancellationRegistry reg = new CancellationRegistry(mapperReturning(row));
        Assert.assertFalse(reg.isCancelled("P"));              // running -> 未取消

        row.set(proc("P", Status.STOPPED.getStatus()));        // 另一个节点 stop
        Assert.assertTrue("别的节点翻的闸门当场可见", reg.isCancelled("P"));

        row.set(proc("P", Status.PENDING.getStatus()));       // 又一个节点 resume/重新入队
        Assert.assertFalse("本机不保留旧信号: 已恢复的流程不能再被判成取消", reg.isCancelled("P"));
    }

    @Test
    public void everyCheckReadsDb() {
        AtomicReference<ProcessInstEntity> row = new AtomicReference<>(proc("P", "running"));
        AtomicInteger reads = new AtomicInteger();
        CancellationRegistry reg = new CancellationRegistry(mapperReturning(row, reads));
        reg.isCancelled("P");
        reg.isCancelled("P");
        reg.isCancelled("P");
        Assert.assertEquals("无缓存: 判几次读几次", 3, reads.get());
    }

    @Test
    public void gateStatusVocabularyIsShared() {
        // 静态口径与 isCancelled 同源(干预侧 requestRollback 用的就是它)
        for (Status bad : new Status[]{Status.STOPPED, Status.PAUSED, Status.FAILED}) {
            Assert.assertTrue(bad + " 是取消闸门", CancellationRegistry.isCancelledStatus(bad));
        }
        for (Status ok : new Status[]{Status.CREATED, Status.PENDING, Status.RUNNING,
                Status.SUCCESS, Status.SKIPPED, Status.SUSPENDED}) {
            Assert.assertFalse(ok + " 不是取消闸门", CancellationRegistry.isCancelledStatus(ok));
        }
        Assert.assertFalse(CancellationRegistry.isCancelledStatus(null));
    }
}
