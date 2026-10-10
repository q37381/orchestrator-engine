package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.util.IdGenerator;
import org.springframework.stereotype.Component;

/**
 * IF 物化: 只建 IF 控制行(条件入 condition_expr)。分支天然惰性——即便 EAGER 也不物化,
 * 由 IfStrategy 决策后走查物化选中分支(__chosen 落 config, 恢复复用)。
 */
@Component
public class IfExprMaterializer extends ControlExprMaterializer {

    public IfExprMaterializer(NodeInstMapper nodeMapper, IdGenerator ids) {
        super(nodeMapper, ids);
    }

    @Override
    public Class<? extends DslParser.Expr> exprType() {
        return DslParser.If.class;
    }

    @Override
    public String materialize(Materializer ctx, DriveContext dc, DslParser.Expr expr, String parentId, boolean eager) {
        DslParser.If iff = (DslParser.If) expr;
        return newControlNode(dc, NodeType.IF, parentId, iff.condition);
    }
}
