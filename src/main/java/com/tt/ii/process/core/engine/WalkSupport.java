package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.define.ActionDef;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.NodeInstEntity;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 走查层的小件(**纯函数, 无状态**): 定义侧空值回落 / 有效键 + 行状态判定。
 *
 * 前身这些判定散在 LayerWalker 的 hasNonTerminal / hasSuccess / hasNonTerminalRows / hasNonTerminalRoots /
 * hasSuccessRows / hasSuccessRoots 六七个近似重载里, 段重臂、收尾判定与 action 级段各抄一份。
 * 现在只留"动作行"与"节点行"两组口径, 三处共用同一份, 不再各说各话。
 */
final class WalkSupport {

    private WalkSupport() {
    }

    /** 段声明未写(=null)时回落空列表(动作段与节点引用段共用)。 */
    static <T> List<T> orEmpty(List<T> v) {
        return v == null ? Collections.<T>emptyList() : v;
    }

    /** 定义侧有效键 = id > handler(段行按它定位; 主段重复键只物化一行)。 */
    static String keyOf(ActionDef ad) {
        return ad.getId() != null ? ad.getId() : ad.getHandler();
    }

    /** 定义侧有效键集合(去重: 行数判据按定义键计)。 */
    static Set<String> keysOf(List<ActionDef> defs) {
        Set<String> keys = new HashSet<>();
        for (ActionDef ad : defs) {
            keys.add(keyOf(ad));
        }
        return keys;
    }

    /** 是否声明了 action 级段(定义侧判据): 级 setup/setdown 段任一非空即为真(装载校验已保证同挂成对)。 */
    static boolean hasActionSegments(ActionDef ad) {
        return ad != null && (!orEmpty(ad.getSetup()).isEmpty() || !orEmpty(ad.getSetdown()).isEmpty());
    }

    /** 段内是否存在非终态行(本轮将重跑; status 缺省/损坏防御为"会重跑", 与走查兜底同向)。 */
    static boolean hasNonTerminal(List<ActionInstEntity> rows) {
        for (ActionInstEntity a : rows) {
            Status st = Status.of(a.getStatus());
            if (st == null || !st.isTerminal()) {
                return true;
            }
        }
        return false;
    }

    /** 段内是否存在 success 行(现场已撤的信号)。 */
    static boolean hasSuccess(List<ActionInstEntity> rows) {
        for (ActionInstEntity a : rows) {
            if (Status.SUCCESS.getStatus().equals(a.getStatus())) {
                return true;
            }
        }
        return false;
    }

    /** 节点行 [from, to) 是否存在非终态行(status 缺省/损坏防御为"会重跑", 与走查兜底同向)。 */
    static boolean hasNonTerminalRows(List<NodeInstEntity> rows, int from, int to) {
        for (int i = from; i < to && i < rows.size(); i++) {
            Status st = Status.of(rows.get(i).getStatus());
            if (st == null || !st.isTerminal()) {
                return true;
            }
        }
        return false;
    }

    /** 节点行 [from, to) 是否存在 success 行(现场已撤的信号, 与 setdown 段行同口径)。 */
    static boolean hasSuccessRows(List<NodeInstEntity> rows, int from, int to) {
        for (int i = from; i < to && i < rows.size(); i++) {
            if (Status.SUCCESS.getStatus().equals(rows.get(i).getStatus())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收尾轮"已开工"判据(节点行): 缺行/未开工(CREATED)/已终态 都不再驱动——只有真正在途的行才收尾。
     * 根序列与容器子节点层的收尾轮共用本谓词, 两处不再各写一份。
     */
    static boolean started(NodeInstEntity row) {
        Status st = row == null ? null : Status.of(row.getStatus());
        return st != null && st != Status.CREATED && !st.isTerminal();
    }
}
