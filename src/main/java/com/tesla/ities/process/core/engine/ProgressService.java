package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.model.ActionSegment;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.mapper.ActionInstMapper;
import com.tesla.ities.process.mapper.LaneMapper;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.util.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进度查询(校验流程轮询用)。
 * 校验流程建议 materialization: EAGER -> 所有**节点行**创建即入库(CREATED), 节点级分母稳定;
 * lane/action 一律驱动期建, 故本视图的 total/done(action 行口径)随驱动增长, 为"已物化部分"的口径。
 * skipReason/error 从 action result 的保留键 __skipReason/__error 读(#14, 无独立列)。
 *
 * 两个视图:
 *  - 扁平口径(total/done/percent/counts/items): 全流程所有已物化 action 行, items 带 nodeInstId/nodeName 与
 *    action/actionName 便于展示归组;
 *  - **groups**: 每个**命名容器**一项(GROUP 聚合节点 + 带命名后缀的并行 {@code PARALLEL:标签(a,b)}) ——
 *    一个校验项一行(标题/状态/自己的 total·done·percent + 明细), 判据是行的 **node_def_id 列**非空(命名容器才落)
 *    而非 type 逐个硬编码; 标题取 name(展示名) > node_def_id(定义 id) > nodeInstId(直接读列, 不再解 config)。
 *
 * 执行段(setup|main|setdown)口径: **统计(total/done/percent/counts)只算主段行**("一个节点 = 一个校验项"
 * 的心智与分母不变); 段行仍进 items 明细并带 segment 字段, 视图可自行按段分组展示。存量行无段值按 main 计。
 */
@Service
public class ProgressService {

    private final NodeInstMapper nodeMapper;
    private final LaneMapper laneMapper;
    private final ActionInstMapper actionMapper;

    public ProgressService(NodeInstMapper nodeMapper, LaneMapper laneMapper, ActionInstMapper actionMapper) {
        this.nodeMapper = nodeMapper;
        this.laneMapper = laneMapper;
        this.actionMapper = actionMapper;
    }

    /** 汇总某流程下所有已物化 action 的状态计数 + 明细(+ 聚合节点分组视图)。 */
    public Map<String, Object> progress(String procInstId) {
        Map<String, Integer> counts = newCountMap();
        List<Map<String, Object>> items = new ArrayList<>();

        // flow 扁平化: 顶层即多个 root 节点, 逐个整子树收集
        List<NodeInstEntity> nodes = new ArrayList<>();
        for (NodeInstEntity root : nodeMapper.findRoots(procInstId)) {
            collect(root, nodes, procInstId);
        }

        int total = 0;   // main 段行数 = 进度分母; 段行只在明细里带 segment 可见(不算进分母)
        for (NodeInstEntity n : nodes) {
            total += collectActions(n, counts, items);
        }
        int done = counts.getOrDefault(Status.SUCCESS.getStatus(), 0) + counts.getOrDefault(Status.SKIPPED.getStatus(), 0);

        // 命名容器视图: 一个校验项一行(标题 + 自己的小计 + 明细)
        List<Map<String, Object>> groups = new ArrayList<>();
        for (NodeInstEntity n : nodes) {
            if (isNamedContainer(n)) {
                groups.add(groupView(n, procInstId));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("procInstId", procInstId);
        result.put("total", total);
        result.put("done", done);
        result.put("percent", total == 0 ? 100 : done * 100 / total);
        result.put("counts", counts);
        result.put("groups", groups);
        result.put("items", items);
        return result;
    }

    /**
     * 命名容器判据: GROUP 行恒有定义快照(匿名手写的 {@code GROUP:名字(...)} 也落 node_def_id, 那是既有口径);
     * 并行行只有带命名后缀的 {@code PARALLEL:标签(a,b)} 才落 node_def_id(匿名并行留 NULL), 于是"一个容器一行"
     * 由 node_def_id 是否非空统一承载, 新增容器形态不必再回来加一条 type 分支, 也不再解 config 里的自述保留键。
     */
    private boolean isNamedContainer(NodeInstEntity n) {
        if (NodeType.GROUP.getStatus().equals(n.getType())) {
            return true;
        }
        return NodeType.PARALLEL.getStatus().equals(n.getType()) && n.getNodeDefId() != null;
    }

    /** 一个命名容器的进度小计: 状态取容器行自己那一条, 明细取其整棵子树下的 action 行(统计同扁平口径: 只算 main 段)。 */
    private Map<String, Object> groupView(NodeInstEntity group, String procInstId) {
        List<NodeInstEntity> sub = new ArrayList<>();
        collect(group, sub, procInstId);      // 含自身
        Map<String, Integer> counts = newCountMap();
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        for (NodeInstEntity n : sub) {
            total += collectActions(n, counts, items);
        }
        int done = counts.getOrDefault(Status.SUCCESS.getStatus(), 0) + counts.getOrDefault(Status.SKIPPED.getStatus(), 0);

        String name = group.getName();
        String nodeDefId = group.getNodeDefId();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("nodeInstId", group.getNodeInstId());
        view.put("group", nodeDefId != null ? nodeDefId : group.getNodeInstId());
        view.put("name", name != null ? name : (nodeDefId != null ? nodeDefId : group.getNodeInstId()));
        view.put("status", group.getStatus());
        view.put("total", total);
        view.put("done", done);
        view.put("percent", total == 0 ? 100 : done * 100 / total);
        view.put("counts", counts);
        view.put("items", items);
        return view;
    }

    /** 某节点容器内(非分片 action + 各分片 lane 下的 action)的行累加进计数与明细; 返回其中 **main 段**行数(分母口径)。 */
    private int collectActions(NodeInstEntity n, Map<String, Integer> counts, List<Map<String, Object>> items) {
        int mainCount = 0;
        for (ActionInstEntity a : actionMapper.findInContainer(n.getNodeInstId(), null)) {
            mainCount += accumulate(n, a, counts, items) ? 1 : 0;
        }
        for (LaneEntity l : laneMapper.findByNode(n.getNodeInstId())) {
            for (ActionInstEntity a : actionMapper.findInContainer(n.getNodeInstId(), l.getLaneId())) {
                mainCount += accumulate(n, a, counts, items) ? 1 : 0;
            }
        }
        return mainCount;
    }

    private Map<String, Integer> newCountMap() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            counts.put(s.getStatus(), 0);
        }
        return counts;
    }

    /** 一行 action 进明细(items 全量, 带 segment); 返回是否 main 段行——**只有 main 段参与统计**(counts/分母)。 */
    private boolean accumulate(NodeInstEntity n, ActionInstEntity a,
                               Map<String, Integer> counts, List<Map<String, Object>> items) {
        String segment = a.getSegment() != null ? a.getSegment() : ActionSegment.MAIN.getStatus();   // 存量行无段值按 main 计
        boolean main = ActionSegment.MAIN.getStatus().equals(segment);
        if (main) {
            counts.merge(a.getStatus(), 1, Integer::sum);
        }
        Map<String, Object> res = Json.readMap(a.getResult());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actionInstId", a.getActionInstId());
        m.put("action", a.getActionDefId());
        m.put("actionName", a.getName() != null ? a.getName() : a.getActionDefId());   // 展示名(未声明回落 def id)
        m.put("segment", segment);          // 执行段: setup|main|setdown(存量行按 main 计)
        m.put("status", a.getStatus());
        m.put("shardKey", a.getShardKey());
        m.put("nodeInstId", n.getNodeInstId());     // 归属: 便于调用方自行按节点/校验项归组
        m.put("nodeName", n.getName() != null ? n.getName()
                : (n.getNodeDefId() != null ? n.getNodeDefId() : n.getNodeInstId())); // 节点展示名(name > nodeDefId > 业务键)
        m.put("skipReason", res.get("__skipReason"));
        m.put("error", res.get("__error"));
        items.add(m);
        return main;
    }

    private void collect(NodeInstEntity n, List<NodeInstEntity> acc, String procInstId) {
        if (n == null) {
            return;
        }
        acc.add(n);
        for (NodeInstEntity c : nodeMapper.findChildren(procInstId, n.getNodeInstId())) {
            collect(c, acc, procInstId);
        }
    }
}
