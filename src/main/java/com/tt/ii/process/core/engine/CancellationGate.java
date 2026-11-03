package com.tt.ii.process.core.engine;

/**
 * 取消闸门(角色接口): "要不要停"的唯一判据——当场读 process_inst.status, 不留本机内存状态
 * (多节点部署下本机不可信, 与既有口径一致)。{@link NodeDriver} 与 {@link ActionRunner} 各自经
 * CancellationRegistry 实现本接口, 走查层类只面向这三个字, 不再持有整个驱动门面。
 */
interface CancellationGate {

    /** 本次驱动所属流程是否已被取消(人工 STOP/PAUSE 落库即生效)。 */
    boolean isCancelled(DriveContext dc);
}
