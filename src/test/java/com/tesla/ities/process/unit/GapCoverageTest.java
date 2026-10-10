package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.model.DefinitionStatus;
import com.tesla.ities.process.core.model.RollbackStatus;
import com.tesla.ities.process.util.IdGenerator;
import com.tesla.ities.process.util.Json;
import com.tesla.ities.process.exception.ProcessException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 若干孤立分支的收敛: JSON 序列化失败、枚举 of() 无匹配回落、NodeDef/ProcessDefinition 全访问器、
 * Context/ProcessContext 点号路径遇标量的降级、ActionContext 转换型 payload 的空校验、IdGenerator base62(0)。
 */
public class GapCoverageTest {

    @Test
    public void jsonWriteUnserializableThrows() {
        try {
            Json.write(new Object());   // Jackson 无序列化器 -> 抛
            Assert.fail("应抛序列化异常");
        } catch (ProcessException expected) {
            Assert.assertEquals("AU204701", expected.getMessageKey());   // 序列化失败也有码可查
        }
    }

    @Test
    public void enumOfNoMatchReturnsNull() {
        Assert.assertNull(DefinitionStatus.of("nope"));
        Assert.assertNull(DefinitionStatus.of("  "));
        Assert.assertNull(RollbackStatus.of("bogus"));
        Assert.assertNull(RollbackStatus.of("   "));
    }

    @Test
    public void nodeDefFullAccessorSweep() {
        NodeDef nd = new NodeDef();
        nd.setId("id");
        nd.setName("nm");
        nd.setHandler("h");
        nd.setShard("sh");
        nd.setLaneExecution("PARALLEL");
        nd.setWhen("w");
        nd.setCondition("c");
        nd.setErrorPolicy("CONTINUE_ON_ERROR");
        Map<String, Object> cfg = Collections.singletonMap("k", 1);
        nd.setConfig(cfg);
        Assert.assertEquals("id", nd.getId());
        Assert.assertEquals("nm", nd.getName());
        Assert.assertEquals("h", nd.getHandler());
        Assert.assertEquals("sh", nd.getShard());
        Assert.assertEquals("PARALLEL", nd.getLaneExecution());
        Assert.assertEquals("w", nd.getWhen());
        Assert.assertEquals("c", nd.getCondition());
        Assert.assertEquals("CONTINUE_ON_ERROR", nd.getErrorPolicy());
        Assert.assertSame(cfg, nd.getConfig());
        Assert.assertNull(nd.getActions());
        Assert.assertNull(nd.getListeners());
    }

    @Test
    public void processDefinitionNullNormalizingSetters() {
        ProcessDefinition d = new ProcessDefinition();
        d.setVars(null);
        d.setNodes(null);
        Assert.assertTrue(d.getVars().isEmpty());
        Assert.assertTrue(d.getNodes().isEmpty());
        Assert.assertNull(d.getSetup());
        Assert.assertNull(d.getSetdown());
        Assert.assertNull(d.getListeners());
        Assert.assertNull(d.getRollbackListeners());
        Assert.assertNull(d.getGlobalNodeListeners());
        Assert.assertNull(d.getGlobalActionListeners());
        Assert.assertNull(d.getFlow());
        Assert.assertNull(d.getErrorPolicy());
        Assert.assertEquals(0, d.setupSize());
        Assert.assertEquals(0, d.setdownSize());
        Assert.assertFalse(d.isEager());
        d.setFlow("x");
        Assert.assertEquals("x", d.getFlow());
        Assert.assertNull(d.node("missing"));
    }

    @Test
    public void contextDescendThroughScalarYieldsNull() {
        ProcessContext p = new ProcessContext("proc");
        p.putConfig("a", "scalar");
        Assert.assertNull(p.resolveConfig("a.b"));   // 首段命中但非 map -> descend 返回 null
    }

    @Test
    public void processContextNestedNonMapBranches() {
        ProcessContext p = new ProcessContext("proc");
        p.putVar("x", "scalar");
        Assert.assertNull(p.getVar("x.y"));           // nestedGet 遇标量 -> null
        Assert.assertTrue(p.nodes().isEmpty());       // 未挂定义
        Assert.assertNull(p.nodeDefinition("any"));   // 未挂定义
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("m", "scalar");
        p.putAllVars(seed);
        p.putVar("m.z", null);                        // nestedRemove 中段非 map -> no-op
    }

    @Test(expected = ProcessException.class)
    public void actionContextTypedPayloadNullThrows() {
        new ActionContext(new ProcessContext("p"), "A", "d", null, null)
                .shardPayload(MiscUnitTest.Bean.class);
    }

    @Test
    public void idGeneratorBase62ZeroViaReflection() throws Exception {
        java.lang.reflect.Method m = IdGenerator.class.getDeclaredMethod("base62", long.class);
        m.setAccessible(true);
        Assert.assertEquals("0", m.invoke(new IdGenerator(), 0L));
    }
}
