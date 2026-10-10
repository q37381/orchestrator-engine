package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.runtime.ConditionEvaluator;
import com.tesla.ities.process.core.runtime.VariableResolver;
import com.tesla.ities.process.domain.ProcessInstEntity;

import java.util.Map;

/**
 * 单次流程驱动(drive)的上下文。owner 认领流程后构造一份, 贯穿整棵树的递归执行。
 * 持有 live 的 ProcessContext(含运行期 vars)、定义、解析器/求值器(无状态单例, #5)。
 * baseVars = 创建期初始 vars(定义 vars + 创建 params), 供物化时 PLAN 阶段解析 ${vars.*}/${params.*}(#12)。
 *
 * 注: 分片解析(ShardResolver)的入参是**节点级 NodeContext**(由 NodeDriver 逐节点建并下传,
 * 流程级参数沿父链上溯), 不在本层缓存任何分片上下文。
 */
public class DriveContext {
    private final ProcessInstEntity processInst;
    private final ProcessDefinition def;
    private final ProcessContext processContext;     // live, vars 可写
    private final VariableResolver resolver;          // 无状态单例
    private final ConditionEvaluator evaluator;       // 无状态单例
    private final Map<String, Object> baseVars;       // 创建期初始 vars(PLAN 阶段解析用)

    public DriveContext(ProcessInstEntity processInst, ProcessDefinition def, ProcessContext processContext,
                        VariableResolver resolver, ConditionEvaluator evaluator,
                        Map<String, Object> baseVars) {
        this.processInst = processInst;
        this.def = def;
        this.processContext = processContext;
        this.resolver = resolver;
        this.evaluator = evaluator;
        this.baseVars = baseVars;
    }

    /** 从已构造的 ProcessContext(含 live vars)派生。resolver/evaluator 为注入的无状态单例。 */
    public static DriveContext create(ProcessInstEntity p, ProcessDefinition def, ProcessContext pctx,
                                      VariableResolver resolver, ConditionEvaluator evaluator) {
        return new DriveContext(p, def, pctx, resolver, evaluator, pctx.vars());
    }

    /** 流程业务键。实体行不外露: 驱动期所需的流程级数据一律走 ProcessContext(已装 config/vars/userId)与定义。 */
    public String procInstId() {
        return processInst.getProcInstId();
    }
    public ProcessDefinition def() {
        return def;
    }
    public ProcessContext processContext() {
        return processContext;
    }
    public VariableResolver resolver() {
        return resolver;
    }
    public ConditionEvaluator evaluator() {
        return evaluator;
    }
    public Map<String, Object> baseVars() {
        return baseVars;
    }
}
