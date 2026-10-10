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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人工干预服务: 暂停/恢复/停止/重试/跳过/回退请求。
 *
 * 流程级模型(#2)下:
 *  - STOP/PAUSE 翻流程状态作闸门; owner 驱动循环每步 isCancelled 检查(读流程状态) -> 在跑的 drain, 未跑的不发。
 *  - RESUME 翻回 RUNNING; 但已退出 owner 线程的流程需重新被认领驱动(resetToPending 让 Dispatcher 重捞)。
 *  - RETRY/SKIP 失败 action: 翻该 action 状态; 因 owner 已退出, 把流程翻回 PENDING 让 Dispatcher 重新驱动,
 *    重入时已 SUCCESS/SKIPPED 的 action 跳过(见 runActionSequence 的 status 过滤), 仅重跑非终态项。
 *  - RETRY 的默认入口以**流程实例**为发起单位({@link #retryProcess}): 一次扫完整棵实例树, 不必先查 actionInstId
 *    再逐条点; {@link #retryAction} 留作定点重跑那一条的细粒度入口。两者共用同一套复位口径(RESETTABLE)。
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

    private static final Logger log = LoggerFactory.getLogger(InterventionService.class);

    /**
     * 重试复位集(action / node 行): **"本轮没跑成"** 的那些。
     *  - FAILED / STOPPED: 坏终态 —— 前者是跑挂了, 后者多半是 drain 掉的、压根没执行过;
     *  - RUNNING / PENDING: 卡在途中(owner 崩溃留下的行、备用认领路径翻走的行), 不复位就永远没人再跑它。
     * SUCCESS 是既成事实, SKIPPED 还包含人工跳过与 guard 判假(重跑它等于否定那次干预), 所以都不复位。
     */
    private static final Set<Status> RESETTABLE =
            EnumSet.of(Status.FAILED, Status.STOPPED, Status.RUNNING, Status.PENDING);

    /** 闸门允许翻回 PENDING 的流程状态: SUCCESS/SKIPPED 的流程不许重跑, CREATED 本就还没被认领过。 */
    private static final Set<Status> REQUEUEABLE =
            EnumSet.of(Status.FAILED, Status.STOPPED, Status.RUNNING, Status.PAUSED);

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
     * 重试失败的 action(向前恢复): 该 action 复位 CREATED, 所属流程翻 PENDING 重新入队。
     * 重入时已完成的 action 跳过, 仅重跑 CREATED 项。依赖 handler 幂等。
     *
     * 定点重跑的细粒度入口; 日常"修好现场后重试这一整轮"请用 {@link #retryProcess(String, String)}
     * (以流程实例为发起单位, 不必先拿到 actionInstId)。
     */
    public boolean retryAction(String actionInstId, String operator) {
        ActionInstEntity a = actionMapper.findById(actionInstId);
        if (a == null || !RESETTABLE.contains(Status.of(a.getStatus()))) {
            return false;
        }
        resetAction(a, operator);
        requeueProcess(a.getNodeInstId(), operator);
        return true;
    }

    /**
     * 重试整个流程(向前恢复, **以流程实例 id 发起**): 扫完整棵实例树, 把所有"本轮没跑成"的行
     * (见 {@link #RESETTABLE})复位成 CREATED —— action 行逐条复位, 容器/叶子节点行同样复位(状态是聚合出来的,
     * 里面有复位项就该重新驱动), 最后把闸门翻回 PENDING 交 Dispatcher 再认领。
     *
     * 复位之后走的就是既有重入路径, 没有任何新的执行语义: 走查仍从第一个根开始, 终态节点被 driveNode 幂等跳过、
     * 已完成的 action 被序列里的 CREATED 过滤跳过, LAZY 缺行照旧补物化 —— 于是效果就是**从断点续跑到结尾**,
     * 已成功的 handler 不重跑、显式放置的交接数据仍在。与 {@link #retryAction} 只差发起粒度与覆盖面,
     * 同样依赖 handler 幂等。
     *
     * 既没复位到行、闸门也翻不动(已 SUCCESS/SKIPPED 的流程)就返回 false: 重试不该把跑成功的流程再跑一遍。
     * 反过来"定义装载失败/流程 before 否决"那两条把流程做成 FAILED 却一行都没跑的路, 实例行全是 CREATED,
     * 这里就是零复位 + 翻闸门 -> 重新入队从头跑一遍, 语义上仍然是"没跑过的才跑"。
     *
     * 这一串写语句之间**刻意不开事务**(引擎整体没有事务基础设施: 无 tx manager, 一处 @Transactional 都没用):
     * 资格先判(翻不动闸门就一行不碰) + 闸门最后翻, 已经把危险方向挡住了 —— 中途异常时流程还不是 PENDING,
     * Dispatcher 捞不到, 不存在"带着一半复位就开跑"; 残留的半截状态只是"没排进队", 再点一次重试即补完
     * (已复位的行是 CREATED, 天然被跳过)。返回值 = 是否真排进了队列, 复位条数只进日志。
     *
     * 流程仍在 RUNNING 且 owner 还活着时有双驱动窗口(见 {@link #requeue}), 稳妥做法是先 {@link #stop} 再重试。
     */
    public boolean retryProcess(String procInstId, String operator) {
        ProcessInstEntity p = processMapper.findById(procInstId);
        if (p == null) {
            log.warn("重试目标流程不存在 proc={}", procInstId);
            return false;
        }
        // 资格前置(不写库先判): 闸门翻不动就一行都不碰。否则会把已完成流程里历史残留的 stopped/failed 行
        // 翻成 CREATED 却又没人来跑它们(这些行是上一轮 drain 留下的: 重入时被 CREATED 过滤跳过, 流程照样收 SUCCESS)。
        if (!REQUEUEABLE.contains(Status.of(p.getStatus()))) {
            log.info("流程状态不可重试, 未改动任何行 proc={} status={}", procInstId, p.getStatus());
            return false;
        }
        int actions = 0;
        int nodes = 0;
        for (NodeInstEntity n : tree(procInstId)) {
            // find(nodeInstId, null) = 该节点全部 action(含各 lane), 不按 lane 收窄
            for (ActionInstEntity a : actionMapper.find(n.getNodeInstId(), null)) {
                if (RESETTABLE.contains(Status.of(a.getStatus()))) {
                    actions += resetAction(a, operator);
                }
            }
            nodes += resetNode(n, operator);
        }
        // 闸门排在最后一步翻: 中途任何一行写失败, 流程都还不是 PENDING, Dispatcher 捞不到 -> 不存在"带一半复位开跑"。
        // 剩下的半截状态只是"没排进队", 本方法可重复调用: 已复位的行是 CREATED 会被跳过, 没复的接着复。
        // 一行都没复位也照样翻闸门再驱动一次: 定义装载失败/流程 before 否决那两条路把流程做成 FAILED 时
        // 实例行还都是 CREATED(什么都没跑), 这时"重试"就是重新入队从头跑, 不会重算任何已完成项。
        boolean requeued = requeue(procInstId, operator);
        log.info("流程重试 proc={} 复位 action={} 节点={} 重新入队={} operator={}",
                procInstId, actions, nodes, requeued, operator);
        return requeued;
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
     * (定义配了 autoRollback 时不必再点这一步: 驱动收尾读到 STOPPED/FAILED 就会自己补。)
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

    /**
     * 复位一行 action -> CREATED(按读到的前态 CAS, 返影响行数 = 是否真复到了)。
     * **不动 result**: 业务结果要留着给重跑与排障参考,
     * 上一轮的 __error/__skipReason 痕迹由驱动侧在本轮收尾时摘(NodeDriver.runAction), 干预这里不越俎代庖。
     */
    private int resetAction(ActionInstEntity a, String operator) {
        return actionMapper.casStatus(a.getActionInstId(), a.getStatus(), Status.CREATED.getStatus(), operator);
    }

    /**
     * 复位一行节点(容器与叶子同口径) -> CREATED。同样走 CAS 而非裸置状态:
     * 流程仍 RUNNING(老 owner 还活着)时, 它刚写的终态不该被干预一脚踩掉, 抢不过就让它留着。
     */
    private int resetNode(NodeInstEntity n, String operator) {
        return RESETTABLE.contains(Status.of(n.getStatus()))
                ? nodeMapper.casStatus(n.getNodeInstId(), n.getStatus(), Status.CREATED.getStatus(), operator)
                : 0;
    }

    /** 把 action 所属流程翻回 PENDING 重新入队(让 Dispatcher 再驱动); 沿途坏终态/卡途中的容器复位 CREATED。 */
    private void requeueProcess(String nodeId, String operator) {
        NodeInstEntity n = nodeMapper.findById(nodeId);
        if (n == null) {
            return;
        }
        // 容器链复位: 本轮没跑成的 -> CREATED, 让重入重新驱动
        resetNodeUpward(n, operator);
        requeue(n.getProcInstId(), operator);
    }

    /** 闸门翻回 PENDING(重新可被认领); 不在 {@link #REQUEUEABLE} 里的状态一律不动。返是否真的翻了。 */
    private boolean requeue(String procInstId, String operator) {
        ProcessInstEntity proc = processMapper.findById(procInstId);
        if (proc == null || !REQUEUEABLE.contains(Status.of(proc.getStatus()))) {
            return false;
        }
        return processMapper.casStatus(procInstId, proc.getStatus(), Status.PENDING.getStatus(), operator) == 1;
    }

    private void resetNodeUpward(NodeInstEntity n, String operator) {
        String cur = n.getNodeInstId();
        while (cur != null) {
            NodeInstEntity node = nodeMapper.findById(cur);
            if (node == null) {
                break;
            }
            resetNode(node, operator);
            cur = node.getParentId();
        }
    }

    /** 整棵实例树(根行按声明顺序、深度优先含自身)——与 ProgressService/RollbackService 同一走查口径。 */
    private List<NodeInstEntity> tree(String procInstId) {
        List<NodeInstEntity> all = new ArrayList<>();
        // flow 扁平化: 顶层即多个 root 节点
        for (NodeInstEntity root : nodeMapper.findRoots(procInstId)) {
            collect(root, all, procInstId);
        }
        return all;
    }

    private void collect(NodeInstEntity n, List<NodeInstEntity> acc, String procInstId) {
        acc.add(n);
        for (NodeInstEntity c : nodeMapper.findChildren(procInstId, n.getNodeInstId())) {
            collect(c, acc, procInstId);
        }
    }
}
