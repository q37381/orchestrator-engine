package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.util.Errors;

/**
 * 失败留痕的唯一入口(record-and-continue 档): 把异常收成一句可定位摘要, 记进流程级失败摘要。
 *
 * 与三级错误口径的对应关系:
 *  - veto(before 否决/guard): 抛异常, 由调用方落 FAILED, **不进本类**;
 *  - record-and-continue(驱动异常/非法返回值/补偿失败): 本类负责 describe + recordFailure;
 *  - observe-only(after 监听器/Observer): 只记日志, 不改终态, **不进本类**。
 *
 * 本类不负责日志级别与堆栈, 调用方仍按现场打 log; 这样"记什么"与"怎么打"分离, 不会各写一套摘要格式。
 */
final class FailureRecorder {

    private FailureRecorder() {
    }

    /**
     * 记录一条失败摘要并返回它(调用方可直接用于日志或 result.__error)。
     *
     * @param context  语境(与日志首词一致, 如"驱动流程异常")
     * @param location 定位(如 "process=p1/node=n1")
     */
    static String record(ProcessContext pctx, Throwable e, String context, String location) {
        String msg = Errors.describe(e, context, location);
        pctx.recordFailure(msg);
        return msg;
    }
}
