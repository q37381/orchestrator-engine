package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.LaneEntity;

import java.util.List;

/**
 * 走查层角色接口: 档案与驱动入口声明"走查意图"的**最小 API**——由 {@link LayerWalker} 实现,
 * 经 {@link NodeEnv#walk()} 下传; 档案不再认识序列的具体类。
 *
 * 前身是 {@code SerialSequence} 继承树(基类 + 五个层类): 档案在 drive() 里直接 new 具体序列类并手工
 * 拼装其构造参数(6~11 个依赖的位置对齐参数), 序列的装配知识因此泄漏到每个调用点, 且序列不可替换、
 * 不可做测试替身。收成角色接口后: 档案只表达"我这一层怎么走"(一个 walk 单元 + 一档),
 * 装配知识(协作方/池/层规格)全部收进走查引擎。
 *
 * 口径不变量(与拆分前逐字一致, 详见各方法注释):
 *  - **骨架单一定义**: 串行模板与扇出模板各自只有一份实现(在 {@link WalkLoop} 内);
 *  - **层差异数据化**: 项集合/闸门/收敛/drain/档五个维度是层规格的字段, 不再用继承钩子表达;
 *  - **协作走方法参数回调**: env 由 NodeDriver 在驱动点现组, 构造期依赖图保持无环。
 */
interface LayerWalk {

    /**
     * 顶层根序列: setup+flow 段 [0, flowEnd) **就近声明收敛**(该根自己声明了 errorPolicy 就听它的,
     * 没声明才回落流程级全局, 且判的只是本项终态——聚合值带着更早那次的坏, 用它判会误杀没吭声的后续根);
     * setdown 段 [flowEnd, end) 是 finally 语义恒不收敛; **顶层不设取消闸门**(停止信号落在正在驱动的
     * 那条链上, 由内层与 handler 处判); 未走到的根"执行到才建", 无剩余项清理。
     * 让渡(返回合法让渡态 pending/paused/suspended)短路: flow 段任一让渡 -> 顶层 setdown 段不跑, 原状态返回(重入后继续);
     * 非法非终态(created/running)经归一为 FAILED, 按坏终态口径收尾。
     * 流程级段重臂(v1.1, 节点级同款判据的顶层镜像): 入口按"setdown 段根行存在 success + setup/flow 段存在非终态行"
     * 把顶层 setup/setdown 段的 success 根行成对复位(顶层 repeatSegments 开启时无条件复位), 让流程级"改—还原"
     * 段在显式重试后成对重跑。
     */
    Status walkRoots(DriveContext dc, NodeEnv env, List<DslParser.Expr> ordered, int flowEnd);

    /**
     * 容器子节点层: {@code SERIAL}=每步查取消闸门(命中即停并聚合 STOPPED) + 行策略收敛(GROUP);
     * {@code FANOUT}=不查闸门、按 {@link WalkLoop#fanout} 汇聚(PARALLEL)。两档的差异只在"档"这一个参数上,
     * 骨架仍是各自唯一一份模板。
     *
     * 子行"走查到第 i 个才定位"(LAZY 建一个走一个; EAGER 创建期已建, 此处只对齐); 扇出档的备料
     * (childAt)在提交线程同步做——"扇出需同时在"的物化就发生在这里; 未走到的没有行, 无剩余项清理。
     */
    Status walkChildren(DriveContext dc, NodeEnv env, NodeContext ctx, List<DslParser.Expr> items, WalkMode mode);

    /**
     * 分片 lane 层: 档由**行声明**派生(NodeDef.laneExecution, 默认 SERIAL, 本引擎内部自取);
     * 每步(串行档)查取消闸门; 串行=走查到该分片才建 lane 行并内联跑该 lane 的**回路**(三段), 
     * 并行=先建全 lane(扇出需同时在)再扇分片池 join(FAIL_FAST drain: 不取消已跑的 lane)。
     * lane 行"走查到才建", 未走到的没有行, 无剩余项清理。
     * 节点声明 segmentsFollowShard: false 时切**共享段**档(节点 setup/setdown 整节点各一次, 段行 lane 恒 null;
     * 各 lane 只跑自己的 MAIN 主体, 全部走完后共享收尾; 空分片且无历史欠账时整段跳过)——节点行仍只在
     * 共享 setdown 走完后落终态(不变式 I1 同款)。
     */
    Status walkLanes(DriveContext dc, NodeEnv env, NodeContext ctx, List<String> shardKeys);

    /**
     * 节点回路(三段编排, {@code lane} 可为 null): setup(前置) → main(主段) → setdown(收尾, finally)。
     *  - setup 段: 段内按行冻结策略收敛; 段聚合坏(FAILED/STOPPED) 则**主段整体跳过**(已物化行 drain),
     *    setdown 照跑;
     *  - main 段: 档由**行声明**派生(NodeDef.actionExecution); 唯一**持有已物化行**的段——
     *    重入: 终态行算 SUCCESS 计入聚合而不重跑、非终态行重跑(at-least-once); 提前结束(收敛/取消)给段内
     *    已物化但没跑到的剩余行落 STOPPED/DRAIN(未物化的绝不补建);
     *  - setdown 段: 恒执行(finally)——段内不收敛、不查取消闸门、恒串行;
     *  - 让渡(段内返回合法让渡态 pending/paused/suspended): 立即退出——后续段(含 setdown)不跑、剩余项不 drain、
     *    容器行不落终态, 原状态上传等重入; 坏终态(FAILED/STOPPED)优先于让渡; 非法非终态(created/running)
     *    归一为 FAILED(按坏终态口径收尾, 既不当让渡也不静默吞);
     *  - 三段 exit 聚合(坏终态优先 > 让渡 > severity)后才把容器行(lane 或非分片 node)状态收尾
     *    (不变式 I1: 主段结束不得提前落终态, 否则"主段成功后、setdown 前崩溃"重驱动会被幂等跳过,
     *    收尾段永远不再执行)。
     *  - 段重臂(v1.1): 入口先判"现场已被 setdown 撤除(存在 success 行)而本趟又要重跑(setup/main 存在非终态行)",
     *    命中则把 setup/setdown 的 success 行成对复位(success -> created)后照常走三段; 节点声明 repeatSegments 时
     *    每趟无条件复位段内全部终态行(承担幂等责任)。目的: 重试在"setdown 已还原"后重新施加 setup 现场并重新收尾。
     *  - action 级段(v1.2): 主段里"声明了级段的动作"(动作同时挂 setup/setdown)走单项电路——级 setup 段 -> 主体 -> 级 setdown 段(finally);
     *    入口定义驱动(主体行终态也进电路, 未完成的级 setdown 段由此天然补跑); 级段重臂判据同段重臂按级段收窄
     *    (段值 action-setup/action-setdown + 定义成员表, 无锚列)。
     * 分片节点每 lane 一个回路(由 walkLanes 对每 lane 各调一次); 非分片节点 lane=null 即节点自身一个回路。
     */
    Status walkCircuit(DriveContext dc, NodeEnv env, NodeContext ctx, LaneEntity lane);

    /**
     * 组回路(三段编排, v1.3): group.setup → 组内子节点序列(SERIAL 档逐个子节点) → group.setdown(finally)。
     * 组没有 MAIN 动作序列, 主体就是子节点序列的走查——段口径(收敛/闸门/让渡/聚合)与节点回路逐字一致,
     * 差异只有与子节点的物化相性两条:
     *  - **无段组零开销**: 无声明/无段的组(含匿名手写 GROUP)直接回落串行子节点走查, 不查段行;
     *  - **组行收尾本层不落**: 三段聚合原样返回, 由 NodeDriver.finishNode 一次写状态与 result 再发 after;
     *    setup 段坏 -> 子节点序列整体跳过且**不递归 drain**(子节点"走查到才建", 手里没有剩余行)。
     * 组级段重臂: 主体待跑 = 直属子节点存在非终态行或尚有子节点未物化(LAZY 下"首个子节点物化前就被停掉"
     * 手里一行都没有); repeatSegments 开关与节点级同口径。
     */
    Status walkGroupCircuit(DriveContext dc, NodeEnv env, NodeContext ctx, List<DslParser.Expr> items);
}
