package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.mapper.ProcessInstMapper;
import com.tt.ii.process.util.Json;
import org.springframework.stereotype.Component;

/**
 * 流程级脏桶刷写: 把 handler/监听器写脏的 vars 与 result 刷回 process_inst(只写脏的那几列、不动状态)。
 *
 * 原挂在 {@link NodeDriver} 上作 public 方法被两处调用(驱动中随刷 + {@link ExecutionEngine} 收尾),
 * 属"流程状态持久化"而非"节点编排", 抽出来单独一份。回退路径不调本组件——前置监听器挂的现场信息
 * 只留在内存(见 RollbackService 的既有口径), 这里不改变那个约束。
 */
@Component
public class ProcessStateFlusher {

    private final ProcessInstMapper processMapper;

    public ProcessStateFlusher(ProcessInstMapper processMapper) {
        this.processMapper = processMapper;
    }

    /**
     * 任一桶脏就刷一次(收尾复用: after 监听器写的 vars/result 也靠这里落住)。
     * 判脏 -> 取快照 -> 写库 -> 清脏必须在同一锁内: 并行 action/lane 各刷一次时,
     * 否则 A 清脏会把 B 刚写的更新一起吞掉(丢更新)。锁对象与 ProcessContext.putVar/putResult 相同。
     */
    public void flush(DriveContext dc, ProcessContext pctx) {
        synchronized (pctx) {
            boolean vars = pctx.isVarsDirty();
            boolean result = pctx.isResultDirty();
            if (!vars && !result) {
                return;
            }
            // 序列化也放在锁内: vars 是 ConcurrentHashMap + 嵌套 LinkedHashMap, 快照法挡不住嵌套写
            processMapper.updateState(dc.procInstId(), null, null, null,
                    result ? Json.write(pctx.resultSnapshot()) : null,
                    vars ? Json.write(pctx.vars()) : null, pctx.userId());
            pctx.clearVarsDirty();
            pctx.clearResultDirty();
        }
    }
}
