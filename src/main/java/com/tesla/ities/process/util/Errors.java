package com.tesla.ities.process.util;

import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 失败摘要: 把异常收成**一条可读文本**, 落到实例行 result 的保留键 {@code __error}
 * (沿用 #14 口径: 失败/跳过信息不建独立列)。日志里那份完整堆栈照打不误, 库里只留一句能定位的话。
 *
 * 形态: {@code 语境(定位): 异常类名: 消息}, 例如
 *  <pre>action 执行异常(node=dbMigrate/action=a@db-replica-1:3306): java.lang.IllegalStateException: handler boom</pre>
 *  - 语境 = 引擎哪个 catch 接住的(节点驱动异常 / before 否决 / 驱动流程异常...), 与日志首词一致, 便于对照;
 *  - 定位 = 炸在哪一层哪一片, 只放 id 不放实例业务键以外的东西;
 *  - 摘要取 {@code Throwable.toString()}——消息为 null 的异常(NPE 这类)也还有类名可读,
 *   比原来只存 {@code getMessage()} 稳(旧写法会把 null 直接落进库)。
 *
 * 引擎自己抛的都是 {@code ProcessException}(带消息键), 它重写了 {@code toString()} 使摘要形如
 *  <pre>...: com.tesla.ities.process.exception.ProcessException[AU204302]: flow 引用了未定义的节点: ghost</pre>
 * ——**键必须在摘要里可见**: 类型统一成 ProcessException 之后, 类名不再区分错误种类,
 * 全靠这个键去 bundle 查文案、按码检索与统计。业务 handler 抛的异常照旧记原类名(不受影响)。
 *
 * {@link #unwrap(Throwable)} 先拆 {@code ExecutionException}/{@code CompletionException} 包装:
 * 从 Future 里捞出来的异常不该记成框架的包装类。
 */
public final class Errors {

    /** result 保留键: 失败摘要(既有键, 消费方——进度接口/守卫/前端——照旧只读这一个)。 */
    public static final String KEY = "__error";

    private Errors() {
    }

    /** 语境 + 定位都给的完整版(任一段为 null 就跳过那一段)。 */
    public static String describe(Throwable t, String context, String at) {
        if (t == null) {
            return null;
        }
        String head = context == null || context.isEmpty() ? "" : context;
        if (at != null && !at.isEmpty()) {
            head = head.isEmpty() ? at : head + "(" + at + ")";
        }
        String summary = unwrap(t).toString();
        return head.isEmpty() ? summary : head + ": " + summary;
    }

    /** 只知道炸在哪一层、没有实例定位时的简版。 */
    public static String describe(Throwable t, String context) {
        return describe(t, context, null);
    }

    /** 只要一句"类名: 消息"(语境与定位都不知道时)。 */
    public static String describe(Throwable t) {
        return describe(t, null, null);
    }

    /** 拆掉并发包装, 取真正抛出的那个异常(不是包装类就原样回)。 */
    public static Throwable unwrap(Throwable t) {
        while ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** 从 result 桶读失败摘要; 没记 = null。 */
    public static String readFrom(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        Object v = result.get(KEY);
        return v == null ? null : String.valueOf(v);
    }

    /** 从落库的 result JSON 文本读失败摘要; 空文本/没记 = null(读坏 JSON 不当"没失败")。 */
    public static String readFrom(String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) {
            return null;
        }
        try {
            return readFrom(Json.readMap(resultJson));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
