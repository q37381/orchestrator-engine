package com.tt.ii.process.core.engine;

/**
 * 走查档(**类型语义级**): 容器类型写明自己要哪一档走查——
 *  <li>{@link #SERIAL}: 声明顺序逐个走查(取消闸门 -> 定位驱动 -> severity 聚合 -> 收敛判定);</li>
 *  <li>{@link #FANOUT}: 逐项备料后扇出到对应池, 交 {@link WalkLoop} join 汇聚
 *      (FAIL_FAST 只取消尚未开始的排队任务, 不强行中断已在跑的)。</li>
 *
 * 只给"档由**类型**写死"的层用(GROUP 恒串行 / PARALLEL 恒扇出, 写在各自档案的 drive 声明里);
 * lane/action 两层的档是**行声明**(NodeDef.laneExecution / actionExecution)的事实,
 * 由 {@link LayerWalker} 内部派生, 不走本枚举。
 */
enum WalkMode {
    SERIAL,
    FANOUT
}
