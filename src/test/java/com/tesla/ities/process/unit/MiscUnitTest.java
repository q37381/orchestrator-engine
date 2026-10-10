package com.tesla.ities.process.unit;

import com.tesla.bcs.sequence.StringSequenceGenerator;
import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.context.LaneContext;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.define.ActionDef;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.ListenerDef;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.model.DefinitionStatus;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.RollbackStatus;
import com.tesla.ities.process.core.model.Scope;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.util.IdGenerator;
import com.tesla.ities.process.util.IdKind;
import com.tesla.ities.process.util.Json;
import com.tesla.ities.process.exception.ProcessException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型/工具/定义/上下文层的基础单测: 状态聚合与反查、错误策略回落、枚举 of/getStatus、
 * JSON 工具边界、业务键生成、DSL 解析与异常分支、NodeDef 语义、上下文分桶与分片 payload 转换。
 */
public class MiscUnitTest {

    // ===================== Status =====================

    @Test
    public void statusAggregateAndPredicates() {
        Assert.assertEquals(Status.SUCCESS, Status.aggregate(null, Status.SUCCESS));
        Assert.assertEquals(Status.FAILED, Status.aggregate(Status.SUCCESS, Status.FAILED));   // a 优先级低 -> 取 b
        Assert.assertEquals(Status.FAILED, Status.aggregate(Status.FAILED, Status.SUCCESS));   // a 优先级高 -> 取 a
        Assert.assertEquals(Status.SUCCESS, Status.aggregate(Status.SUCCESS, Status.SKIPPED));  // 同优先级取 a
        Assert.assertEquals(Status.FAILED, Status.aggregate(Status.STOPPED, Status.FAILED));
        Assert.assertTrue(Status.SUCCESS.isTerminal());
        Assert.assertTrue(Status.FAILED.isTerminal());
        Assert.assertTrue(Status.SKIPPED.isTerminal());
        Assert.assertTrue(Status.STOPPED.isTerminal());
        Assert.assertFalse(Status.RUNNING.isTerminal());
        Assert.assertTrue(Status.SUCCESS.isCompletedOk());
        Assert.assertTrue(Status.SKIPPED.isCompletedOk());
        Assert.assertFalse(Status.FAILED.isCompletedOk());
        Assert.assertEquals(3, Status.FAILED.severity());
    }

    @Test
    public void statusOfIsCaseInsensitiveAndSafe() {
        Assert.assertEquals(Status.SUCCESS, Status.of("success"));
        Assert.assertEquals(Status.SUCCESS, Status.of("SUCCESS"));
        Assert.assertNull(Status.of("nope"));
        Assert.assertNull(Status.of(null));
    }

    // ===================== ErrorPolicy / NodeType / DefinitionStatus / RollbackStatus / Scope / Materialization =====================

    @Test
    public void errorPolicyResolution() {
        Assert.assertEquals(ErrorPolicy.FAIL_FAST, ErrorPolicy.of("fail_fast"));
        Assert.assertEquals("默认回落", ErrorPolicy.DEFAULT, ErrorPolicy.of("bogus"));
        Assert.assertSame(ErrorPolicy.FAIL_FAST, ErrorPolicy.DEFAULT);
        Assert.assertNull(ErrorPolicy.parseOrNull(null));
        Assert.assertNull(ErrorPolicy.parseOrNull("  "));
        Assert.assertEquals(ErrorPolicy.CONTINUE_ON_ERROR, ErrorPolicy.parseOrNull("CONTINUE_ON_ERROR"));
        Assert.assertEquals("fail_fast", ErrorPolicy.FAIL_FAST.getStatus());
        Assert.assertEquals("continue_on_error", ErrorPolicy.CONTINUE_ON_ERROR.getStatus());
    }

    @Test
    public void nodeTypeOf() {
        Assert.assertEquals(NodeType.ACTION, NodeType.of("action"));
        Assert.assertEquals(NodeType.IF, NodeType.of("IF"));
        Assert.assertNull(NodeType.of(null));
        Assert.assertNull(NodeType.of(""));
        Assert.assertNull(NodeType.of("bogus"));
        Assert.assertEquals("parallel", NodeType.PARALLEL.getStatus());
    }

    @Test
    public void definitionStatusAndRollbackStatusAndScope() {
        Assert.assertEquals(DefinitionStatus.PUBLISHED, DefinitionStatus.of("published"));
        Assert.assertTrue(DefinitionStatus.isValid("archived"));
        Assert.assertFalse(DefinitionStatus.isValid("nope"));
        Assert.assertEquals("draft", DefinitionStatus.DRAFT.getStatus());

        Assert.assertEquals(RollbackStatus.COMPENSATED, RollbackStatus.of("COMPENSATED"));
        Assert.assertNull(RollbackStatus.of(null));
        Assert.assertEquals("compensating", RollbackStatus.COMPENSATING.getStatus());

        Assert.assertEquals(Scope.LANE, Scope.of("lane"));
        Assert.assertEquals(Scope.NODE, Scope.of("NODE"));
        Assert.assertNull(Scope.of(null));
        Assert.assertNull(Scope.of(""));
        Assert.assertNull(Scope.of("nope"));
        Assert.assertEquals("action", Scope.ACTION.getStatus());
    }

    @Test
    public void materializationOf() {
        Assert.assertEquals(ProcessDefinition.Materialization.EAGER, ProcessDefinition.Materialization.of("eager"));
        Assert.assertEquals(ProcessDefinition.Materialization.LAZY, ProcessDefinition.Materialization.of("LAZY"));
        Assert.assertNull(ProcessDefinition.Materialization.of(null));
        Assert.assertNull(ProcessDefinition.Materialization.of("  "));
        Assert.assertNull(ProcessDefinition.Materialization.of("bogus"));
        Assert.assertEquals("eager", ProcessDefinition.Materialization.EAGER.getStatus());
    }

    // ===================== Json / IdGenerator =====================

    @Test
    public void jsonHelpers() {
        Assert.assertNull(Json.write(null));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", 1);
        Assert.assertEquals("{\"a\":1}", Json.write(m));
        Assert.assertTrue(Json.readMap(null).isEmpty());
        Assert.assertTrue(Json.readMap("").isEmpty());
        Assert.assertEquals(1, Json.readMap("{\"a\":1}").get("a"));
        try {
            Json.readMap("not-json");
            Assert.fail("非法 JSON 应抛异常");
        } catch (ProcessException expected) {
            Assert.assertEquals("AU204704", expected.getMessageKey());
        }
        Assert.assertTrue(Json.readListMap(null).isEmpty());
        Assert.assertTrue(Json.readListMap("").isEmpty());
        Assert.assertTrue(Json.readListMap("bad").isEmpty());   // 异常吞成空列表
        List<Map<String, Object>> lm = Json.readListMap("[{\"a\":1}]");
        Assert.assertEquals(1, lm.size());
        Assert.assertNotNull(Json.mapper());
    }

    @Test
    public void idGeneratorProducesShortUniqueKeys() {
        IdGenerator g = new IdGenerator();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = g.next(IdKind.ACTION_INST);        // 未注入 Bean: 走本地时间戳兜底序列
            Assert.assertTrue("业务键不超过 20 字符", id.length() <= 20);
            Assert.assertTrue("应唯一", seen.add(id));
        }
    }

    /** 一类业务键 = 一个序列 Bean: 注入按 Bean 名整Map收, 五类各走各的序列, 互不串号。 */
    @Test
    public void idGeneratorUsesOneSequencePerKind() {
        IdGenerator g = new IdGenerator();
        Map<String, StringSequenceGenerator> beans = new LinkedHashMap<>();
        for (IdKind kind : IdKind.values()) {
            beans.put(kind.beanName(), new StringSequenceGenerator());
        }
        beans.put("somebodyElsesSequence", new StringSequenceGenerator());   // 宿主自己的其他序列: 名字不在口径里就不接
        g.setSequenceBeans(beans);
        Assert.assertTrue("五个 Bean 齐了就不该有缺失", g.missingSequenceBeanNames().isEmpty());

        Assert.assertEquals("1", g.next(IdKind.PROCESS_INST));
        Assert.assertEquals("2", g.next(IdKind.PROCESS_INST));
        // 换一类就是另一条序列: 从头续号, 与流程实例那条互不影响
        Assert.assertEquals("1", g.next(IdKind.NODE_INST));
        Assert.assertEquals("1", g.next(IdKind.LANE));
        Assert.assertEquals("1", g.next(IdKind.ACTION_INST));
        Assert.assertEquals("1", g.next(IdKind.ROLLBACK_RECORD));
        Assert.assertEquals("3", g.next(IdKind.PROCESS_INST));
        Assert.assertEquals("Bean 名口径", "processInstSequenceGenerator", IdKind.PROCESS_INST.beanName());

        g.setSequence(IdKind.LANE, null);                  // 解绑(传 null)= 该类回落本地序列
        Assert.assertEquals("解绑后回落", Collections.singletonList("laneSequenceGenerator"),
                g.missingSequenceBeanNames());
        Assert.assertNull(g.getSequence(IdKind.LANE));
    }

    /** 序列取到空值/超长都当场拦: 空业务键会落成查不到的死行, 超长顶 VARCHAR(20) 列宽。 */
    @Test
    public void idGeneratorRejectsBadSequenceValues() {
        Assert.assertEquals("空值", "AU204801", keyOf("   "));
        Assert.assertEquals("null", "AU204801", keyOf(null));
        Assert.assertEquals("超长", "AU204802", keyOf("012345678901234567891"));
    }

    private static String keyOf(final String value) {
        IdGenerator g = new IdGenerator();
        g.setSequence(IdKind.PROCESS_INST, new StringSequenceGenerator() {
            @Override
            public String getStringSequence() {
                return value;
            }
        });
        try {
            g.next(IdKind.PROCESS_INST);
            return "none";
        } catch (ProcessException e) {
            return e.getMessageKey();
        }
    }

    // ===================== DslParser 分支 =====================

    @Test
    public void dslParsesLeafParallelIf() {
        Assert.assertTrue(DslParser.parseFlow(null).isEmpty());
        List<DslParser.Expr> one = DslParser.parseFlow("a");
        Assert.assertTrue(one.get(0) instanceof DslParser.Leaf);
        Assert.assertEquals("a", ((DslParser.Leaf) one.get(0)).nodeRef);

        List<DslParser.Expr> par = DslParser.parseFlow("PARALLEL(a, b)");
        Assert.assertTrue(par.get(0) instanceof DslParser.Parallel);
        Assert.assertEquals(2, ((DslParser.Parallel) par.get(0)).items.size());

        List<DslParser.Expr> ifElse = DslParser.parseFlow("IF(x==1, then, else)");
        DslParser.If iff = (DslParser.If) ifElse.get(0);
        Assert.assertEquals("x==1", iff.condition);
        Assert.assertNotNull(iff.thenBranch);
        Assert.assertNotNull(iff.elseBranch);

        List<DslParser.Expr> ifNoElse = DslParser.parseFlow("IF(c, then)");
        Assert.assertNull(((DslParser.If) ifNoElse.get(0)).elseBranch);
    }

    @Test(expected = ProcessException.class)
    public void dslTrailingCommaBeforeCloseThrows() {
        // 尾逗号紧贴 ')' 触发 peekAfterCommaIsClose 分支后, PARALLEL 收尾 expect(')') 失配 -> 抛异常
        DslParser.parseFlow("PARALLEL(a,)");
    }

    @Test(expected = ProcessException.class)
    public void dslTrailingGarbageThrows() {
        DslParser.parseFlow("a)");
    }

    @Test(expected = ProcessException.class)
    public void dslMissingCloseThrows() {
        DslParser.parseFlow("PARALLEL(a");
    }

    @Test(expected = ProcessException.class)
    public void dslEmptyTokenThrows() {
        DslParser.parseFlow("a,");
    }

    // ===================== NodeDef / ActionDef / ListenerDef =====================

    @Test
    public void nodeDefSemantics() {
        NodeDef nd = new NodeDef();
        nd.setId("n");
        Assert.assertFalse(nd.isSharded());
        Assert.assertFalse(nd.isLaneParallel());
        Assert.assertEquals("n", nd.displayName());   // 无 name 回落 id
        nd.setName("显示名");
        Assert.assertEquals("显示名", nd.displayName());
        nd.setShard("dbTopology");
        Assert.assertTrue(nd.isSharded());
        nd.setLaneExecution("parallel");
        Assert.assertTrue(nd.isLaneParallel());

        // 单 handler -> effectiveActions 合成一条
        nd.setHandler("h");
        List<ActionDef> single = nd.effectiveActions();
        Assert.assertEquals(1, single.size());
        Assert.assertEquals("h", single.get(0).getHandler());

        // 显式 actions 优先
        ActionDef ad = new ActionDef();
        ad.setId("a1");
        ad.setHandler("x");
        ad.setWhen("cond");
        ad.setConfig(Collections.singletonMap("k", "v"));
        ad.setListeners(Collections.singletonList(new ListenerDef()));
        nd.setActions(Arrays.asList(ad));
        Assert.assertSame(ad, nd.effectiveActions().get(0));
        Assert.assertEquals("x", ad.getHandler());
        Assert.assertEquals("cond", ad.getWhen());
        Assert.assertEquals("v", ad.getConfig().get("k"));
        Assert.assertEquals(1, ad.getListeners().size());
    }

    @Test
    public void listenerDefAccessors() {
        ListenerDef ld = new ListenerDef();
        ld.setName("nm");
        Map<String, Object> cfg = Collections.singletonMap("a", 1);
        ld.setConfig(cfg);
        Assert.assertEquals("nm", ld.getName());
        Assert.assertSame(cfg, ld.getConfig());
    }

    // ===================== 上下文分桶 / 分片 payload / 展示名 =====================

    @Test
    public void contextCrossBucketReadAndReplace() {
        ProcessContext p = new ProcessContext("proc");
        p.putConfig("ck", 1);
        p.putParams("ck", 2);
        p.putResult("ck", 3);
        // 同名键三桶各存各的: 读谁就是谁, 引擎不做跨桶合并读(所以没有 get(key))
        Assert.assertEquals(Integer.valueOf(1), (Object) p.getConfig("ck"));
        Assert.assertEquals(Integer.valueOf(2), (Object) p.getParams("ck"));
        Assert.assertEquals(Integer.valueOf(3), (Object) p.getResult("ck"));

        p.putResult("only", "x");
        Assert.assertEquals("x", p.getResult("only"));

        // resolveConfig 嵌套下钻
        Map<String, Object> nested = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("b", "deep");
        nested.put("a", inner);
        p.replaceConfig(nested);
        Assert.assertEquals("deep", p.resolveConfig("a.b"));
        Assert.assertNull(p.resolveConfig("a.zzz"));
        Assert.assertNull(p.resolveConfig("unknown.path"));

        p.replaceConfig(null);
        Assert.assertTrue(p.config().isEmpty());
    }

    @Test
    public void processContextNestedVarsAndNullHandling() {
        ProcessContext p = new ProcessContext("proc");
        p.putVar("a.b.c", "deep");
        Assert.assertEquals("deep", p.getVar("a.b.c"));
        p.putVar("a.b.c", null);          // 空值删键
        Assert.assertNull(p.getVar("a.b.c"));
        p.putVar("x.y", 1);
        p.putVar("x.y", null);            // 中段是 map, 正常删
        Assert.assertNull(p.getVar("x.y"));
        p.putVar("scalar", null);         // 中段非 map 的删除路径 no-op
        Assert.assertNull(p.getVar("scalar"));

        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("k", 1);
        seed.put("nul", null);
        p.putAllVars(seed);
        Assert.assertEquals(Integer.valueOf(1), (Object) p.getVar("k"));
        Assert.assertFalse("null 值不进桶", p.vars().containsKey("nul"));
        p.putAllVars(null);               // null 入参直接返回
        Assert.assertTrue(p.isVarsDirty());
        p.clearVarsDirty();
        Assert.assertFalse(p.isVarsDirty());
    }

    /** 流程级 result: 写即置脏(驱动路径据此脏刷 process_inst.result), 重建灌入与空入参都不置脏。 */
    @Test
    public void processResultDirtyFlagTracksWrites() {
        ProcessContext p = new ProcessContext("proc");
        Assert.assertFalse(p.isResultDirty());

        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("fromDb", 1);
        p.putAllResults(seed);
        Integer fromDb = p.getResult("fromDb");
        Assert.assertEquals(Integer.valueOf(1), fromDb);
        Assert.assertFalse("从库读回不算脏写", p.isResultDirty());
        p.putAllResults(null);            // 空入参 no-op, 不炸也不脏
        Assert.assertFalse(p.isResultDirty());

        p.putResult("fresh", "v");
        Assert.assertTrue(p.isResultDirty());
        Assert.assertEquals(2, p.resultSnapshot().size());   // 快照带全部桶
        p.clearResultDirty();
        Assert.assertFalse(p.isResultDirty());
    }

    @Test
    public void nodeContextNameAndRefFallbacks() {
        ProcessContext p = new ProcessContext("proc");
        NodeInstEntity inst = new NodeInstEntity();
        inst.setNodeInstId("N1");
        NodeContext nc = new NodeContext(p, inst);
        Assert.assertNull(nc.nodeRef());            // 无定义
        Assert.assertEquals("N1", nc.name());       // 回落实例业务键
        Assert.assertSame(p, nc.process());

        NodeDef nd = new NodeDef();
        nd.setId("ref");
        inst.setDef(nd);
        Assert.assertEquals("ref", nc.nodeRef());
        Assert.assertEquals("ref", nc.name());
        nd.setName("展示");
        Assert.assertEquals("展示", nc.name());
    }

    @Test
    public void laneContextAccessors() {
        ProcessContext p = new ProcessContext("proc");
        NodeInstEntity inst = new NodeInstEntity();
        inst.setNodeInstId("N1");
        NodeContext nc = new NodeContext(p, inst);
        LaneContext lc = new LaneContext(nc, "L1", "db-01");
        Assert.assertEquals("L1", lc.laneId());
        Assert.assertEquals("db-01", lc.shardKey());
        Assert.assertSame(nc, lc.node());
        Assert.assertSame(p, lc.process());
    }

    @Test
    public void actionContextPayload() {
        ProcessContext p = new ProcessContext("proc");
        Map<String, Object> mapPayload = new LinkedHashMap<>();
        mapPayload.put("x", 5);
        ActionContext actx = new ActionContext(p, "A1", "def1", "db-01", mapPayload);
        Assert.assertEquals("db-01", actx.shardKey());
        Assert.assertEquals("def1", actx.actionDefId());
        Assert.assertEquals("A1", actx.actionInstId());
        Assert.assertSame(actx.upward(), p);

        Object raw = actx.shardPayload();           // 原对象直取
        Assert.assertSame(mapPayload, raw);
        Bean bean = actx.shardPayload(Bean.class);  // 需要转换走 Jackson
        Assert.assertEquals(5, bean.x);

        actx.cancel();
        Assert.assertTrue(actx.isCancelled());
    }

    /** 无参 shardPayload() 只做转型, 非分片(payload=null)时静默返回 null; 需要类型转换才走 shardPayload(Class) 那条。 */
    @Test
    public void actionContextWithoutPayloadReturnsNull() {
        ActionContext actx = new ActionContext(new ProcessContext("p"), "A", "d", null, null);
        Assert.assertNull(actx.shardPayload());
    }

    /** Jackson 转换目标 POJO。 */
    public static class Bean {
        public int x;
    }
}
