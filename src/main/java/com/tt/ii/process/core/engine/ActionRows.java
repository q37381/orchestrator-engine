package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.define.ActionDef;
import com.tt.ii.process.core.model.ActionSegment;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;

import java.util.List;

/**
 * action 行定位(角色接口): "这一层要有一行"的三类事实——find-or-create 单个 action 行、find-or-create
 * 某分片的 lane 行、取本容器内已存在的 action 行(与 ActionDef 按 auto_id 下标对齐)。
 *
 * 口径本体在 {@link ActionKind}(叶子的建行知识归档案所有), 由它实现本接口; 走查层类只面向接口取行,
 * 既不经 {@link NodeDriver} 转发(那是纯 Middle Man), 也不在构造期点名具体档案(DIP)。
 */
interface ActionRows {

    /** 定位/补物化第 i 个 action 行(执行到才建; 重入命中已存在行)。segment 进落库列与自然键。 */
    ActionInstEntity ensureActionRow(DriveContext dc, NodeInstEntity node, String laneId, ActionDef ad,
                                     String shardKey, ActionSegment segment);

    /** 定位/补物化某分片的 lane 行(find-or-create, 按 node+shardKey 幂等)。 */
    LaneEntity ensureLaneRow(DriveContext dc, NodeInstEntity node, String shardKey);

    /** 本容器(lane 或非分片 node)内**某段**已存在的 action 行(auto_id 升序 = 段内声明顺序)。 */
    List<ActionInstEntity> findInContainer(String nodeId, String laneId, ActionSegment segment);

    /** 本容器(lane 或非分片 node)内是否已有任何 action 行(收尾轮判定 lane 是否开过工, 不建行)。 */
    boolean hasAnyRow(String nodeId, String laneId);

    /**
     * 段行重臂(v1.1): 把本段**终态**行 CAS 复位为 CREATED, 让它们在本趟回路里重跑(走查的"非终态行重跑"路径)。
     * allTerminal=true 复位全部终态行(节点 repeatSegments 口径); false 只复位 success 行(成对重臂口径,
     * skipped 是 guard/人工判定的既成事实不复位)。只动 status、不动 result; 非终态行不动(本轮本就会重跑)。
     *
     * @return 实际复位条数(幂等: 已为非终态的行零改动)
     */
    int reArmSegment(String nodeId, String laneId, ActionSegment segment, boolean allTerminal, String actor);

    /**
     * 行集重臂(v1.2 action 级段): 把给定行集中的**终态**行 CAS 复位为 CREATED, 口径同 {@link #reArmSegment}。
     * 级段重臂按"定义成员过滤后的行集"复位——**不可整段复位**(同节点多个 action 级段并存时整段复位会误伤其他段)。
     *
     * @return 实际复位条数
     */
    int reArmRows(List<ActionInstEntity> rows, boolean allTerminal, String actor);
}
