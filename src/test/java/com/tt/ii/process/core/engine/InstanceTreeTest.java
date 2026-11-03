package com.tt.ii.process.core.engine;

import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.mapper.NodeInstMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.when;

/**
 * 实例树遍历单测: 锁死"根行按声明顺序 + 深度优先含自身"这一口径。
 *
 * 这段 DFS 原来在 ProgressService/RollbackService/InterventionService 各有一份, 顺序一旦分叉,
 * 进度分母、补偿逆序、干预复位会出现难以察觉的偏差; 抽成 InstanceTree 后这里一次锁住。
 */
@RunWith(MockitoJUnitRunner.class)
public class InstanceTreeTest {

    @Mock
    private NodeInstMapper nodeMapper;

    private InstanceTree instanceTree;

    @Before
    public void setUp() {
        instanceTree = new InstanceTree(nodeMapper);
    }

    /** all = 根行按声明顺序, 每个根先自身后子节点, 深度优先。 */
    @Test
    public void allWalksRootsDepthFirstInDeclarationOrder() {
        NodeInstEntity r1 = node("r1", null);
        NodeInstEntity a = node("a", "r1");
        NodeInstEntity a1 = node("a1", "a");
        NodeInstEntity b = node("b", "r1");
        NodeInstEntity r2 = node("r2", null);

        when(nodeMapper.findRoots("P")).thenReturn(Arrays.asList(r1, r2));
        when(nodeMapper.findChildren("P", "r1")).thenReturn(Arrays.asList(a, b));
        when(nodeMapper.findChildren("P", "a")).thenReturn(Collections.singletonList(a1));
        when(nodeMapper.findChildren("P", "b")).thenReturn(Collections.<NodeInstEntity>emptyList());
        when(nodeMapper.findChildren("P", "a1")).thenReturn(Collections.<NodeInstEntity>emptyList());
        when(nodeMapper.findChildren("P", "r2")).thenReturn(Collections.<NodeInstEntity>emptyList());

        List<NodeInstEntity> rows = instanceTree.all("P");

        Assert.assertEquals(Arrays.asList(r1, a, a1, b, r2), rows);
    }

    /** subtree 含根自身; 空根返回空(与 ProgressService 原 collect 的 null 防御一致)。 */
    @Test
    public void subtreeIncludesRootAndGuardsNull() {
        NodeInstEntity root = node("g", null);
        NodeInstEntity child = node("c", "g");
        when(nodeMapper.findChildren("P", "g")).thenReturn(Collections.singletonList(child));
        when(nodeMapper.findChildren("P", "c")).thenReturn(Collections.<NodeInstEntity>emptyList());

        Assert.assertEquals(Arrays.asList(root, child), instanceTree.subtree(root, "P"));
        Assert.assertTrue(instanceTree.subtree(null, "P").isEmpty());
    }

    /** pathToRoot 含叶子自身, 沿 parentId 上溯; 行缺失即停。 */
    @Test
    public void pathToRootWalksUpUntilMissing() {
        NodeInstEntity leaf = node("leaf", "mid");
        NodeInstEntity mid = node("mid", "root");
        NodeInstEntity root = node("root", null);
        when(nodeMapper.findById("leaf")).thenReturn(leaf);
        when(nodeMapper.findById("mid")).thenReturn(mid);
        when(nodeMapper.findById("root")).thenReturn(root);

        Assert.assertEquals(Arrays.asList(leaf, mid, root), instanceTree.pathToRoot(leaf));
        Assert.assertTrue(instanceTree.pathToRoot(null).isEmpty());
    }

    private static NodeInstEntity node(String id, String parentId) {
        NodeInstEntity n = new NodeInstEntity();
        n.setNodeInstId(id);
        n.setParentId(parentId);
        return n;
    }
}
