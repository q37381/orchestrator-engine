package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.Context;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.NodeInstEntity;

/**
 * 节点递归驱动(角色接口): 档案与层类需要的只是"把这个子节点驱动到终态", 而不是整个 {@link NodeDriver}
 * (幂等跳过、markRunning、监听器链、收尾落库都在实现方内部)。由 NodeDriver 实现。
 */
interface NodeRecursion {

    /** 驱动一个节点至终态, 显式给出直属上层容器(上下文父链与实例树同形)。 */
    Status driveNode(DriveContext dc, NodeInstEntity node, Context container);

    /**
     * **收尾驱动**(settle 收尾切片专用): 清理节点按其全段正常执行, 不查取消闸门——
     * 收尾切片(流程级 setdown 节点 / 组级 setdown 子节点)的主 action 就是清理动作本身,
     * 若被停止闸门拦下,"到 setdown"就成了空转。子树(容器子节点)继承同一口径。
     */
    Status driveNodeCleanup(DriveContext dc, NodeInstEntity node, Context container);
}
