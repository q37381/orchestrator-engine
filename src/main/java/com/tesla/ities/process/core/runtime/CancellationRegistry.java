package com.tesla.ities.process.core.runtime;

import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * 取消信号(#6): **只读 DB, 不留任何内存状态**。
 *
 * 服务是多节点部署: 干预可能落在 A 节点, 驱动却跑在 B 节点, 两边共同的真值只有 process_inst.status。
 * 本机置位/节流缓存在这个前提下都是错的 —— 别的节点翻回的闸门(RESUME/RETRY 重新入队)本节点会一直
 * 看不见, 或反过来把已经恢复的流程继续判成取消并 drain 掉。所以状态一律当场查, 谁写谁可见。
 *
 * 口径: STOPPED/PAUSED/FAILED 视为已取消; 查不到行保守判取消(拿不到真值就停); 状态不识别判不取消。
 * 成本: 每个 action / 分片 lane / 子节点判一次, 都是主键 findById(引擎这些路径本来就读写同一行);
 * 业务 handler 长循环用的 ctx.isCancelled() 是引擎建 action 上下文时置的位, 不打库。
 */
@Component
public class CancellationRegistry {

    /** 视为"已取消"的流程状态。 */
    private static final Set<Status> CANCELLED = EnumSet.of(Status.STOPPED, Status.PAUSED, Status.FAILED);

    private final ProcessInstMapper processMapper;

    public CancellationRegistry(ProcessInstMapper processMapper) {
        this.processMapper = processMapper;
    }

    /** 流程当前是否处于取消闸门(当场读库, 跨节点即时一致)。 */
    public boolean isCancelled(String procInstId) {
        ProcessInstEntity p = processMapper.findById(procInstId);
        if (p == null) {
            return true;
        }
        return isCancelledStatus(Status.of(p.getStatus()));   // 状态不识别 -> null -> 不判取消
    }

    /** 状态口径本身(干预侧复用同一份, 免得"哪些状态算取消"两处各写一遍)。 */
    public static boolean isCancelledStatus(Status s) {
        return s != null && CANCELLED.contains(s);
    }
}
