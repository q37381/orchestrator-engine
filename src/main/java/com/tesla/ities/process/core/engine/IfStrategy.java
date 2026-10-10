package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.util.Json;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * IF 策略(#3): 评估条件 -> 惰性物化选中分支 -> 内联驱动该分支(单分支, 串行语义)。
 * 决策(__chosen)持久化进 config(运行时数据), 恢复时复用不重算。未选分支不物化(#4 惰性)。
 * 分支表达式来自行内走查光标 node.expr()(不反查), 子行按下标对齐(唯一子行 ↔ 选中分支),
 * 对齐时选中分支表达式已补充进子行, 递归驱动不再传定义。
 * 无选中分支(条件假且无 else) -> SKIPPED(对控制流 = 成功完成)。
 */
@Component
public class IfStrategy implements NodeStrategy {

    private final NodeInstMapper nodeMapper;
    private final Materializer materializer;

    public IfStrategy(NodeInstMapper nodeMapper, Materializer materializer) {
        this.nodeMapper = nodeMapper;
        this.materializer = materializer;
    }

    @Override public NodeType type() {
        return NodeType.IF;
    }

    @Override
    public Status drive(NodeDriver driver, DriveContext dc, NodeContext ctx) {
        NodeInstEntity node = ctx.inst();
        DslParser.If iff = (DslParser.If) node.getExpr();
        Map<String, Object> cfg = Json.readMap(node.getConfig());

        // 决策: 已持久化则复用, 否则现场评估
        boolean cond;
        if (cfg.containsKey(Materializer.CFG_CHOSEN)) {
            cond = "then".equals(cfg.get(Materializer.CFG_CHOSEN));
        } else {
            cond = dc.evaluator().eval(node.getConditionExpr(), dc.processContext());
            cfg.put(Materializer.CFG_CHOSEN, cond ? "then" : "else");
            nodeMapper.updateConfig(node.getNodeInstId(), Json.write(cfg), ctx.userId());
        }

        DslParser.Expr chosen = cond ? iff.thenBranch : iff.elseBranch;
        if (chosen == null) {
            return Status.SKIPPED;     // 无选中分支
        }

        // 惰性物化选中分支(IF 分支总是执行到才建, 即便流程是 EAGER); 重入已存在则直接复用
        // 分支的直属容器 = 本 IF 的 ctx(上下文父链与实例树同形, 嵌在聚合节点里时组桶对分支仍可读)
        List<DslParser.Expr> branch = Collections.singletonList(chosen);
        NodeInstEntity child = materializer.childAt(dc, node, branch, 0, dc.def().isEager());
        return driver.driveNode(dc, child, ctx);
    }
}
