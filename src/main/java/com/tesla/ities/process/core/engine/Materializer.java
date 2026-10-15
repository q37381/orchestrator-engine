package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.mapper.NodeInstMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 物化调度器: **位置对齐**的走查物化 + 按表达式分发给节点类型档案({@link NodeKinds})。
 *
 * 设计(#类型归口): 建行/跑法都在一种类型一个的 {@link NodeKind} 里, 本类只做两件与类型无关的事——
 * 按下标把"行 ↔ 表达式"对齐好(缺行才请档案补建), 以及 EAGER 时按档案声明递归子层。
 * 新增节点类型 = 新增 Expr 类 + 一个 NodeKind 档案(注册进 NodeKinds), 本类不用按类型细化判断。
 *
 * 两档物化(materialization):
 *  - EAGER: 创建期一次性递归物化**整棵节点树**(ensureRoots 批量), 节点级分母稳定;
 *    递归与不递归由档案自己说({@link NodeKind#materializesChildrenEagerly()}: IF 分支恒惰性, 叶子无子节点行);
 *    lane/action 属执行细节, 一律驱动期建(叶子物化创建期不预建子行)。
 *  - LAZY(默认): **执行到哪个建哪个**——根行走查到第 i 个才建第 i 个(rootAt), 控制节点的子行/分片
 *    lane/action 同理(childAt / ActionKind.laneAt/materializeAction)。崩溃半物化时按需补齐。
 *
 * 实例定位不做路径反查: 驱动按定义顺序前向走查, 实例行与表达式列表按下标对齐(auto_id 顺序),
 * 已存在(执行过)的行复用, 缺行(未执行过/崩溃半物化)用手里的表达式补物化。
 *
 * 对齐同时把表达式作为**走查光标**补充进各行的 node.expr(), 叶子行一并挂上其 NodeDef(node.def())
 * 与节点级监听器链(node.listeners(), 全局链+本地声明合并, 全局在前), 均为 transient 不落库——控制节点没有
 * NodeDef, 行↔表达式的配对只在这里发生; 其后驱动/档案一律**行内自取定义**, 不再回查 ProcessDefinition、
 * 也不作为参数层层传递。
 *
 * 实例行只存运行期数据; listener 链等可由定义(版本钉死)推导的数据不落实例。
 * 例外: 各档案建行时把定义引用键与展示名烙进行上的 nodeDefId/name 展示快照(叶子见 ActionKind,
 * 命名容器见 Group/ParallelKind), 让进度视图直接读列就能展示, 不再依赖 transient 的 def 或 config 自述键。
 */
@Component
public class Materializer implements ChildCursor {

    private final NodeInstMapper nodeMapper;
    private final NodeKinds kinds;

    public Materializer(NodeInstMapper nodeMapper, NodeKinds kinds) {
        this.nodeMapper = nodeMapper;
        this.kinds = kinds;
    }

    /**
     * 物化一个表达式(按表达式类分发给档案), 返回本节点业务键 nodeInstId。
     * eager 且该档案声明"子层跟着预建"时, 在这里**统一**递归子表达式——
     * 于是 PARALLEL/GROUP 不必各抄一份同样的循环, IF/叶子也不必解释为什么不建(能力位自己说)。
     */
    public String materializeNode(DriveContext dc, DslParser.Expr expr, String parentId, boolean eager) {
        NodeKind kind = kinds.ofExpr(expr);
        String id = kind.materialize(dc, expr, parentId);
        if (eager && kind.materializesChildrenEagerly()) {
            for (DslParser.Expr child : expr.children()) {
                materializeNode(dc, child, id, true);
            }
        }
        return id;
    }

    // ===================== 批量物化(EAGER 创建期预建节点树) =====================

    /**
     * 顶层根行与 ordered 表达式按下标**批量**对齐: 缺行全部补物化(EAGER 创建期 / 崩溃愈合),
     * 对齐后把表达式 + 监听器链补充进各行。叶子行不带走 lane/action(一律驱动期建)。
     * @return 按声明顺序的根行(auto_id 升序, 与 ordered 对齐)。
     */
    public List<NodeInstEntity> ensureRoots(DriveContext dc, List<DslParser.Expr> ordered, boolean eager) {
        List<NodeInstEntity> rows = nodeMapper.findRoots(dc.procInstId());
        if (rows.size() < ordered.size()) {
            for (int i = rows.size(); i < ordered.size(); i++) {
                materializeNode(dc, ordered.get(i), null, eager);
            }
            rows = nodeMapper.findRoots(dc.procInstId());
        }
        align(dc, rows, ordered);
        return rows;
    }

    // ===================== 逐个物化(LAZY: 执行到第 index 个才建第 index 个) =====================

    /**
     * 顶层第 index 个根: 缺则物化(LAZY 执行到才建 / 崩溃愈合), 对齐并挂 expr+listeners 后返回该行。
     * EAGER 下节点行已在创建期建好, 本方法只做定位对齐。
     */
    @Override
    public NodeInstEntity rootAt(DriveContext dc, List<DslParser.Expr> ordered, int index, boolean eager) {
        List<NodeInstEntity> rows = nodeMapper.findRoots(dc.procInstId());
        while (rows.size() <= index) {
            // 建缺的下一条(按声明顺序); 新行 auto_id 最大, 追加即保持升序对齐, 避免整表重查
            String nodeId = materializeNode(dc, ordered.get(rows.size()), null, eager);
            rows.add(nodeMapper.findById(nodeId));
        }
        return attachCursor(dc, rows.get(index), ordered.get(index));
    }

    /**
     * 某控制节点下第 index 个子: 缺则物化(LAZY 执行到才建 / 崩溃愈合), 对齐并挂 expr+listeners 后返回。
     */
    @Override
    public NodeInstEntity childAt(DriveContext dc, NodeInstEntity node, List<DslParser.Expr> exprs, int index, boolean eager) {
        List<NodeInstEntity> rows = nodeMapper.findChildren(dc.procInstId(), node.getNodeInstId());
        while (rows.size() <= index) {
            // 建缺的下一条(按声明顺序); 新行 auto_id 最大, 追加即保持升序对齐, 避免整表重查
            String nodeId = materializeNode(dc, exprs.get(rows.size()), node.getNodeInstId(), eager);
            rows.add(nodeMapper.findById(nodeId));
        }
        return attachCursor(dc, rows.get(index), exprs.get(index));
    }

    // ===================== 对齐(行↔表达式, 挂走查光标 + 监听器链) =====================

    /** 行↔表达式按下标批量对齐。 */
    private void align(DriveContext dc, List<NodeInstEntity> rows, List<DslParser.Expr> ordered) {
        int n = Math.min(rows.size(), ordered.size());
        for (int i = 0; i < n; i++) {
            attachCursor(dc, rows.get(i), ordered.get(i));
        }
    }

    /**
     * 给单行挂走查光标(expr)、节点定义(def)与节点级监听器链(listeners), 均为 transient 不落库。
     * **有声明键的表达式**从自己的 {@code defRef()} 报出引用键(叶子 nodeRef / 组与命名并行的 name),
     * 于是这里不再按类型分派; 匿名控制容器(PARALLEL/IF)无声明 -> def 为 null。
     * 链 = 全局节点级链 + 节点自己声明的链合并(全局在前); 无 NodeDef 时只走全局链。
     */
    private NodeInstEntity attachCursor(DriveContext dc, NodeInstEntity row, DslParser.Expr expr) {
        row.setExpr(expr);
        String defRef = expr.defRef();
        NodeDef nd = defRef == null || dc.def() == null ? null : dc.def().node(defRef);
        row.setDef(nd);
        row.setListeners(dc.def() == null ? null
                : ProcessDefinition.mergeWithGlobals(dc.def().getGlobalNodeListeners(),
                        nd == null ? null : nd.getListeners()));
        return row;
    }

}
