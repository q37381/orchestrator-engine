package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.model.Status;

import java.util.Map;

/**
 * 回退级监听器: 补偿(Saga 向后恢复)路径的 before/after 两切点, 只收 ProcessContext(类型即 scope)。
 *
 * 存在的理由: 补偿不走 driveRoots, 所以 setup/setdown 段与三类监听器在回退路径上一个都不触发;
 * 而补偿同样需要"现场准备"(取数据库连接信息/凭据/拓扑), 于是给回退自己一对切点。
 * 链从**定义推导**(声明在 rollbackListeners 段, 与流程级 listeners 同构), 由 RollbackService 传入 ListenerEngine。
 *
 * 切点语义(与 ProcessListener 一致):
 *  - before(ctx, config): 逐条 compensate 之前跑一次。抛异常 = **否决整轮回退**(一条都不补, 报告记 veto)。
 *  - after(ctx, outcome, config): 补偿收尾后纯通知; outcome = FAILED(有补偿失败/被否决) > STOPPED(遇不可补偿步骤停下)
 *    > SUCCESS(全部补偿完成)。异常仅记录, 不改已定结果。
 *
 * 产出往哪放(两条都可用, 差别在持久化):
 *  - {@code ctx.putResult("dbConn", conn)}: 只进流程级**内存** result 桶, 引擎从不刷回 process_inst.result ——
 *    放每轮现取的连接信息/临时凭据用这个; compensate 里 {@code ctx.resolveResult("dbConn.host")} 沿父链上溯读,
 *    action config 里也可直接写 {@code ${result.dbConn.host}}(补偿期已补激活解析)。
 *  - {@code ctx.putVar(...)}: 落 process_inst.vars(回退结束会刷库, updator_id = 本次操作人), 跨回退可见。
 *
 * 可读定义: ctx 已挂上钉死版本的流程定义(ctx.nodes()/ctx.nodeDefinition(id)/ctx.definition()),
 * 所以 before 就能拿到全量拓扑与节点 config, 不必等实例行。
 *
 * 收敛约定: 同步 inline 执行; 纯副作用监听器须自行 try/catch 吞异常, 否则会否决整轮回退。
 */
public interface RollbackListener {

    String name();

    /** 回退前置: 现取补偿要用的现场信息(连接串/凭据/拓扑)。抛异常 = 否决整轮回退。 */
    default void before(ProcessContext ctx, Map<String, Object> config) throws Exception { }

    /** 回退收尾: 纯通知, outcome 为本轮补偿的聚合结果。 */
    default void after(ProcessContext ctx, Status outcome, Map<String, Object> config) throws Exception { }
}
