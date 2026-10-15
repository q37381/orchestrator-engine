package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.define.DefinitionLoader;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.engine.Materializer;
import com.tesla.ities.process.core.engine.NodeKind;
import com.tesla.ities.process.core.engine.NodeKinds;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.mapper.ProcessDefinitionMapper;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import com.tesla.ities.process.core.runtime.DefinitionCache;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.when;

/**
 * 物化/定义缓存的防御分支:
 *  - DslParser 的 IF 条件里带嵌套括号与逗号 -> readUntilCommaOrClose 按深度跳过;
 *  - Materializer 收到没有对应节点类型档案(NodeKind)的表达式 -> 抛异常;
 *  - DefinitionCache 定位不到定义版本 -> 抛异常; processInst 委托 mapper。
 *
 * 装配口径: mapper 用 {@code @Mock}(不打桩就是 null, 正好是"查不到这一版"), 缓存/物化器用
 * {@code @InjectMocks} 按构造器装配; DefinitionLoader 与 NodeKinds 要跑**真身**(本类钉的正是
 * 它们自己的判定), 所以是 {@code @Spy} —— 同样由 Mockito 纳管并注进构造器。
 */
@RunWith(MockitoJUnitRunner.class)
public class EngineDefensiveTest {

    @Mock
    private ProcessDefinitionMapper defMapper;
    @Mock
    private ProcessInstMapper procMapper;
    @Spy
    private DefinitionLoader loader = new DefinitionLoader();
    @InjectMocks
    private DefinitionCache cache;

    @Mock
    private NodeInstMapper nodeMapper;
    /** 空档案表: 任何表达式都认领不到, 用来逼出"缺档案"那条防御。 */
    @Spy
    private NodeKinds kinds = new NodeKinds(Collections.<NodeKind>emptyList());
    @InjectMocks
    private Materializer materializer;

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
        materializer.materializeNode(null, new DslParser.Leaf("x"), null, false);
    }

    /** find 不打桩 = mock 默认回 null, 正是"这个 defId:version 库里没有"。 */
    @Test(expected = ProcessException.class)
    public void definitionCacheMissingVersionThrows() {
        cache.definition("ghost", 1);
    }

    @Test
    public void definitionCacheProcessInstDelegates() {
        ProcessInstEntity row = new ProcessInstEntity();
        row.setProcInstId("P9");
        when(procMapper.findById("P9")).thenReturn(row);
        Assert.assertSame(row, cache.processInst("P9"));
    }
}
