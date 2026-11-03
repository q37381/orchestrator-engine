package com.tt.ii.process.constants;

/**
 * 跨类共享的引擎保留字面量: result/config 保留键、{@code __skipReason} 的值域、占位符语法标记与作用域前缀。
 *
 * 为什么集中: 这些字符串在多个类里被写入/读取, 或解析侧与写回侧必须一字不差地成对出现;
 * 散在各处时"改一处漏一处"的失配只在运行期才暴露(键名拼错读不到、前缀与 substring 长度
 * 对不上解出错值)。集中后一处可查、可搜、可对照。
 *
 * **值是落库与对外契约**: {@code __error}/{@code __skipReason}/{@code __unresolved}/{@code __chosen}
 * 直接写进实例行的 result/config JSON, 消费方(进度接口/守卫/前端/运维脚本)按字面量读取——
 * 改动值必须同步存量数据与消费方, 故测试里刻意保留字面量断言以锁定契约。
 */
public final class ReservedKeys {

    // ---------- 实例行保留键(#14 口径: 失败/跳过等运行期信息不建独立列) ----------

    /** result 保留键: 失败摘要({@link com.tt.ii.process.util.Errors#KEY} 的单一事实来源)。 */
    public static final String ERROR = "__error";

    /** result 保留键: 跳过原因(写入方 ActionRunner/LayerWalker/InterventionService, 读取方 ProgressService)。 */
    public static final String SKIP_REASON = "__skipReason";

    /** result 保留键: 本阶段该解而没解出的占位符路径清单(激活期解析后由 ActionRunner 写入)。 */
    public static final String UNRESOLVED = "__unresolved";

    /** node config 保留键: IF 分支决策(then/else, 恢复复用不重算)。 */
    public static final String CHOSEN = "__chosen";

    /**
     * node config 保留键: IF **未选支**的自述(引用键或容器形状), 与 {@link #CHOSEN} 同条 update 落库。
     * 用途是"一行就问出跳过了哪一支": {@code __chosen=else} 单独看有歧义(走了 else / 根本没有 else),
     * 配对本键就分得开。未选支为空(条件真且没写 else)则**不写**本键 —— 什么都没被跳过。
     */
    public static final String SKIPPED_BRANCH = "__skippedBranch";

    // ---------- 停止先收尾(settleOnStop)的流程级 config 保留键 ----------

    /**
     * process config 保留键: 创建期从定义 settleOnStop 钉死的策略(true = 停止先收尾)。
     * 停止入口与驱动只读流程行, 不依赖定义装载; 未钉(未声明/旧行) = 硬停。
     */
    public static final String SETTLE_ON_STOP = "__settleOnStop";

    /**
     * process config 保留键: 停止请求(两阶段停止的唯一信号)。
     * stop() 落标识 -> 下一轮调度进收尾轮(setdown/finally) -> 落 STOPPED 后清除; retry 重新驱动前也清除。
     */
    public static final String STOP_REQUESTED = "__stopRequested";

    // ---------- __skipReason 的值域(写入方与读取方共用的词汇表) ----------

    /** guard=false 跳过(声明式 when 未通过)。 */
    public static final String GUARD_FALSE = "GUARD_FALSE";

    /** handler 自身返回 SKIPPED。 */
    public static final String HANDLER_SKIP = "HANDLER_SKIP";

    /** 人工跳过(干预入口)。 */
    public static final String MANUAL_SKIP = "MANUAL_SKIP";

    /** 段内未跑到的行清理(drain 落 STOPPED)。 */
    public static final String DRAIN = "DRAIN";

    /**
     * IF 未选支留痕(node 行): 这一支没被选中, 物化成**已跳过**的节点行只为看得见(不驱动、不建其子层/action)。
     * 与 {@link #GUARD_FALSE} 分得开: 后者是"本节点自己 guard 判假", 这里是"上层 IF 选了另一支"。
     */
    public static final String BRANCH_NOT_TAKEN = "BRANCH_NOT_TAKEN";

    // ---------- 占位符语法 ----------

    /** 占位符开始标记: {@code ${路径}} 的 "${"。 */
    public static final String PLACEHOLDER_OPEN = "${";

    /** 变量作用域前缀(带点): 保留作用域的逐个声明; 只有这些开头的残留才算"该解而没解出来"。 */
    public static final String VARS_PREFIX = "vars.";
    public static final String PARAMS_PREFIX = "params.";
    public static final String CONFIG_PREFIX = "config.";
    public static final String RESULT_PREFIX = "result.";
    public static final String PROCESS_PREFIX = "process.";
    public static final String NODE_PREFIX = "node.";
    public static final String LANE_PREFIX = "lane.";
    public static final String ACTION_PREFIX = "action.";

    /** action.params 保留键: 当前动作的原始分片 payload, 仅运行期持有、不落库。 */
    public static final String PAYLOAD = "payload";

    private ReservedKeys() {
    }
}
