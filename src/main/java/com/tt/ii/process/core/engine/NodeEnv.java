package com.tt.ii.process.core.engine;

/**
 * 节点驱动的**协作环境**(值对象): 一次 driveNode 用到的五组能力各按角色接口给一份, 档案只取自己那一组
 * (见各类档案的取用), 不再拿到整个 {@link NodeDriver}/{@link Materializer} 门面。
 *
 * 存在的理由有三:
 *  1. **接口隔离**: 档案要的是"递归/对齐/跑 action/判取消/走查"五个动作, 不是实现类的全部 API 面;
 *  2. **签名稳定**: NodeKind.drive 必须一份签名喂所有档案(注册表按 type 分发), 统一收本对象后,
 *     新增协作能力=在这里加一个窄接口字段, 不炸四份档案的签名;
 *  3. **装配无环**: 由 NodeDriver 在驱动点**现组**并作为参数下传(延续"协作走方法参数回调"的既有方向),
 *     档案与 ActionRunner/LayerWalker 之间只有调用期引用, 构造期依赖图保持单向。
 *
 * 与 {@link DriveContext} 的分工: DriveContext = 本次驱动的数据现场(定义/live ProcessContext/baseVars),
 * 本类 = 协作能力(组件引用), 两者并列下传、互不混装。字段全为无状态单例, 现组代价只有一次对象分配。
 */
public final class NodeEnv {

    private final NodeRecursion nodes;
    private final ChildCursor children;
    private final ActionExecution actions;
    private final CancellationGate gate;
    private final LayerWalk walk;
    private final boolean cleanup;

    public NodeEnv(NodeRecursion nodes, ChildCursor children, ActionExecution actions,
                   CancellationGate gate, LayerWalk walk) {
        this(nodes, children, actions, gate, walk, false);
    }

    /** @param cleanup true = 收尾切片(finally)的完整执行档: 闸门恒开, 主段照跑(清理动作本身不能被 settle 拦下) */
    public NodeEnv(NodeRecursion nodes, ChildCursor children, ActionExecution actions,
                   CancellationGate gate, LayerWalk walk, boolean cleanup) {
        this.nodes = nodes;
        this.children = children;
        this.actions = actions;
        this.gate = gate;
        this.walk = walk;
        this.cleanup = cleanup;
    }

    /** 递归驱动子节点(容器类档案与层类用)。 */
    public NodeRecursion nodes() {
        return nodes;
    }

    /** 行↔表达式对齐定位(rootAt/childAt)。 */
    public ChildCursor children() {
        return children;
    }

    /** 跑容器内 action 序列单项(叶子档案用)。 */
    public ActionExecution actions() {
        return actions;
    }

    /** 取消闸门(子层每步查库判停)。 */
    public CancellationGate gate() {
        return gate;
    }

    /** 走查引擎(容器类档案声明"这一层怎么走"用; 见 {@link LayerWalk})。 */
    public LayerWalk walk() {
        return walk;
    }

    /** 是否收尾切片的完整执行档(finally): settle 轮也不跳过主段。 */
    public boolean cleanup() {
        return cleanup;
    }
}
