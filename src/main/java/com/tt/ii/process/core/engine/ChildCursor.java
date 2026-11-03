package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.domain.NodeInstEntity;

import java.util.List;

/**
 * 行↔表达式对齐光标(角色接口): 走查"定位第 i 项"(缺行按需补物化、已存在则复用)所需的最小视图,
 * 由 {@link Materializer} 实现。档案与层类经本接口取子行, 不再认识物化调度器的其余部分。
 */
interface ChildCursor {

    /** 顶层第 index 个根: 缺则物化, 对齐并挂走查光标后返回。 */
    NodeInstEntity rootAt(DriveContext dc, List<DslParser.Expr> ordered, int index, boolean eager);

    /**
     * 顶层第 index 个根, **finally 段专用**(setdown 段根): 缺则物化, 但把 index 之前未物化的
     * 声明位(多半是失败点之后的 flow 根)一并补成 STOPPED/DRAIN, 而不是留成 CREATED 孤儿。
     * 为什么必须补位: 行↔表达式按 auto_id 下标对齐, 跳过空洞会把 setdown 行挤到 flow 根的位置上;
     * 补位行立即可被 retry 复位(FAILED/STOPPED 在复位集内), 且不会作为“从未执行”的 CREATED 污染进度。
     */
    default NodeInstEntity rootAtForFinally(DriveContext dc, List<DslParser.Expr> ordered, int index, boolean eager) {
        return rootAt(dc, ordered, index, eager);
    }

    /**
     * 顶层第 index 个根的**只取已存在行**版(停止收尾轮用): 缺行返回 null, **绝不补物化**——
     * 从没开始过的节点不该在停止时被拉起来跑 setdown。已存在则同样对齐并挂走查光标。
     */
    NodeInstEntity rootAtExisting(DriveContext dc, List<DslParser.Expr> ordered, int index, boolean eager);

    /** 某控制节点下第 index 个子: 缺则物化, 对齐并挂走查光标后返回。 */
    NodeInstEntity childAt(DriveContext dc, NodeInstEntity node, List<DslParser.Expr> exprs, int index, boolean eager);

    /** 某控制节点下第 index 个子的**只取已存在行**版(停止收尾轮用): 缺行返回 null, 绝不补物化。 */
    NodeInstEntity childAtExisting(DriveContext dc, NodeInstEntity node, List<DslParser.Expr> exprs, int index, boolean eager);
}
