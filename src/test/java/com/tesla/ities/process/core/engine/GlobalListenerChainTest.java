package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.ListenerDef;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.mapper.NodeInstMapper;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 全局节点级/action 级监听器(globalNodeListeners / globalActionListeners)的生效链回归:
 *  - 节点级: Materializer.attachCursor 对齐时把全局链 + 节点自己声明的链合并挂进行内(全局在前);
 *    控制节点(PARALLEL/IF)没有 NodeDef, 也照样走全局节点级链;
 *  - action 级: NodeDriver.runAction 用同一 mergeWithGlobals 合并(分片时每 lane 的每个 action 都走),
 *    这里直接钉合并语义(两侧都空 -> null = 切点 no-op 的既有口径)。
 *
 * 装配口径: 物化器 {@code @InjectMocks}(nodeMapper 是 @Mock —— attachCursor 只排行光标, 不落库),
 * 空档案表 NodeKinds 用 @Spy(本类不派发档案, 只需构造子有个真身可注)。
 */
@RunWith(MockitoJUnitRunner.class)
public class GlobalListenerChainTest {

    @Mock
    private NodeInstMapper nodeMapper;
    @Spy
    private NodeKinds kinds = new NodeKinds(Collections.<NodeKind>emptyList());
    @InjectMocks
    private Materializer materializer;

    private static ListenerDef listener(String name) {
        ListenerDef ld = new ListenerDef();
        ld.setName(name);
        return ld;
    }

    private static ProcessDefinition defWithGlobals(List<ListenerDef> nodeGlobals, List<ListenerDef> actionGlobals) {
        ProcessDefinition def = new ProcessDefinition();
        def.setFlow("a");
        def.setGlobalNodeListeners(nodeGlobals);
        def.setGlobalActionListeners(actionGlobals);
        return def;
    }

    private static DriveContext driveContext(ProcessDefinition def) {
        ProcessInstEntity p = new ProcessInstEntity();
        p.setProcInstId("p1");
        ProcessContext pctx = new ProcessContext("p1");
        return DriveContext.create(p, def, pctx, null, null);
    }

    private static List<ListenerDef> names(ListenerDef... ns) {
        return new ArrayList<>(Arrays.asList(ns));
    }

    private NodeInstEntity attach(ProcessDefinition def, DslParser.Expr expr) throws Exception {
        Method m = Materializer.class.getDeclaredMethod("attachCursor", DriveContext.class, NodeInstEntity.class, DslParser.Expr.class);
        m.setAccessible(true);
        NodeInstEntity row = new NodeInstEntity();
        return (NodeInstEntity) m.invoke(materializer, driveContext(def), row, expr);
    }

    /** 叶子: 全局节点级链在前, 节点自己声明的链随后。 */
    @Test
    public void leafChainMergesGlobalsAheadOfLocal() throws Exception {
        NodeDef nd = new NodeDef();
        nd.setId("a");
        nd.setListeners(names(listener("localGuard")));
        ProcessDefinition def = defWithGlobals(names(listener("globalAudit"), listener("globalGate")), null);
        def.getNodes().put("a", nd);

        List<ListenerDef> chain = attach(def, new DslParser.Leaf("a")).getListeners();
        Assert.assertEquals(Arrays.asList("globalAudit", "globalGate", "localGuard"),
                chain.stream().map(ListenerDef::getName).collect(java.util.stream.Collectors.toList()));
    }

    /** 控制节点(PARALLEL)无 NodeDef, 但配了全局节点级链照样挂上; 没配则维持空(null)。 */
    @Test
    public void controlNodeGetsGlobalChain() throws Exception {
        ProcessDefinition withGlobal = defWithGlobals(names(listener("globalAudit")), null);
        List<ListenerDef> chain = attach(withGlobal, new DslParser.Parallel()).getListeners();
        Assert.assertEquals(1, chain.size());
        Assert.assertEquals("globalAudit", chain.get(0).getName());

        ProcessDefinition without = defWithGlobals(null, null);
        Assert.assertNull("未声明全局链时控制节点维持无监听器", attach(without, new DslParser.Parallel()).getListeners());
    }

    /** 合并语义(action 级同口径): 只有一侧 -> 内容退化成那一侧(新列表, 不回写声明链); 两侧都空 -> null(no-op)。 */
    @Test
    public void mergeDegradesToSingleSideOrNull() {
        List<ListenerDef> globals = names(listener("globalAudit"));
        List<ListenerDef> locals = names(listener("localAudit"));

        Assert.assertEquals("全局为空 -> 生效链就是本地链", locals, ProcessDefinition.mergeWithGlobals(Collections.emptyList(), locals));
        Assert.assertEquals("本地为空 -> 生效链就是全局链", globals, ProcessDefinition.mergeWithGlobals(globals, null));
        Assert.assertNull("两边都没声明 -> null(no-op 口径不变)", ProcessDefinition.mergeWithGlobals(null, new ArrayList<>()));

        List<ListenerDef> merged = ProcessDefinition.mergeWithGlobals(globals, locals);
        Assert.assertEquals(Arrays.asList("globalAudit", "localAudit"),
                merged.stream().map(ListenerDef::getName).collect(java.util.stream.Collectors.toList()));
        Assert.assertEquals("合并生成新列表, 不回写污染声明链", 1, globals.size());
    }
}
