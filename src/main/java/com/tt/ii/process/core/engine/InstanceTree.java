package com.tt.ii.process.core.engine;

import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.mapper.NodeInstMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 实例树遍历归口(**无状态, 线程安全**): 根行 + 深度优先子树 + 叶子到根的父链。
 *
 * 前身这段 DFS 在 {@link ProgressService} / {@link RollbackService} / {@link InterventionService} 各抄一份
 * (collect/allNodes/tree), 三处顺序必须一致却没有任何机制保证; 现在只留一份, 查询/补偿/干预共用同一走查口径。
 *
 * 口径(与拆分前逐字一致):
 *  - 根行按声明顺序(auto_id 升序), 每个根先自身后子节点, 深度优先;
 *  - {@link #subtree} 含根自身(命名容器的小计要把它自己算进去);
 *  - {@link #pathToRoot} 含叶子自身, 行缺失即停(干预复位沿途容器同口径);
 *  - 入参 null 一律返回空结果, 不抛。
 */
@Component
public class InstanceTree {

    private final NodeInstMapper nodeMapper;

    public InstanceTree(NodeInstMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    /** 某流程的整棵实例树: 根行按声明顺序, 深度优先(含自身)。 */
    public List<NodeInstEntity> all(String procInstId) {
        List<NodeInstEntity> acc = new ArrayList<>();
        for (NodeInstEntity root : nodeMapper.findRoots(procInstId)) {
            collect(root, acc, procInstId);
        }
        return acc;
    }

    /** 某根的子树(含自身), 深度优先。 */
    public List<NodeInstEntity> subtree(NodeInstEntity root, String procInstId) {
        List<NodeInstEntity> acc = new ArrayList<>();
        collect(root, acc, procInstId);
        return acc;
    }

    /** 叶子到根的父链(含自身); 行缺失即停。 */
    public List<NodeInstEntity> pathToRoot(NodeInstEntity leaf) {
        List<NodeInstEntity> chain = new ArrayList<>();
        String cur = leaf == null ? null : leaf.getNodeInstId();
        while (cur != null) {
            NodeInstEntity node = nodeMapper.findById(cur);
            if (node == null) {
                break;
            }
            chain.add(node);
            cur = node.getParentId();
        }
        return chain;
    }

    private void collect(NodeInstEntity n, List<NodeInstEntity> acc, String procInstId) {
        if (n == null) {
            return;
        }
        acc.add(n);
        for (NodeInstEntity child : nodeMapper.findChildren(procInstId, n.getNodeInstId())) {
            collect(child, acc, procInstId);
        }
    }
}
