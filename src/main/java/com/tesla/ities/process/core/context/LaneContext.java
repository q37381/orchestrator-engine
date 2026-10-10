package com.tesla.ities.process.core.context;

/** 泳道级上下文(仅分片节点), 父为 NodeContext。同 shardKey 的共享边界。 */
public class LaneContext extends Context {
    private final String laneId;
    private final String shardKey;

    public LaneContext(NodeContext parent, String laneId, String shardKey) {
        super(parent);
        this.laneId = laneId;
        this.shardKey = shardKey;
    }
    public String laneId() {
        return laneId;
    }
    public String shardKey() {
        return shardKey;
    }
    @Override public NodeContext parent() {
        return (NodeContext) super.parent();
    }
    public NodeContext node() {
        return parent();
    }
    public ProcessContext process() {
        return node().process();
    }
}
