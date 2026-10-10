package com.tesla.ities.process.api;

import com.tesla.ities.process.core.model.Status;

/**
 * 执行类入口({@link ProcessFacade#createAndStart} / {@link ProcessFacade#start})的返回:
 * **流程实例 id + 调用结束时的状态(+ 失败时那一条异常摘要)**, 不可变。
 *
 * 为什么不直接返 {@code ProcessInstEntity}: 那是持久化行——auto_id 物理主键(门面约定不外露)、
 * config/vars/result 是裸 JSON 文本(调用方得自己 parse)、且对象可变(外部能 setStatus)。
 *
 * 状态口径(不承诺返回的一定是终态):
 *  - 当前引擎是**同线程 inline 驱动**, 方法返回来时通常已是终态(SUCCESS/FAILED/STOPPED);
 *  - 本次调用没接手(行不存在/已非 PENDING/被调度器或别的 owner 抢先 claim)时, status 只是当下读到的样子
 *    (running = 别人在跑; pending = 没人接手), 不另外标"是否本调用驱动";
 *  - 中途被人工 STOP/PAUSE 抢先翻走时, status 以 DB 为真值(不返引擎聚合出的那个), 故也可能不是终态;
 *  - status=null 只有一种含义: 查不到这行(不把"查不到"美化为某个状态)。
 *
 * {@link #getError()} 是流程行上那**一条**失败摘要(process_inst.result 的保留键 __error, 形如
 * "action 执行异常(node=x/action=x): java.lang.IllegalStateException: boom"): 只在坏终态时多读一次流程行,
 * 成功路径不查库; 读不到/没记就是 null。其余进度、明细仍走 {@link ProcessFacade#progress}。
 *
 * 将来若 createAndStart 改成异步驱动, 本类型无需换签名, 只是 status 常为 pending/running。
 */
public final class ProcessExecution {

    private final String procInstId;
    private final Status status;
    private final String error;

    ProcessExecution(String procInstId, Status status) {
        this(procInstId, status, null);
    }

    ProcessExecution(String procInstId, Status status, String error) {
        this.procInstId = procInstId;
        this.status = status;
        this.error = error;
    }

    /** 业务键 varchar(20): 后续进度查询/干预都用它。 */
    public String getProcInstId() {
        return procInstId;
    }

    /** 调用结束时读到的流程状态; null = 查不到这行。 */
    public Status getStatus() {
        return status;
    }

    /** 小写规范状态名(与落库/progress 同口径), 便于直接回给前端; 无状态时 null。 */
    public String getStatusName() {
        return status == null ? null : status.getStatus();
    }

    /** 是否已落到终态(SUCCESS/FAILED/SKIPPED/STOPPED)。 */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    /** 是否成功完成(SUCCESS 或 SKIPPED)。 */
    public boolean isSuccess() {
        return status != null && status.isCompletedOk();
    }

    /** 是否落到 FAILED。 */
    public boolean isFailed() {
        return status == Status.FAILED;
    }

    /** 流程行上那一条失败摘要(异常语境 + 定位 + 类名: 消息); 没失败/没读到 = null。 */
    public String getError() {
        return error;
    }

    /** 是否记到了失败摘要(坏终态时想直接判"有没有原因"用这个, 不必自己判 null)。 */
    public boolean hasError() {
        return error != null;
    }

    @Override
    public String toString() {
        return error == null ? "proc=" + procInstId + " status=" + getStatusName()
                : "proc=" + procInstId + " status=" + getStatusName() + " error=" + error;
    }
}
