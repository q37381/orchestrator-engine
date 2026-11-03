package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.Context;
import com.tt.ii.process.core.define.ActionDef;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;

/**
 * action 运行期协作(角色接口): 档案与走查引擎要跑的两件事——执行单个 action
 * (解析 config -> guard -> BEFORE -> handler -> 持久化 -> AFTER)、把提前结束时没跑到的已物化行落 STOPPED/DRAIN。
 *
 * 由 {@link ActionRunner} 实现: action 从解析到落库的生命周期归它所有; 容器内的**序列走查**
 * (定位/档/收敛/drain 判定)归 {@link LayerWalker}——本接口因此只留"单项执行 + drain 落行"两个动作,
 * 不再有容器级序列入口。递归/对齐/取消各走自己的窄接口, 本接口只管 action 这一档。
 */
interface ActionExecution {

    /** 执行单个 action: 解析 config -> guard -> BEFORE -> handler -> 持久化 -> AFTER。 */
    Status runAction(DriveContext dc, Context parent,
                     ActionInstEntity action, ActionDef ad);

    /** drain 落一行: 置 STOPPED 并把 __skipReason=DRAIN 并入原有 result(不丢已持久化数据)。 */
    void markActionDrained(ActionInstEntity action, String status, String resultJson, String actor);
}
