package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.engine.Materializer;
import com.tesla.ities.process.core.runtime.DefinitionCache;
import com.tesla.ities.process.domain.ProcessDefinitionEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.mapper.ProcessDefinitionMapper;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import com.tesla.ities.process.core.define.DefinitionLoader;
import com.tesla.ities.process.exception.ProcessException;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

/**
 * 物化/定义缓存的防御分支:
 *  - DslParser 的 IF 条件里带嵌套括号与逗号 -> readUntilCommaOrClose 按深度跳过;
 *  - Materializer 收到没有对应物化策略的表达式 -> 抛异常;
 *  - DefinitionCache 定位不到定义版本 -> 抛异常; processInst 委托 mapper。
 */
public class EngineDefensiveTest {

    private static Object stub(Class<?> iface, java.lang.reflect.InvocationHandler h) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h);
    }

    @Test
    public void dslIfConditionWithNestedParensAndComma() {
        List<DslParser.Expr> r = DslParser.parseFlow("IF(eq(f(a,b), c), t, e)");
        DslParser.If iff = (DslParser.If) r.get(0);
        // 条件整体读到顶层逗号前(括号内的逗号不截断)
        Assert.assertEquals("eq(f(a,b), c)", iff.condition);
        Assert.assertNotNull(iff.thenBranch);
        Assert.assertNotNull(iff.elseBranch);
    }

    @Test(expected = ProcessException.class)
    public void materializerWithoutStrategyThrows() {
        Materializer m = new Materializer(null, Collections.emptyList());
        m.materializeNode(null, new DslParser.Leaf("x"), null, false);
    }

    @Test(expected = ProcessException.class)
    public void definitionCacheMissingVersionThrows() {
        ProcessDefinitionMapper defMapper = (ProcessDefinitionMapper) stub(ProcessDefinitionMapper.class,
                (p, meth, args) -> null);   // find 恒返回 null
        ProcessInstMapper procMapper = (ProcessInstMapper) stub(ProcessInstMapper.class,
                (p, meth, args) -> null);
        DefinitionCache cache = new DefinitionCache(defMapper, procMapper, new DefinitionLoader());
        cache.definition("ghost", 1);
    }

    @Test
    public void definitionCacheProcessInstDelegates() {
        ProcessInstEntity row = new ProcessInstEntity();
        row.setProcInstId("P9");
        ProcessInstMapper procMapper = (ProcessInstMapper) stub(ProcessInstMapper.class,
                (p, meth, args) -> "findById".equals(meth.getName()) ? row : null);
        ProcessDefinitionMapper defMapper = (ProcessDefinitionMapper) stub(ProcessDefinitionMapper.class,
                (p, meth, args) -> null);
        DefinitionCache cache = new DefinitionCache(defMapper, procMapper, new DefinitionLoader());
        Assert.assertSame(row, cache.processInst("P9"));
    }

    private static ProcessDefinitionEntity unused() {
        return null;
    }
}
