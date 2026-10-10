package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.runtime.VariableResolver;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.mapper.NodeInstMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物化调度器: 表达式 -> 物化策略(ExprMaterializer)的注册与分发, 外加**位置对齐**的走查物化。
 *
 * 设计(#物化策略化): 每种 DSL 表达式一个物化策略(Leaf/Parallel/If), 本类不再按类型细化判断;
 * 新增节点类型 = 新增 Expr 类 + 一个 ExprMaterializer + 一个 NodeStrategy。
 *
 * 两档物化(materialization):
 *  - EAGER: 创建期一次性递归物化**整棵节点树**(ensureRoots 批量), 节点级分母稳定;
 *    lane/action 属执行细节, 一律驱动期建(叶子物化创建期不预建子行)。
 *  - LAZY(默认): **执行到哪个建哪个**——根行走查到第 i 个才建第 i 个(rootAt), 控制节点的子行/分片
 *    lane/action 同理(childAt / LeafExprMaterializer.laneAt/materializeAction)。崩溃半物化时按需补齐。
 *
 * 实例定位不做路径反查: 驱动按定义顺序前向走查, 实例行与表达式列表按下标对齐(auto_id 顺序),
 * 已存在(执行过)的行复用, 缺行(未执行过/崩溃半物化)用手里的表达式补物化。
 *
 * 对齐同时把表达式作为**走查光标**补充进各行的 node.expr(), 叶子行一并挂上其 NodeDef(node.def())
 * 与节点级监听器链(node.listeners(), 全局链+本地声明合并, 全局在前), 均为 transient 不落库——控制节点没有
 * NodeDef, 行↔表达式的配对只在这里发生; 其后驱动/策略一律**行内自取定义**, 不再回查 ProcessDefinition、
 * 也不作为参数层层传递。
 *
 * 实例行只存运行期数据; listener 链等可由定义(版本钉死)推导的数据不落实例。
 * 例外: 各物化策略建行时把定义引用键与展示名烙进行上的 nodeDefId/name 展示快照(叶子见 LeafExprMaterializer,
 * 命名容器见 Group/ParallelExprMaterializer), 让进度视图直接读列就能展示, 不再依赖 transient 的 def 或 config 自述键。
 */
@Component
public class Materializer {

    /** IF 决策(then/else, 恢复复用; 运行时数据, 只落 config)。 */
    public static final String CFG_CHOSEN = "__chosen";

    private final NodeInstMapper nodeMapper;
    private final Map<Class<? extends DslParser.Expr>, ExprMaterializer> materializers = new HashMap<>();

    public Materializer(NodeInstMapper nodeMapper, List<ExprMaterializer> strategies) {
        this.nodeMapper = nodeMapper;
        for (ExprMaterializer m : strategies) {
            materializers.put(m.exprType(), m);
        }
    }

    /** 物化一个表达式(策略分发), 返回本节点业务键 nodeInstId。 */
    public String materializeNode(DriveContext dc, DslParser.Expr expr, String parentId, boolean eager) {
        ExprMaterializer m = materializers.get(expr.getClass());
        if (m == null) {
            throw new ProcessException("AU204304", expr.getClass().getSimpleName());
        }
        return m.materialize(this, dc, expr, parentId, eager);
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
     * **有声明键的表达式**(Leaf 的 nodeRef / Group 的 name / 命名后缀的 Parallel.name)从钉死版本的 ProcessDefinition
     * 取 NodeDef(一次取定, 驱动期行内自取, 不再回查); 匿名控制容器(PARALLEL/IF)无声明 -> def 为 null。
     * 链 = 全局节点级链 + 节点自己声明的链合并(全局在前); 无 NodeDef 时只走全局链。
     */
    private NodeInstEntity attachCursor(DriveContext dc, NodeInstEntity row, DslParser.Expr expr) {
        row.setExpr(expr);
        String defRef = expr instanceof DslParser.Leaf ? ((DslParser.Leaf) expr).nodeRef
                : expr instanceof DslParser.Group ? ((DslParser.Group) expr).name
                : expr instanceof DslParser.Parallel ? ((DslParser.Parallel) expr).name : null;
        NodeDef nd = defRef == null || dc.def() == null ? null : dc.def().node(defRef);
        row.setDef(nd);
        row.setListeners(dc.def() == null ? null
                : ProcessDefinition.mergeWithGlobals(dc.def().getGlobalNodeListeners(),
                        nd == null ? null : nd.getListeners()));
        return row;
    }

    // ===================== 共享工具(物化策略复用) =====================

    /** 物化期 PLAN 解析: 用创建期 baseVars 替换 ${vars.*}/${params.*}; result.* 未解析原样保留(#12)。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> resolvePlan(DriveContext dc, Map<String, Object> cfg) {
        Object resolved = dc.resolver().resolve(cfg, null, VariableResolver.Phase.PLAN, dc.baseVars());
        return asMap(resolved);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }
}
