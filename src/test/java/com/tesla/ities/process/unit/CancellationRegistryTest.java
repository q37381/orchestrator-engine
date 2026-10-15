package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.runtime.CancellationRegistry;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取消信号单测: 闸门只认 DB(process_inst.status), 本机不留任何状态 ——
 * 多节点部署下别的节点翻闸门(停/暂停/恢复/重新入队)必须当场可见, 既不缓存也不钉死。
 *
 * 替身一律 {@code @Mock} 字段 + {@code @InjectMocks} 装配被测对象; "库里那一行"用 {@link #row} 现场改,
 * 相当于别的节点刚写完。
 */
@RunWith(MockitoJUnitRunner.class)
public class CancellationRegistryTest {

    /** 当前 process_inst 那一行(置 null = 查不到行)。 */
    private final AtomicReference<ProcessInstEntity> row = new AtomicReference<>();

    @Mock
    private ProcessInstMapper processMapper;

    @InjectMocks
    private CancellationRegistry registry;

    /** 闸门只读 findById: 按业务键命中才回行, 否则 null(与主键查询同语义)。 */
    @Before
    public void stubFindById() {
        when(processMapper.findById(anyString())).thenAnswer(inv -> {
            ProcessInstEntity r = row.get();
            return r != null && r.getProcInstId().equals(inv.<String>getArgument(0)) ? r : null;
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
            row.set(proc("P", bad.getStatus()));
            Assert.assertTrue(bad + " 视为已取消", registry.isCancelled("P"));
        }
        // 缺失行也当已取消(拿不到真值, 保守停)
        row.set(null);
        Assert.assertTrue(registry.isCancelled("X"));
        // 不可识别状态 -> of 返回 null -> 非取消
        row.set(proc("P", "weird-status"));
        Assert.assertFalse(registry.isCancelled("P"));
    }

    @Test
    public void peerNodeGateFlipIsVisibleAtOnce() {
        row.set(proc("P", "running"));
        Assert.assertFalse(registry.isCancelled("P"));                   // running -> 未取消

        row.set(proc("P", Status.STOPPED.getStatus()));                  // 另一个节点 stop
        Assert.assertTrue("别的节点翻的闸门当场可见", registry.isCancelled("P"));

        row.set(proc("P", Status.PENDING.getStatus()));                 // 又一个节点 resume/重新入队
        Assert.assertFalse("本机不保留旧信号: 已恢复的流程不能再被判成取消", registry.isCancelled("P"));
    }

    @Test
    public void everyCheckReadsDb() {
        row.set(proc("P", "running"));
        registry.isCancelled("P");
        registry.isCancelled("P");
        registry.isCancelled("P");
        verify(processMapper, times(3)).findById("P");                   // 无缓存: 判几次读几次
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
