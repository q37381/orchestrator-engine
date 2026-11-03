package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.model.Status;

/**
 * 串行走查的一步(层差异的**唯一入口**): 第 i 项怎么定位并驱动、坏了要不要叫停剩余项、
 * 每步要不要查取消闸门、提前结束时清理哪些已物化行。
 *
 * 前身是 {@code SerialSpec} 的四个 lambda: 断点只能落在合成方法上, 栈里是 lambda$walkXxx$0,
 * "第 3 条 lane 为什么没跑"要先反推闭包来自哪一层。现在每一层一份**具名实现**(字段即现场),
 * 走查模板只认这四个方法——层与层的相似行为因此在同一个接口上对齐, 差异只剩各层重写的那一两个方法。
 *
 * 默认值 = 该层在这四个维度上的"无行为": 不收敛 / 不查闸门 / 无剩余行可清理。
 */
interface WalkStep {

    /** 定位并驱动第 i 项(定位/补物化也在实现里做——"执行到才建")。 */
    Status drive(int index);

    /** 第 index 项坏到要不要叫停剩余项(判的是**本项终态**, 不是聚合值)。 */
    default boolean stops(Status outcome, int index) {
        return false;
    }

    /** 取消闸门(每步判; 顶层与并行档恒 false)。 */
    default boolean cancelled() {
        return false;
    }

    /** 提前结束(取消/收敛)时清理 [from, 末尾) 的剩余项; 只有持已物化行的 action 层覆盖。 */
    default void drainFrom(int from) {
    }
}
