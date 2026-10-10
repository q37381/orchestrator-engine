package com.tesla.ities.process.core.context;

import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.util.Errors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流程级上下文。持有运行期变量空间 vars(#7), 可被任意子层写入, 引擎在 action 跑完后刷回 process_inst.vars。
 * vars 的解析: 定义级 variables 在创建期已合并进初始 vars; 运行期 putVar 叠加/覆盖。
 *
 * 审计与定义(t2/t5):
 *  - userId = 创建流程时由调用方传入的当前用户(= process_inst.creator_id), 各级实例行的 creator_id 都取它,
 *    写库的 updator_id 也靠它下传(无 ThreadLocal);
 *  - definition = 钉死版本的流程定义(transient, 不落库), 由创建/驱动入口 attachDefinition 挂上——
 *    ProcessListener 据 ctx.nodes()/nodeDefinition(id)/definition() 可读到**全部节点定义**。
 *
 * 线程安全: PARALLEL 子节点/分片 lane 在不同线程并发跑, 可能并发 putVar/putResult; vars 顶层用
 * ConcurrentHashMap, putVar/getVar/putResult 加锁串行化(嵌套子 map 的写也在锁内), 避免并发结构破坏。
 *
 * result 桶(流程级落库): 写流程级 result 一律**置脏**(只可能是 pctx.putResult——子层容器自己的 putResult
 * 落各自表的 result 列, 与本桶无关), 驱动路径由 NodeDriver.flushProcessState 随 vars 一起脏刷回
 * process_inst.result; 从库读回走 {@link #putAllResults}(不置脏), 否则每次驱动都把原值原样刷回去。
 * 回退路径**不刷**这一列(前置监听器挂的现场信息只留在内存)。
 *
 * 失败因由(#14 同族): 引擎各层 catch 到的异常经 {@link #recordFailure} 攒成**一条**摘要(首个胜出),
 * 由流程收尾 {@link #settleFailure} 写进 result 的保留键 __error 落库——坏终态才写, 成功终态反向摘残留,
 * 让流程行自己就说清为什么坏了, 不再只躺在日志里。
 *
 * vars 桶**不收 null 值**(ConcurrentHashMap 的硬约束, 否则 putAll/put 直接 NPE):
 *  定义 vars / 创建参数里某键写了但留空 -> 灌入时跳过(等于没这个变量); putVar(key, null) -> 删键(不残留旧值)。
 */
public class ProcessContext extends Context {
    private final String procInstId;
    private final Map<String, Object> vars = new ConcurrentHashMap<>();
    private volatile String userId;
    private volatile ProcessDefinition definition;   // 钉死版本定义(只读), 监听器可读全部节点定义
    private volatile boolean varsDirty;     // 标记本次 step 是否写过 vars, 决定是否刷库
    private volatile boolean resultDirty;   // 同上, 标记流程级 result 是否被写过
    private volatile String failure;        // 本次驱动捕获到的**第一条**失败摘要(内存态, 由流程收尾落 process_inst.result.__error)

    public ProcessContext(String procInstId) {
        super(null);
        this.procInstId = procInstId;
    }

    public String procInstId() {
        return procInstId;
    }

    /** 创建流程时传入的当前用户(读取走基类 userId(), 本级直接返回存储值)。 */
    public void setUserId(String v) {
        this.userId = v;
    }
    @Override public String userId() {
        return userId;
    }

    /** 钉死版本的完整流程定义(可能为 null: 旧行/无定义上下文)。 */
    public ProcessDefinition definition() {
        return definition;
    }
    public void attachDefinition(ProcessDefinition v) {
        this.definition = v;
    }

    /** 全部节点定义(只读视图; 未挂载定义时为空); 要**按 flow 顺序**的清单走 definition().flowOrder()。 */
    public Map<String, NodeDef> nodes() {
        return definition == null ? Collections.emptyMap() : definition.getNodes();
    }

    /** 按节点 id 取单个节点定义。 */
    public NodeDef nodeDefinition(String nodeId) {
        return definition == null ? null : definition.node(nodeId);
    }

    @Override public Map<String, Object> vars() {
        return vars;
    }

    /** 读单个变量(泛型口径同基类: 类型不符当场 CCE)。 */
    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T getVar(String key) {
        return (T) nestedGet(vars, key);
    }

    @Override
    public synchronized void putVar(String key, Object value) {
        if (value == null) {
            nestedRemove(vars, key);   // 空值 = 清空这个变量(桶不收 null)
        } else {
            nestedPut(vars, key, value);
        }
        varsDirty = true;
    }

    public boolean isVarsDirty() {
        return varsDirty;
    }
    public void clearVarsDirty() {
        varsDirty = false;
    }

    /** 流程级 result 写即置脏(与 putVar 同口径): 驱动路径据此脏刷 process_inst.result。 */
    @Override
    public synchronized void putResult(String key, Object value) {
        super.putResult(key, value);
        resultDirty = true;
    }

    public boolean isResultDirty() {
        return resultDirty;
    }
    public void clearResultDirty() {
        resultDirty = false;
    }

    /**
     * 记下本次驱动里**第一条**失败摘要(并行 lane/子节点同时炸也只留最先那句, 后来的不动):
     * 引擎各层 catch 都调这里, 收尾由 {@link #settleFailure} 落 process_inst.result.__error。
     * 只存内存、不置 result 脏——免得 CONTINUE_ON_ERROR 最终跑成功的流程也挂上失败信息。
     */
    public synchronized void recordFailure(String error) {
        if (error != null && failure == null) {
            failure = error;
        }
    }

    /** 本次驱动记下的首条失败摘要; 没有则 null。 */
    public synchronized String failure() {
        return failure;
    }

    /**
     * 收尾把失败因由落到流程级 result 的保留键(走 putResult 置脏, 随既有脏标记同条 updateState 刷回);
     * 传 null(成功终态/本轮没异常)则**摘掉上一轮残留**——重跑成功后不该还挂着 __error。
     */
    public synchronized void settleFailure(String error) {
        if (error == null) {
            resultDirty |= result.remove(Errors.KEY) != null;
        } else {
            putResult(Errors.KEY, error);
        }
    }

    /** 从库重建 result 桶: 整体灌入**不置脏**(脏标记只记本次驱动里的写)。 */
    public synchronized void putAllResults(Map<String, Object> src) {
        if (src != null) {
            result.putAll(src);
        }
    }

    /** 刷库用的加锁快照: result 是 LinkedHashMap, 并行 lane/子节点线程可能正在写。 */
    public synchronized Map<String, Object> resultSnapshot() {
        return new LinkedHashMap<>(result);
    }

    /** 批量灌入(创建/收尾重读): 值为 null 的键跳过——"写了但留空"就当没这个变量。 */
    public void putAllVars(Map<String, Object> src) {
        if (src == null) {
            return;
        }
        for (Map.Entry<String, Object> e : src.entrySet()) {
            if (e.getValue() != null) {
                vars.put(e.getKey(), e.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object nestedGet(Map<String, Object> root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(seg);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private void nestedPut(Map<String, Object> root, String path, Object value) {
        String[] segs = path.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            Object nxt = cur.get(segs[i]);
            if (!(nxt instanceof Map)) {
                nxt = new LinkedHashMap<String, Object>();
                cur.put(segs[i], nxt);
            }
            cur = (Map<String, Object>) nxt;
        }
        cur.put(segs[segs.length - 1], value);
    }

    /** 点号路径的叶子删除; 路径中段不是 map = 本就不存在, 静默 no-op。 */
    @SuppressWarnings("unchecked")
    private void nestedRemove(Map<String, Object> root, String path) {
        String[] segs = path.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            Object nxt = cur.get(segs[i]);
            if (!(nxt instanceof Map)) {
                return;
            }
            cur = (Map<String, Object>) nxt;
        }
        cur.remove(segs[segs.length - 1]);
    }
}
