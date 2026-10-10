package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.define.DslParser;

/**
 * 表达式物化策略(与 NodeStrategy 驱动策略对称): 一种 DSL 表达式一个物化器,
 * 新增节点类型 = 新增 Expr 类 + 一个物化器(注册到 Materializer), 不再在物化器里堆 if/instanceof 判断。
 *
 * 物化 = 表达式 -> node_inst 行(必要时连带 lane/action 行, 全部 CREATED)。
 * eager=true 时递归物化子树; false 只建本节点行(子层延迟到驱动时, 由驱动策略拿行内表达式走查补物化)。
 * 实例行只存**运行期数据**(状态/结果/业务 config/IF 决策); 监听器链等定义可推导数据不落实例。
 */
public interface ExprMaterializer {

    /** 负责的表达式类型。 */
    Class<? extends DslParser.Expr> exprType();

    /**
     * 物化一个表达式, 返回本节点业务键 nodeInstId。
     * @param ctx Materializer 上下文(递归物化/公共工具)
     */
    String materialize(Materializer ctx, DriveContext dc, DslParser.Expr expr, String parentId, boolean eager);
}
