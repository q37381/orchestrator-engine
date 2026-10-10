package com.tesla.ities.process.util;

/**
 * 业务键种类 —— 一一对应宿主环境的**一个序列号生成器 Bean**(一个 Bean 一条序列)。
 *
 * 引擎产出的五类业务键各走自己的序列, 互不串号: 取号时按本枚举定位 Bean, 而 {@code beanName()} 是**唯一的**
 * Bean 名口径(注入按名整Map取, 不写 {@code @Qualifier})。宿主那边叫别的名字时只改下面五行字符串,
 * 调用点一律不动。
 */
public enum IdKind {

    /** process_inst.proc_inst_id */
    PROCESS_INST("processInstSequenceGenerator"),
    /** process_node_inst.node_inst_id(叶子与 PARALLEL/IF/GROUP 控制节点同一条序列) */
    NODE_INST("nodeInstSequenceGenerator"),
    /** process_lane.lane_id(分片 lane) */
    LANE("laneSequenceGenerator"),
    /** process_action_inst.action_inst_id */
    ACTION_INST("actionInstSequenceGenerator"),
    /** process_rollback_record.rollback_id(补偿留痕) */
    ROLLBACK_RECORD("rollbackRecordSequenceGenerator");

    private final String beanName;

    IdKind(String beanName) {
        this.beanName = beanName;
    }

    /** 该种类对应的序列号生成器 Bean 名(宿主上下文里必须叫这个, 或用 {@code IdGenerator.setSequence} 显式绑)。 */
    public String beanName() {
        return beanName;
    }
}
