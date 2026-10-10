package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.runtime.CancellationRegistry;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.mapper.ActionInstMapper;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import com.tesla.ities.process.util.Json;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工干预服务: 暂停/恢复/停止/重试/跳过/回退请求。
 *
 * 流程级模型(#2)下:
 *  - STOP/PAUSE 翻流程状态作闸门; owner 驱动循环每步 isCancelled 检查(读流程状态) -> 在跑的 drain, 未跑的不发。
 *  - RESUME 翻回 RUNNING; 但已退出 owner 线程的流程需重新被认领驱动(resetToPending 让 Dispatcher 重捞)。
 *  - RETRY/SKIP 失败 action: 翻该 action 状态; 因 owner 已退出, 把流程翻回 PENDING 让 Dispatcher 重新驱动,
 *    重入时已 SUCCESS/SKIPPED 的 action 跳过(见 runActionSequence 的 status 过滤), 仅重跑非终态项。
 *
 * 注: 单 owner 模型下"暂停后续跑"依赖 owner 线程仍在; 若 owner 已退出, 干预以"翻状态 + 重新入队"实现。
 *
 * 启动不走本服务: 原 startIfPending 已删——"是否仍 PENDING" 只是认领前的一个分支, ExecutionEngine.start
 * 自己判、把当下状态随返回值给出, 门面不必先查一次库。
 *
 * 审计(#1): operator 直接下传给各 mapper 写语句 -> 本次干预改过的行 updator_id = 操作人(无 ThreadLocal);
 * operator 同时作为业务字段留在 rollback_record。
 */
@Service
public class InterventionService {

    private final ProcessInstMapper processMapper;
    private final NodeInstMapper nodeMapper;
    private final ActionInstMapper actionMapper;

    public InterventionService(ProcessInstMapper processMapper, NodeInstMapper nodeMapper,
                               ActionInstMapper actionMapper) {
        this.processMapper = processMapper;
        this.nodeMapper = nodeMapper;
        this.actionMapper = actionMapper;
    }

    /** 暂停: RUNNING -> PAUSED。闸门就是这一行状态, 别的节点的 owner 查到 PAUSED 即 drain。 */
    public boolean pause(String procInstId, String operator) {
        return processMapper.casStatus(procInstId, Status.RUNNING.getStatus(), Status.PAUSED.getStatus(), operator) == 1;
    }

    /** 恢复: PAUSED -> PENDING(重新入队, 由 Dispatcher 再认领驱动; 已完成项重入跳过)。 */
    public boolean resume(String procInstId, String operator) {
        return processMapper.casStatus(procInstId, Status.PAUSED.getStatus(),
                Status.PENDING.getStatus(), operator) == 1;
    }

    /** 停止: RUNNING/PAUSED -> STOPPED。只翻流程状态; 在跑的 drain。 */
    public boolean stop(String procInstId, String operator) {
        int n = processMapper.casStatus(procInstId, Status.RUNNING.getStatus(), Status.STOPPED.getStatus(), operator);
        if (n == 0) {
            n = processMapper.casStatus(procInstId, Status.PAUSED.getStatus(), Status.STOPPED.getStatus(), operator);
        }
        return n == 1;
    }

    /**
     * 重试失败的 action(向前恢复): 该 action FAILED -> CREATED, 所属流程 FAILED/STOPPED -> PENDING 重新入队。
     * 重入时已完成的 action 跳过, 仅重跑 CREATED 项。依赖 handler 幂等。
     */
    public boolean retryAction(String actionInstId, String operator) {
        ActionInstEntity a = actionMapper.findById(actionInstId);
        if (a == null || !Status.FAILED.getStatus().equals(a.getStatus())) {
            return false;
        }
        actionMapper.casStatus(actionInstId, Status.FAILED.getStatus(), Status.CREATED.getStatus(), operator);
        requeueProcess(a.getNodeInstId(), operator);
        return true;
    }

    /** 人工跳过失败/卡住的 action: -> SKIPPED(原因写入 result.__skipReason), 流程重新入队继续推进。 */
    public boolean skipAction(String actionInstId, String operator) {
        ActionInstEntity a = actionMapper.findById(actionInstId);
        if (a == null) {
            return false;
        }
        Map<String, Object> res = new LinkedHashMap<>(Json.readMap(a.getResult()));
        res.put("__skipReason", "MANUAL_SKIP");
        actionMapper.updateResult(actionInstId, Status.SKIPPED.getStatus(), Json.write(res), operator);
        requeueProcess(a.getNodeInstId(), operator);
        return true;
    }

    /**
     * 回退请求(向后恢复): 把闸门翻成 STOPPED 落库, 实际逆序补偿由 RollbackService 完成。默认人工触发。
     * 闸门必须入库而不是本机置位 —— 驱动可能跑在别的节点, 只有 process_inst.status 两边都读得到。
     * 已是取消态(FAILED/PAUSED/STOPPED)就不动状态, 免得把"因何停住"的原态抹平。
     */
    public boolean requestRollback(String procInstId, String operator) {
        ProcessInstEntity p = processMapper.findById(procInstId);
        if (p == null) {
            return false;
        }
        if (!CancellationRegistry.isCancelledStatus(Status.of(p.getStatus()))) {
            processMapper.casStatus(procInstId, p.getStatus(), Status.STOPPED.getStatus(), operator);
        }
        return true;
    }

    // ---------- 内部 ----------

    /** 把 action 所属流程翻回 PENDING 重新入队(让 Dispatcher 再驱动); 沿途 RUNNING/FAILED 容器复位 CREATED。 */
    private void requeueProcess(String nodeId, String operator) {
        NodeInstEntity n = nodeMapper.findById(nodeId);
        if (n == null) {
            return;
        }
        // 容器链复位: FAILED/RUNNING -> CREATED, 让重入重新驱动
        resetNodeUpward(n, operator);
        ProcessInstEntity proc = processMapper.findById(n.getProcInstId());
        if (proc != null && (Status.FAILED.getStatus().equals(proc.getStatus())
                || Status.STOPPED.getStatus().equals(proc.getStatus())
                || Status.RUNNING.getStatus().equals(proc.getStatus())
                || Status.PAUSED.getStatus().equals(proc.getStatus()))) {
            processMapper.casStatus(proc.getProcInstId(), proc.getStatus(), Status.PENDING.getStatus(), operator);
        }
    }

    private void resetNodeUpward(NodeInstEntity n, String operator) {
        String cur = n.getNodeInstId();
        while (cur != null) {
            NodeInstEntity node = nodeMapper.findById(cur);
            if (node == null) {
                break;
            }
            if (Status.FAILED.getStatus().equals(node.getStatus()) || Status.RUNNING.getStatus().equals(node.getStatus())) {
                nodeMapper.setStatus(cur, Status.CREATED.getStatus(), operator);
            }
            cur = node.getParentId();
        }
    }
}
