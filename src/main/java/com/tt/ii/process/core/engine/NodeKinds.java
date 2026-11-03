package com.tt.ii.process.core.engine;

import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.model.NodeType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点类型档案({@link NodeKind})的**唯一**注册表: 一次注册, 两处分发。
 *  <li>{@link Materializer} 按**表达式类**分发物化(建行时用, 见 {@link #ofExpr});</li>
 *  <li>{@link NodeDriver} 按**实例行 type 列**分发驱动(驱动时用, 见 {@link #ofType})。</li>
 *
 * 装配口径: 档案一律 {@code @Component} 并由 Spring 集合注入进来——**新增类型忘了注解不是"少一种能力"而是起不来**
 * (上下文里一个 NodeKind Bean 都没有时, List 注入直接缺 Bean 失败), 比旧的两个平行注册表漏注册到驱动期才发现早得多。
 * 同一类型/同一表达式被两份档案认领也当场拒(AU204309): 后注册的静默覆盖前一份是最难查的那类装配错。
 *
 * 预留位不报错: {@code NodeType.WHILE} 目前没有档案(需 visit_count + 迭代物化), 所以本类不强求枚举全覆盖,
 * 只在真正取用不到时按老口径报 AU204306/AU204304。
 */
@Component
public class NodeKinds {

    private final Map<NodeType, NodeKind> byType = new EnumMap<>(NodeType.class);
    private final Map<Class<? extends DslParser.Expr>, NodeKind> byExpr = new HashMap<>();

    public NodeKinds(List<NodeKind> kinds) {
        for (NodeKind kind : kinds) {
            register(kind);
        }
    }

    private void register(NodeKind kind) {
        NodeKind sameType = byType.put(kind.type(), kind);
        NodeKind sameExpr = byExpr.put(kind.exprType(), kind);
        if (sameType != null || sameExpr != null) {
            throw new ProcessException("AU204309", kind.type().getStatus());
        }
    }

    /** 按节点类型取档案(驱动分发); 无档案(如预留的 WHILE)当场报清楚。 */
    public NodeKind ofType(NodeType type) {
        NodeKind kind = byType.get(type);
        if (kind == null) {
            throw new ProcessException("AU204306", type);
        }
        return kind;
    }

    /** 按表达式类型取档案(物化分发); 定义里出现引擎不认的形状就报清楚(不回落默认物化)。 */
    public NodeKind ofExpr(DslParser.Expr expr) {
        NodeKind kind = byExpr.get(expr.getClass());
        if (kind == null) {
            throw new ProcessException("AU204304", expr.getClass().getSimpleName());
        }
        return kind;
    }

    /** 已注册的档案份数(冒烟/回归用它确认"每种在用的类型都真有人认领")。 */
    public int size() {
        return byType.size();
    }
}
