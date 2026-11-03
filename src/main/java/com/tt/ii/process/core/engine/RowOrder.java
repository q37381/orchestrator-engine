package com.tt.ii.process.core.engine;

import com.tt.ii.process.domain.ActionInstEntity;

import java.util.Comparator;

/**
 * 行序比较归口(纯函数): auto_id 升序 = 声明/执行顺序, 降序 = 逆序补偿。
 *
 * 前身是 RollbackService/RestoreService 各写一条 lambda; 收成共享常量后, "补偿逆序、重放正序"
 * 这条口径只在一处定义, 也不会再出现 null auto_id 的 NPE(统一按 0 计)。
 */
final class RowOrder {

    private RowOrder() {
    }

    /** auto_id 的 null 防御: 统一按 0 计(与拆分前 nz 同口径)。 */
    static long autoId(Long v) {
        return v == null ? 0L : v;
    }

    /** 动作行按 auto_id 升序(声明/执行顺序)。 */
    static final Comparator<ActionInstEntity> ACTION_ASC =
            Comparator.comparingLong(RowOrder::autoIdOf);

    /** 动作行按 auto_id 降序(逆序补偿)。 */
    static final Comparator<ActionInstEntity> ACTION_DESC = ACTION_ASC.reversed();

    private static long autoIdOf(ActionInstEntity a) {
        return autoId(a.getAutoId());
    }
}
