package com.tesla.ities.process.core.context;

import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.domain.NodeInstEntity;

/**
 * 节点级上下文, 父为**直属上层容器**。**直接持有实例行**(NodeInstEntity), 所以 node 不再单独传:
 * 策略/驱动只带这一个对象, 行内字段(type/expr/errorPolicy/shardResolver/config...)与派生信息同源。
 *
 * 父链形状: 顶层节点与匿名控制容器(PARALLEL/IF)的子节点直接挂在 ProcessContext 上;
 * **聚合节点(GROUP)的子节点挂在组的 NodeContext 上**, 于是上下文父链与实例树同形——
 * 组级共享 config(${config.<k>})与使用者**显式挂进组桶**的 result(${result.<k>})都靠这一层可读;
 * 引擎不自动向任何上级桶写值(无冒泡/挂载, 见 GroupStrategy)。因此 process() 一律**沿父链上溯**, 不再等于 parent()。
 *
 * 派生视图:
 *  - definition() = 行内对齐期挂载的声明定义(匿名控制容器/未对齐行为 null; GROUP 有定义);
 *  - nodeRef()    = 定义 id(flow DSL 引用键; 聚合节点展开后为 "<组>.<子>" 形式);
 *  - name()       = 展示名: 定义 name > 定义 id > 实例业务键(始终非空)。
 *
 * userId 与 vars 同族: 本层不存, 逐级上溯到流程(创建人由 create 传入, 落 process_inst.creator_id)。
 */
public class NodeContext extends Context {
    private final NodeInstEntity inst;

    /** @param parent 直属上层容器: ProcessContext(顶层/匿名控制容器的子节点) 或组的 NodeContext(聚合节点子节点) */
    public NodeContext(Context parent, NodeInstEntity inst) {
        super(parent);
        this.inst = inst;
    }

    /** 本节点实例行(transient 走查光标/定义/监听器链都从这里读)。 */
    public NodeInstEntity inst() {
        return inst;
    }

    public String nodeInstId() {
        return inst.getNodeInstId();
    }

    /** 节点声明定义(可为 null: 匿名控制容器 / 未走查对齐的行)。 */
    public NodeDef definition() {
        return inst.getDef();
    }

    /** 声明级节点 id(flow DSL 里的引用键); 无定义时 null。 */
    public String nodeRef() {
        NodeDef nd = inst.getDef();
        return nd == null ? null : nd.getId();
    }

    /** 展示名: 定义 name > 定义 id > 实例业务键。 */
    public String name() {
        NodeDef nd = inst.getDef();
        String display = nd == null ? null : nd.displayName();
        return display != null ? display : inst.getNodeInstId();
    }

    /** 流程级上下文: 沿父链上溯(聚合节点会多出一层组容器, 故不再等于 parent())。 */
    public ProcessContext process() {
        Context c = parent();
        while (c != null && !(c instanceof ProcessContext)) {
            c = c.parent();
        }
        return (ProcessContext) c;
    }
}
