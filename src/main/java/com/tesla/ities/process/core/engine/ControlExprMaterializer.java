package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.util.IdGenerator;
import com.tesla.ities.process.util.IdKind;
import com.tesla.ities.process.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控制节点(PARALLEL/IF/GROUP)物化基类: 共用 control 行构造(无 kind/path; 监听器由定义推导不落 config)。
 *
 * error_policy 默认取流程级**全局** errorPolicy(IF 恒如此; 匿名 PARALLEL 也在 flow DSL 里, 无 NodeDef 可就近声明),
 * 于是 PARALLEL 的 failFast 语义因此也能被全局策略一改就生效。
 * **命名容器是例外**, 它们走 {@link #newControlNode(DriveContext, NodeType, String, String, String, Map)}
 * 传自己的生效策略与 config, 于是"某一项遇错即停、另一项逐项跑完"能各自表达:
 *  - GROUP: 声明在 nodes 下(聚合节点段);
 *  - PARALLEL: flow 里的命名后缀 {@code PARALLEL:checks(a,b)}(写在关键字与括号之间)指向 nodes 下的一条纯容器声明。
 */
public abstract class ControlExprMaterializer implements ExprMaterializer {

    protected final NodeInstMapper nodeMapper;
    protected final IdGenerator ids;

    protected ControlExprMaterializer(NodeInstMapper nodeMapper, IdGenerator ids) {
        this.nodeMapper = nodeMapper;
        this.ids = ids;
    }

    /** 建一条控制节点行(仅运行期元数据; config 留空, IF 决策由策略落 __chosen)。匿名控制节点无定义, nodeDefId/name 留 NULL。 */
    protected String newControlNode(DriveContext dc, NodeType type, String parentId, String condition) {
        return newControlNode(dc, type, parentId, condition, globalErrorPolicy(dc), null, null, null);
    }

    /**
     * 建一条控制节点行, 显式给出生效错误策略、config 与展示快照(命名容器用: GROUP / 冒号标签的 PARALLEL, 声明优先于全局)。
     *
     * @param errorPolicy 已解析的**生效值**(ErrorPolicy.getStatus() 小写); null 则回落全局
     * @param config      业务 config(已 PLAN 解析); null 则落空对象
     * @param nodeDefId   定义引用键(命名容器的声明键); 展示用快照
     * @param name        展示名(NodeDef.displayName); 展示用快照, null 则视图回落 nodeDefId/业务键
     */
    protected String newControlNode(DriveContext dc, NodeType type, String parentId, String condition,
                                    String errorPolicy, Map<String, Object> config, String nodeDefId, String name) {
        String id = ids.next(IdKind.NODE_INST);
        NodeInstEntity n = new NodeInstEntity();
        n.setNodeInstId(id);
        n.setProcInstId(dc.procInstId());
        n.setNodeDefId(nodeDefId);
        n.setName(name);
        n.setType(type.getStatus());
        n.setParentId(parentId);
        n.setStatus(Status.CREATED.getStatus());
        n.setErrorPolicy(errorPolicy != null ? errorPolicy : globalErrorPolicy(dc));
        n.setShardEnabled(false);
        n.setConditionExpr(condition);
        n.setConfig(Json.write(config == null ? new LinkedHashMap<String, Object>() : config));
        String actor = dc.processContext().userId();   // 建行者 = 流程创建人
        n.setCreatorId(actor);
        n.setUpdatorId(actor);
        nodeMapper.insertIgnore(n);
        return id;
    }

    /** 全局错误策略(无定义上下文时退回默认 FAIL_FAST, 不让物化因定义缺失炸掉)。 */
    private static String globalErrorPolicy(DriveContext dc) {
        return (dc.def() == null ? ErrorPolicy.DEFAULT : dc.def().globalErrorPolicy()).getStatus();
    }

    @Override
    public abstract Class<? extends DslParser.Expr> exprType();
}
