package com.tesla.ities.process.core.define;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tesla.ities.process.core.model.ErrorPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ProcessDefinition: 从 YAML/JSON 解析出的内存模型(不可变版本的反序列化结果)。
 *
 * 变更:
 *  - version/name 不再从 YAML 解析(#3): 以 process_definition 表的 (process_def_id, version) 列为准, 创建/发布时外部传入。
 *  - 多方言片段就是 vars 下的一个普通嵌套包(本例习惯写 vars.dialects.<方言>.<key>); 引擎不认任何保留键名,
 *    选哪一支由占位符里的**间接键**表达: ${vars.dialects.${vars.dialect}.lockSql}。
 *  - flow = 节点执行顺序 DSL(原 body): "A,B,PARALLEL(C,D),IF(x,E,F)", 解析为**顶层节点列表**
 *    (顶层逗号扁平化, 不包 SERIAL 虚拟根)。驱动按定义顺序前向走查, 实例行按下标与表达式对齐。
 *  - setup/setdown = 前置/收尾节点引用列表(声明在 nodes 下, 均为 ACTION):
 *    setup 在 flow 之前按序执行; setdown 类似 finally, **无论成败都执行**(清理锁/上报等)。
 *  - materialization: EAGER(创建期物化整棵 flow) | LAZY(执行到一个物化一个, 默认)。
 *  - listeners = 流程级监听器(ProcessListener, #4), 按声明顺序执行; 链由本定义推导,
 *    不再在 process_inst 上固化快照(定义版本不可变, 推导结果恒定)。
 *  - rollbackListeners = **回退级**监听器(RollbackListener): 补偿路径不走 driveRoots, setup/setdown 与
 *    三类监听器在回退时一个也不触发, 所以给回退自己一对 before/after 切点(现取连接信息之类的现场准备)。
 *  - globalNodeListeners / globalActionListeners = **全局**节点级/action 级监听器: 声明一次, 对每个节点、
 *    每个 action 都生效(与各节点/action 自己声明的链合并, 全局在前)。
 *  - errorPolicy = **全局**错误策略: 声明一次, 没自己声明 errorPolicy 的节点(含匿名 PARALLEL/IF 控制节点与其 lane)
 *    都用它; 与监听器链不同, 单值取**覆盖**语义(节点自己的声明优先), 都没声明才是默认 FAIL_FAST。
 *    另外它也管顶层根序列(setup/flow 遇坏是否继续)——那一层容器就是流程本身。
 *    但根序列/GROUP 组内序列的收敛是**就近声明优先**(见 {@link #stopsSequence}): 那一项自己写了 errorPolicy
 *    就听它的(FAIL_FAST 要求上层停下 / CONTINUE_ON_ERROR 失败也不拦后面), 没写才回落到本层。
 *    要只改**某一条**并行的收敛: flow 里给它挂命名后缀 {@code PARALLEL:checks(a,b)}, 在 nodes.checks 上就近声明。
 *  - autoRollback = **自动回退**开关(与 errorPolicy 同为流程级单值声明): 配成 true 后, 本次驱动的终态若是
 *    FAILED 或 STOPPED, 引擎收尾就当场逆序补偿"已成功且可补偿"的 action(等价于自动调一次 RollbackService)。
 *    只配流程级——回退撤销的是整条流程的副作用, 单个节点无权决定; 不配/配 false 仍旧只有人工回退。
 *  - **借出语义**: 本类可 JSON 往返(DefinitionCache 缓存快照, 每次驱动读出一份私有副本),
 *    所以字段/嵌套容器都按普通 bean 属性处理, 派生方法必须 @JsonIgnore(见 isEager/roots)。
 */
public class ProcessDefinition {
    private Materialization materialization = Materialization.LAZY;
    private Map<String, Object> vars = new LinkedHashMap<>();   // 可带任意嵌套子包(如方言字典); 键不要与创建参数同名(物化期两桶同源)
    private List<String> setup;                                  // 前置节点引用(flow 之前执行)
    private List<String> setdown;                                // 收尾节点引用(finally 语义)
    private List<ListenerDef> listeners;                          // 流程级监听器(ProcessListener)
    private List<ListenerDef> rollbackListeners;                  // 回退级监听器(RollbackListener, 补偿前/后)
    private List<ListenerDef> globalNodeListeners;                // 全局节点级监听器: 每个节点(含 PARALLEL/IF 控制节点)都走
    private List<ListenerDef> globalActionListeners;              // 全局 action 级监听器: 每个 action(含分片每 lane)都走
    private String flow;                                          // DSL: "A,B,PARALLEL(C,D),IF(x,E,F)"
    private String errorPolicy;                                   // 全局错误策略(节点未声明时的回落; null = 默认 FAIL_FAST)
    private String autoRollback;                                  // 自动回退开关(true/false 文本; null 或未声明 = 只走人工回退)
    private Map<String, NodeDef> nodes = new LinkedHashMap<>();

    /** flow 解析结果(顶层表达式列表), 惰性一次性解析, 不参与 JSON 序列化。 */
    private transient volatile List<DslParser.Expr> roots;

    /** 物化时机(不落库, 只来自 YAML 声明): 与全仓枚举同口径, 字符串形态取小写 getStatus()。 */
    public enum Materialization {
        EAGER,
        LAZY;

        private final String status;    // 小写声明值(与 Status/ErrorPolicy 统一口径)

        Materialization() {
            this.status = name().toLowerCase(java.util.Locale.ROOT);
        }

        /** 小写声明英文名。 */
        public String getStatus() {
            return status;
        }

        /** 按声明字符串反查(大小写不敏感); null/空白/不识别返回 null, 由调用方决定回落。 */
        public static Materialization of(String v) {
            if (v == null || v.trim().isEmpty()) {
                return null;
            }
            for (Materialization m : values()) {
                if (m.status.equalsIgnoreCase(v.trim())) {
                    return m;
                }
            }
            return null;
        }
    }

    public Materialization getMaterialization() {
        return materialization;
    }
    public void setMaterialization(Materialization v) {
        this.materialization = v;
    }
    public Map<String, Object> getVars() {
        return vars;
    }
    /** null 一律归一成空 map: 创建期要 new LinkedHashMap<>(getVars()) 合并 baseVars, 不允许 NPE。 */
    public void setVars(Map<String, Object> v) {
        this.vars = v == null ? new LinkedHashMap<>() : v;
    }
    public List<String> getSetup() {
        return setup;
    }
    public void setSetup(List<String> v) {
        this.setup = v;
    }
    public List<String> getSetdown() {
        return setdown;
    }
    public void setSetdown(List<String> v) {
        this.setdown = v;
    }
    public List<ListenerDef> getListeners() {
        return listeners;
    }
    public void setListeners(List<ListenerDef> v) {
        this.listeners = v;
    }
    public List<ListenerDef> getRollbackListeners() {
        return rollbackListeners;
    }
    public void setRollbackListeners(List<ListenerDef> v) {
        this.rollbackListeners = v;
    }
    public List<ListenerDef> getGlobalNodeListeners() {
        return globalNodeListeners;
    }
    public void setGlobalNodeListeners(List<ListenerDef> v) {
        this.globalNodeListeners = v;
    }
    public List<ListenerDef> getGlobalActionListeners() {
        return globalActionListeners;
    }
    public void setGlobalActionListeners(List<ListenerDef> v) {
        this.globalActionListeners = v;
    }
    public String getFlow() {
        return flow;
    }
    public void setFlow(String v) {
        this.flow = v;
    }
    public String getErrorPolicy() {
        return errorPolicy;
    }
    public void setErrorPolicy(String v) {
        this.errorPolicy = v;
    }
    public String getAutoRollback() {
        return autoRollback;
    }
    public void setAutoRollback(String v) {
        this.autoRollback = v;
    }
    public Map<String, NodeDef> getNodes() {
        return nodes;
    }
    /** 同上: 传 null 不留 NPE 种子(node(id) 直接 nodes.get(id))。 */
    public void setNodes(Map<String, NodeDef> v) {
        this.nodes = v == null ? new LinkedHashMap<>() : v;
    }

    public NodeDef node(String id) {
        return nodes.get(id);
    }

    /**
     * 全局链 + 本地声明合并成生效链: **全局在前、本地在后**(都按声明顺序), 不做同名去重——
     * 全局与本地声明同名监听器视为有意的两次触发(各带各的 config)。
     * 两边都没声明时返回 null, 与既有"无链 = 切点 no-op"口径一致。
     */
    public static List<ListenerDef> mergeWithGlobals(List<ListenerDef> globals, List<ListenerDef> locals) {
        boolean noGlobals = globals == null || globals.isEmpty();
        boolean noLocals = locals == null || locals.isEmpty();
        if (noGlobals && noLocals) {
            return null;
        }
        List<ListenerDef> merged = new ArrayList<>();
        if (!noGlobals) {
            merged.addAll(globals);
        }
        if (!noLocals) {
            merged.addAll(locals);
        }
        return merged;
    }

    /** 全局错误策略(本层声明值): 未声明/非法一律默认 FAIL_FAST。控制节点无 NodeDef, 直接用这个。 */
    public ErrorPolicy globalErrorPolicy() {
        return ErrorPolicy.of(errorPolicy);
    }

    /**
     * 节点的生效错误策略: **节点自己的声明优先**(单值覆盖, 不像监听器链那样拼接),
     * 节点未声明/声明不合法 → 用全局 errorPolicy, 两者都没有 → 默认 FAIL_FAST。
     */
    public ErrorPolicy effectiveErrorPolicy(String declared) {
        ErrorPolicy own = ErrorPolicy.parseOrNull(declared);
        return own != null ? own : globalErrorPolicy();
    }

    /** 节点**自己**声明的错误策略; 没声明/声明不合法/无定义(匿名 PARALLEL/IF)一律 null = "这一项没吭声"。 */
    public ErrorPolicy declaredErrorPolicy(NodeDef item) {
        return item == null ? null : ErrorPolicy.parseOrNull(item.getErrorPolicy());
    }

    /**
     * 一项遇坏后,**顶层根序列**(setup/flow)要不要就地收敛(跳过剩余根)——**就近声明优先**:
     *  1. 这一项自己声明了 errorPolicy 就听它的: {@code FAIL_FAST} = "我坏了根序列就停", {@code CONTINUE_ON_ERROR} = "我失败也别拦后续根";
     *  2. 没声明(或声明不合法/无定义)才回落本层策略 {@code ownerPolicy}——根序列那层就是流程级全局 errorPolicy。
     *
     * 于是两项流程级声明盖不住的能力就近表达了: 全局 {@code CONTINUE_ON_ERROR} 里可以指出**关键项**失败即停,
     * 全局 {@code FAIL_FAST} 里也可以把**锦上添花的校验项**标成失败不拦路。匿名 PARALLEL/IF 无 NodeDef, 恒回落全局。
     *
     * 本方法**只作用于根序列这一层**: 容器内部(组内子项/并行扇出/叶子 action)的收敛一律只看容器自己那一条
     * errorPolicy, 子项声明不跨越本层(见 GroupStrategy/ParallelStrategy)。
     */
    @JsonIgnore
    public boolean stopsSequence(NodeDef item, ErrorPolicy ownerPolicy) {
        ErrorPolicy declared = declaredErrorPolicy(item);
        return (declared != null ? declared : ownerPolicy) == ErrorPolicy.FAIL_FAST;
    }

    /**
     * 布尔开关声明值的宽松文本解析: 只认 true / false(大小写不敏感、忽略首尾空白), 其余一律 null。
     * null 有两种来历, 由调用方区分: 装载期 = 非法声明(拒), 判定期 = 没声明(按关闭算)。
     * YAML 侧不需要额外兼容: {@code yes}/{@code on} 这类标量已被 SnakeYAML 解成 Boolean, 转字符串就是 true。
     */
    static Boolean parseSwitch(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        if ("true".equalsIgnoreCase(t)) {
            return Boolean.TRUE;
        }
        return "false".equalsIgnoreCase(t) ? Boolean.FALSE : null;
    }

    /**
     * 本流程是否配了自动回退(派生视图, 非属性): 声明值算出 true 才算开。
     * 生效点在 ExecutionEngine 收尾——驱动终态为 FAILED/STOPPED 时当场逆序补偿。
     */
    @JsonIgnore
    public boolean autoRollbackEnabled() {
        return Boolean.TRUE.equals(parseSwitch(autoRollback));
    }

    /**
     * 是否 EAGER 物化(派生视图, 非属性)。
     * 本类会被序列化成定义快照再读回(DefinitionCache 借出副本), 所以所有**派生方法一律 @JsonIgnore**——
     * Jackson 按 bean 约定把 isXxx/getXxx 当属性写出去, 而回读时没有对应 setter 会直接失配抛异常。
     */
    @JsonIgnore
    public boolean isEager() {
        return materialization == Materialization.EAGER;
    }

    /** flow 顶层表达式列表。 */
    @JsonIgnore
    public List<DslParser.Expr> roots() {
        List<DslParser.Expr> r = roots;
        if (r == null) {
            r = DslParser.parseFlow(flow);
            roots = r;
        }
        return r;
    }

    /** 有序根表达式: setup 引用 + flow 顶层 + setdown 引用(与物化/驱动的行序一致)。 */
    @JsonIgnore
    public List<DslParser.Expr> orderedRoots() {
        List<DslParser.Expr> out = new ArrayList<>();
        if (setup != null) {
            for (String ref : setup) {
                out.add(new DslParser.Leaf(ref));
            }
        }
        out.addAll(roots());
        if (setdown != null) {
            for (String ref : setdown) {
                out.add(new DslParser.Leaf(ref));
            }
        }
        return out;
    }

    /**
     * flow 顺序的全部节点定义: 沿 {@link #orderedRoots()}(setup + flow + setdown)前序展开, 容器在前、子节点紧随。
     *
     * 为什么需要它: {@link #nodes} 是 LinkedHashMap, 键序 = YAML 里 nodes 段的**书写顺序**, 与执行顺序毫无关系;
     * 想"按 flow 顺序列出所有节点"(前端进度骨架、批量初始化校验项、开跑前自检)只能走 DSL 表达式树。
     * 定义侧的这份顺序就是驱动/物化用的那份(实例行按 auto_id 与表达式按下标对齐), 所以两者天然一致。
     *
     * 口径:
     *  - {@code PARALLEL(a,b)} 按声明序输出, 但它们是**并发**跑的, 别当成时间先后;
     *    带命名后缀的 {@code PARALLEL:checks(a,b)} 自己也算一项(它承载 name/when/errorPolicy/config), 与 GROUP 同口径;
     *  - {@code IF(cond,then,else)} 两支都算候选输出(运行期只驱动选中的那支);
     *  - 聚合节点(GROUP)自己算一项(它承载 name/when/errorPolicy/config), 其组内子节点紧随其后;
     *    手写 {@code GROUP:name(...)} 而 nodes 下没那个键 = 匿名串行容器, 组不出现、子节点照常输出;
     *  - flow 引用了 nodes 下不存在的键 -> 跳过(那种定义本就在驱动期报 AU204302, 这里不替引擎判错)。
     */
    @JsonIgnore
    public List<NodeDef> flowOrder() {
        List<NodeDef> out = new ArrayList<>();
        for (DslParser.Expr e : orderedRoots()) {
            collectInFlowOrder(e, out);
        }
        return out;
    }

    /** 前序收集一个表达式: 能定位到 NodeDef 的才进结果, 控制表达式则继续展开其子表达式。 */
    private void collectInFlowOrder(DslParser.Expr e, List<NodeDef> out) {
        if (e instanceof DslParser.Leaf) {
            NodeDef nd = node(((DslParser.Leaf) e).nodeRef);
            if (nd != null) {
                out.add(nd);
            }
        } else if (e instanceof DslParser.Parallel) {
            DslParser.Parallel par = (DslParser.Parallel) e;
            if (par.name != null) {                 // 命名后缀的命名并行容器: 自己算一项(承载策略/guard/config), 子项紧随
                NodeDef nd = node(par.name);
                if (nd != null) {
                    out.add(nd);
                }
            }
            for (DslParser.Expr item : par.items) {
                collectInFlowOrder(item, out);
            }
        } else if (e instanceof DslParser.Group) {
            DslParser.Group group = (DslParser.Group) e;
            NodeDef nd = node(group.name);
            if (nd != null) {
                out.add(nd);
            }
            for (DslParser.Expr item : group.items) {
                collectInFlowOrder(item, out);
            }
        } else if (e instanceof DslParser.If) {
            DslParser.If iff = (DslParser.If) e;
            collectInFlowOrder(iff.thenBranch, out);
            if (iff.elseBranch != null) {
                collectInFlowOrder(iff.elseBranch, out);
            }
        }
    }

    /** setup 段大小(顶层行序 [0, setupSize))。 */
    public int setupSize() {
        return setup == null ? 0 : setup.size();
    }

    /** setdown 段大小(顶层行序末尾)。 */
    public int setdownSize() {
        return setdown == null ? 0 : setdown.size();
    }
}