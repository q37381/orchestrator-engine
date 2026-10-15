package com.tesla.ities.process.core.runtime;

import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.context.LaneContext;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.domain.NodeInstEntity;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 占位符解析单测(无 DB/无 Spring): 聚焦**间接键**(路径里嵌占位符)与两档时机/两轮严格度。
 * 引擎不认得任何业务键名 —— 本测试刻意用 dbKind/sqlByType 这类自定义名字, 证明选支能力不依赖保留字。
 *
 * 解析器无协作者(上下文由入参传入), 按 {@code @InjectMocks} 口径由 Mockito 造; 传进去的
 * ProcessContext/ActionContext 是**真实上下文对象**(被测语义就落在这份桶上), 不做替身。
 */
@RunWith(MockitoJUnitRunner.class)
public class VariableResolverTest {

    @InjectMocks
    private VariableResolver resolver;

    // ===================== 端到端: PLAN 冻结 -> 列内容 -> ACTIVATION 给 handler 的值 =====================

    /** 定义里的两份方言片段(键名全由 DSL 作者定, 片段里带 ${vars.table} 等待填真值)。 */
    private Map<String, Object> dbTypesDict() {
        return map(
                "mysql", map("lockSql", "ALTER TABLE ${vars.table} DISABLE KEYS"),
                "goldendb", map("lockSql", "SET TABLE ${vars.table} READONLY"));
    }

    @Test
    public void variantsInVars_areFullyFrozenAtPlanSoHandlerNeedsNoParsing() {
        // LAZY 下节点"执行到才物化", 此时前置 action 已 putVar -> PLAN 的 baseVars 就是 live vars
        Map<String, Object> liveVars = map("dbKind", "goldendb", "table", "t_order", "batchSize", 5000,
                "dbTypes", dbTypesDict());
        Map<String, Object> cfg = map("sql", "${vars.dbTypes.${vars.dbKind}.lockSql}",
                "batchSize", "${vars.batchSize}");

        Map<String, Object> frozen = asMap(resolver.resolve(cfg, null, VariableResolver.Phase.PLAN, liveVars));
        // 落库的 config 列已是成品: 选支 + 填值 + 保留类型(int 不会变字符串)
        Assert.assertEquals("SET TABLE t_order READONLY", frozen.get("sql"));
        Assert.assertEquals(5000, frozen.get("batchSize"));

        // 激活期再走一轮: 已无占位符, 原样透传(handler 直接 config().get("sql"))
        Map<String, Object> live = asMap(resolver.resolve(frozen,
                action(process("goldendb"), frozen), VariableResolver.Phase.ACTIVATION));
        Assert.assertEquals("SET TABLE t_order READONLY", live.get("sql"));
    }

    @Test
    public void variantsNearbyInConfig_arePartiallyFrozenAtPlanAndPickedAtActivation() {
        Map<String, Object> cfg = map(
                "sqlByType", map("mysql", map("lock", "ALTER TABLE ${vars.table} DISABLE KEYS"),
                        "goldendb", map("lock", "SET TABLE ${vars.table} READONLY")),
                "sql", "${config.sqlByType.${vars.dbKind}.lock}");

        Map<String, Object> frozen = asMap(resolver.resolve(cfg, null, VariableResolver.Phase.PLAN,
                map("dbKind", "goldendb", "table", "t_order")));
        // PLAN 不碰 ${config.*}(无上下文) -> 列里 sql 仍占位符, 但**指针已固化**为 goldendb
        Assert.assertEquals("${config.sqlByType.goldendb.lock}", frozen.get("sql"));
        Assert.assertEquals("SET TABLE t_order READONLY", asMap(asMap(frozen.get("sqlByType")).get("goldendb")).get("lock"));

        // 激活期按 live vars 选支 -> handler 拿到成品
        Map<String, Object> live = asMap(resolver.resolve(frozen,
                action(process("goldendb"), frozen), VariableResolver.Phase.ACTIVATION));
        Assert.assertEquals("SET TABLE t_order READONLY", live.get("sql"));
    }

    @Test
    public void pointerDetectedAfterPlan_isRescuedByActivation() {
        // 物化时方言还没检测出来(dbKind 缺失) -> 整条原样冻结, 不报错也不猜
        Map<String, Object> frozen = asMap(resolver.resolve(map("sql", "${vars.dbTypes.${vars.dbKind}.lockSql}"),
                null, VariableResolver.Phase.PLAN, map("table", "t_order", "dbTypes", dbTypesDict())));
        Assert.assertEquals("${vars.dbTypes.${vars.dbKind}.lockSql}", frozen.get("sql"));

        // 同一次驱动里前置 action 已 ctx.putVar("dbKind", "mysql") -> 激活期同一行解出 mysql 那支
        ProcessContext p = process("mysql");
        Map<String, Object> live = asMap(resolver.resolve(frozen,
                action(p, frozen), VariableResolver.Phase.ACTIVATION));
        Assert.assertEquals("ALTER TABLE t_order DISABLE KEYS", live.get("sql"));
    }

    /** 目标形态: 指针就是 config 的第一段 —— ${config.${vars.dialect}.sql}。 */
    @Test
    public void dialectAsFirstSegmentUnderConfig() {
        Map<String, Object> cfg = map(
                "mysql", map("sql", "ALTER TABLE ${vars.table} DISABLE KEYS"),
                "goldendb", map("sql", "SET TABLE ${vars.table} READONLY"),
                "picked", "${config.${vars.dialect}.sql}");
        ProcessContext p = process("mysql");        // live vars 里有 table=t_order
        p.putVar("dialect", "goldendb");           // 前置 action 检测出来的方言
        Assert.assertEquals("SET TABLE t_order READONLY",
                resolver.resolve("${config.${vars.dialect}.sql}", action(p, cfg), VariableResolver.Phase.ACTIVATION));

        // 叶子直接就是各方言的字符串也行
        ProcessContext p2 = process("mysql");
        p2.putVar("dialect", "goldendb");
        Map<String, Object> flat = map("mysql", "LOCK A", "goldendb", "LOCK B", "picked", "${config.${vars.dialect}}");
        Assert.assertEquals("LOCK B", resolver.resolve("${config.${vars.dialect}}",
                action(p2, flat), VariableResolver.Phase.ACTIVATION));
    }

    // ===================== 构造工具 =====================

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** live vars: 指针变量(这里叫 dbKind)由前置 handler ctx.putVar(...) 检测写入; 附带 vars 里的方言字典。 */
    private ProcessContext process(String kind) {
        Map<String, Object> vars = map("table", "t_order", "dbTypes", dbTypesDict());
        if (kind != null) {
            vars.put("dbKind", kind);
        }
        ProcessContext p = new ProcessContext("p1");
        p.putAllVars(vars);
        return p;
    }

    private ActionContext action(ProcessContext p, Map<String, Object> cfg) {
        NodeInstEntity row = new NodeInstEntity();
        row.setNodeInstId("n1");
        LaneContext l = new LaneContext(new NodeContext(p, row), "l1", "shard-1");
        ActionContext a = new ActionContext(l, "a1", "disableKeys", "shard-1", null);
        cfg.forEach(a::putConfig);
        return a;
    }

    /** 两份方言片段就近声明在 config 里, 键名与指针变量名都是使用者自己定的。 */
    private Map<String, Object> cfgWithVariants() {
        return map(
                "sqlByType", map(
                        "mysql", map("lock", "ALTER TABLE ${vars.table} DISABLE KEYS"),
                        "goldendb", map("lock", "SET TABLE ${vars.table} READONLY")),
                "sql", "${config.sqlByType.${vars.dbKind}.lock}");
    }

    // ===================== 间接键: 运行期按某个值挑一份片段 =====================

    @Test
    public void indirectKeyPicksVariantByRuntimeValue() {
        String ph = "${config.sqlByType.${vars.dbKind}.lock}";
        Assert.assertEquals("ALTER TABLE t_order DISABLE KEYS",
                resolver.resolve(ph, action(process("mysql"), cfgWithVariants()), VariableResolver.Phase.ACTIVATION));
        Assert.assertEquals("SET TABLE t_order READONLY",
                resolver.resolve(ph, action(process("goldendb"), cfgWithVariants()), VariableResolver.Phase.ACTIVATION));
    }

    @Test
    public void indirectKeyWorksInsideVarsToo() {
        Map<String, Object> base = map(
                "dialect", "goldendb", "table", "t_user",
                "dialects", map("goldendb", map("lockSql", "SET TABLE ${vars.table} READONLY")));
        Assert.assertEquals("SET TABLE t_user READONLY",
                resolver.resolve("${vars.dialects.${vars.dialect}.lockSql}", null,
                        VariableResolver.Phase.PLAN, base));
    }

    @Test
    public void unknownPointerKeepsPlaceholderForNextPhase() {
        String ph = "${config.sqlByType.${vars.dbKind}.lock}";
        // 指针变量还没检测出来 -> 无从插值 -> 整条原样保留(不猜、不报错)
        Assert.assertEquals(ph, resolver.resolve(ph, action(process(null), cfgWithVariants()),
                VariableResolver.Phase.ACTIVATION));
        // PLAN 期无上下文 -> config.* 取不到, 但指针已插值, 回写的是固化后的路径
        Assert.assertEquals("${config.sqlByType.mysql.lock}",
                resolver.resolve(ph, null, VariableResolver.Phase.PLAN, map("dbKind", "mysql")));
    }

    /** 用户形态: 指针有值但外层没这个键 -> 只替内层, 外层按已插值路径保留。 */
    @Test
    public void outerMissStillFreezesTheInnerPointer() {
        ProcessContext p = process("mysql");
        p.putVar("a", "xxx");                       // live vars.a=xxx, 但 config 里没有 xxx 这一支
        Map<String, Object> cfg = map("other", "irrelevant");
        Assert.assertEquals("${config.xxx.table}",
                resolver.resolve("${config.${vars.a}.table}", action(p, cfg),
                        VariableResolver.Phase.ACTIVATION));
        // 混排文本里也一样(指针固化, 不被整句丢弃)
        Assert.assertEquals("table=${config.xxx.table}!",
                resolver.resolve("table=${config.${vars.a}.table}!", action(p, cfg),
                        VariableResolver.Phase.ACTIVATION));
        // 指针自己取不到时, 才整条保留原文
        Assert.assertEquals("${config.${vars.nope}.table}",
                resolver.resolve("${config.${vars.nope}.table}", action(p, cfg),
                        VariableResolver.Phase.ACTIVATION));
        // 固化后的路径仍可被下一阶段直接解出(不再依赖 vars.a 当时的值)
        Map<String, Object> frozen2 = map("xxx", map("table", "t_order"), "picked", "${config.xxx.table}");
        Assert.assertEquals("t_order",
                resolver.resolve("${config.xxx.table}", action(process("mysql"), frozen2),
                        VariableResolver.Phase.ACTIVATION));
    }

    @Test
    public void selfReferenceThroughIndirectKeyTerminates() {
        Map<String, Object> cfg = map("sqlByType",
                map("mysql", map("lock", "X ${config.sqlByType.${vars.dbKind}.lock}")));
        Object v = resolver.resolve("${config.sqlByType.${vars.dbKind}.lock}",
                action(process("mysql"), cfg), VariableResolver.Phase.ACTIVATION);
        Assert.assertEquals("X ${config.sqlByType.${vars.dbKind}.lock}", v);   // 内层不再插值, 不会 StackOverflow
    }

    @Test
    public void foreignTemplatesAreKeptVerbatimAndNeverThrow() {
        // 片段里带 handler 自用的 ${table}(首 token 不在保留集): 原样带出
        Map<String, Object> cfg = map("sqlByType", map("mysql", map("lock", "ALTER TABLE ${table} DISABLE KEYS")));
        Assert.assertEquals("ALTER TABLE ${table} DISABLE KEYS",
                resolver.resolve("${config.sqlByType.mysql.lock}", action(process("mysql"), cfg),
                        VariableResolver.Phase.ACTIVATION));
        // 外层直接遇到也不再报错: 引擎不替业务定性, 当外来模板原样交回 handler
        Assert.assertEquals("ALTER TABLE ${table} DISABLE KEYS",
                resolver.resolve("ALTER TABLE ${table} DISABLE KEYS",
                        action(process("mysql"), cfg), VariableResolver.Phase.ACTIVATION));
        // 保留写法但字段不存在(如 ${lane.shardKey}) 也不再抛, 原样保留(但会被 findUnresolved 报出)
        Assert.assertEquals("${lane.shardKey}",
                resolver.resolve("${lane.shardKey}", action(process("mysql"), cfg), VariableResolver.Phase.ACTIVATION));
    }

    @Test
    public void wholeTreeWalkResolvesVariantsInPlace() {
        // 没有"字典不穿透"特权了: 各分支就是普通 config, 里面合法的 ${vars.*} 同样被就地填值
        Map<String, Object> resolved = asMap(resolver.resolve(cfgWithVariants(),
                action(process("goldendb"), cfgWithVariants()), VariableResolver.Phase.ACTIVATION));
        Map<String, Object> byType = asMap(resolved.get("sqlByType"));
        Assert.assertEquals("SET TABLE t_order READONLY", asMap(byType.get("goldendb")).get("lock"));
        Assert.assertEquals("ALTER TABLE t_order DISABLE KEYS", asMap(byType.get("mysql")).get("lock"));
        Assert.assertEquals("SET TABLE t_order READONLY", resolved.get("sql"));   // 选中那支 + 填值一轮
    }

    // ===================== 路径取值规则 =====================

    @Test
    public void configSupportsFlatKeyWithDotAndNestedDrill() {
        Map<String, Object> cfg = map("conn", map("host", "127.0.0.1"), "a.b", "flat-key-hit");
        ActionContext a = action(process("mysql"), cfg);
        Assert.assertEquals("127.0.0.1",
                resolver.resolve("${config.conn.host}", a, VariableResolver.Phase.ACTIVATION));
        Assert.assertEquals("flat-key-hit", resolver.resolve("${config.a.b}", a, VariableResolver.Phase.ACTIVATION));
        Assert.assertEquals("{host=127.0.0.1}", String.valueOf(
                resolver.resolve("${action.config.conn}", a, VariableResolver.Phase.ACTIVATION)));
    }

    /** ${config.*} 沿父链上溯(innermost 胜): 流程级 config = 创建输入, 子层直接可读。 */
    @Test
    public void configBubblesUpToProcessCreateInput() {
        ProcessContext p = process("mysql");
        p.putConfig("cluster", "c-prod");                       // 创建输入落流程级 config 桶
        p.putConfig("pool", map("max", 32));
        ActionContext a = action(p, map("cluster", "c-node"));   // 本层同名键优先

        Assert.assertEquals("c-node",
                resolver.resolve("${config.cluster}", a, VariableResolver.Phase.ACTIVATION));
        Assert.assertEquals("本层没有的键才上溯", 32,
                resolver.resolve("${config.pool.max}", a, VariableResolver.Phase.ACTIVATION));
        Assert.assertEquals("混排文本同样上溯",
                "cluster=c-node pool=32", resolver.resolve("cluster=${config.cluster} pool=${config.pool.max}",
                        a, VariableResolver.Phase.ACTIVATION));
        // 锁定层只读那一层本桶(不上溯): 要流程级就写 ${process.config.*}
        Assert.assertEquals("${node.config.cluster}",
                resolver.resolve("${node.config.cluster}", a, VariableResolver.Phase.ACTIVATION));
        Assert.assertEquals("c-prod",
                resolver.resolve("${process.config.cluster}", a, VariableResolver.Phase.ACTIVATION));
        // PLAN 无 ctx -> config.* 仍留待激活期
        Assert.assertEquals("${config.cluster}",
                resolver.resolve("${config.cluster}", null, VariableResolver.Phase.PLAN, map("cluster", "c-prod")));
    }

    // ===================== 既有语义不回退 =====================

    @Test
    public void planStillFreezesVarsAndParams() {
        Map<String, Object> base = map("batchSize", 5000, "cluster", "c1", "pool", map("max", 32));
        Assert.assertEquals(5000, resolver.resolve("${vars.batchSize}", null, VariableResolver.Phase.PLAN, base));
        Assert.assertEquals(32, resolver.resolve("${vars.pool.max}", null, VariableResolver.Phase.PLAN, base));
        Assert.assertEquals("c1", resolver.resolve("${params.cluster}", null, VariableResolver.Phase.PLAN, base));
        Assert.assertEquals("cluster=c1",
                resolver.resolve("cluster=${params.cluster}", null, VariableResolver.Phase.PLAN, base));
        Assert.assertEquals("${result.queryTopology.host}",
                resolver.resolve("${result.queryTopology.host}", null, VariableResolver.Phase.PLAN, base));
    }

    /** params 桶不持久化: 创建参数在物化期就已被 baseVars 冻结, 激活期只读当下驱动内存里 putParams 的值。 */
    @Test
    public void paramsHaveNoPersistenceSourceAtActivation() {
        ActionContext a = action(process("mysql"), map("k", 1));
        Assert.assertEquals("${params.cluster}",
                resolver.resolve("${params.cluster}", a, VariableResolver.Phase.ACTIVATION));
        a.putParams("cluster", "c-mem");                       // 只有这样才有值(不来自库)
        Assert.assertEquals("c-mem",
                resolver.resolve("${params.cluster}", a, VariableResolver.Phase.ACTIVATION));
    }

    @Test
    public void nestedPlaceholderInsideLiteralTextIsInterpolated() {
        // IF 条件里也走同一套: 混合文本 + 间接键
        Map<String, Object> cfg = map("gates", map("mysql", "open"));
        Object v = resolver.resolve("gate=${config.gates.${vars.dbKind}}!",
                action(process("mysql"), cfg), VariableResolver.Phase.ACTIVATION);
        Assert.assertEquals("gate=open!", v);
    }

    @Test
    public void findUnresolvedReportsLeftoverPaths() {
        Map<String, Object> cfg = map(
                "sql", "${vars.missing}",                       // 保留写法但变量不存在 -> 算残留
                "batchSize", 5000,
                "conn", map("host", "127.0.0.1", "pwd", "${secret.x}"),   // 外来模板 -> 不算残留
                "tables", java.util.Arrays.asList("t_a", "${lane.shardKey}"));
        Assert.assertEquals(java.util.Arrays.asList("sql", "tables[1]"), resolver.findUnresolved(cfg));
        Assert.assertTrue(resolver.findUnresolved(map("sql", "SELECT 1", "batchSize", 5000)).isEmpty());
        // 混排串里只要没有一个保留写法没解出, 就不报(全句都是 handler 自用的模板)
        Assert.assertTrue(resolver.findUnresolved(map("sql", "ALTER TABLE ${table} DISABLE KEYS")).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
