package com.tesla.ities.process.exception;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiFunction;

/**
 * 引擎统一异常: 所有引擎侧抛出都用本类, 携带<b>消息键</b>与<b>可选可变参数</b>。
 *
 * <ul>
 *   <li><b>messageKey</b>: 形如 {@code AU204001}(引擎占用 AU204001~AU204999), 解成中文文案放进
 *       {@code getMessage()}; 键本身同时暴露给调用方({@link #getMessageKey()}), 便于按码分支而不是按文案匹配。</li>
 *   <li><b>args</b>: 文案里的 {@code {0}}/{@code {1}} 占位符按顺序填充。数字一律先转字符串再交给
 *       MessageFormat, 免得 id/数量被 locale 加成千分位。</li>
 *   <li><b>cause</b>: 需要保留原始异常时用 {@link #ProcessException(String, Throwable, Object...)};
 *       注意第二个形参是 Throwable 时编译器会选这个重载(把 Throwable 当 cause, 后续才是 args)。</li>
 * </ul>
 *
 * <h3>单文件自包含(故意不依赖第二个类)</h3>
 * 文案来源就是 classpath 上那份 {@value #BUNDLE}(UTF-8, 默认即简体中文), 由本类<b>自己</b>懒加载,
 * 不经 Spring、不声明 Bean、不引静态桥类 —— 抛出点散布在非 Spring 管理的工具类(DslParser / Json /
 * 直接 new 的 DefinitionLoader), 拿到键就能解。取文案这件事本身<b>绝不抛异常</b>: 键缺失或 bundle
 * 读不到都降级成 {@code "键: 参数..."}, 免得二次异常盖掉真正的故障。消息在<b>构造时刻</b>解析一次并存进
 * {@code getMessage()}, 所以日志/落库摘要与栈信息不受后续 Locale 切换影响。
 *
 * <h3>宿主工程已有 ProcessException 时</h3>
 * 本文件是引擎独立编译用的, <b>可以整份删除</b>: 全仓抛出点只用两个构造签名
 * <pre>{@code
 * ProcessException(String messageKey, Object... args)
 * ProcessException(String messageKey, Throwable cause, Object... args)
 * }</pre>
 * 宿主那份同名同类只要有这两个构造就接得上。另有两件事要跟着对一下:
 * <ul>
 *   <li><b>toString 带键</b>: {@code Errors.describe} 把异常收成一句 {@code result.__error} 落库, 类型统一成
 *       ProcessException 后类名不再区分错误种类, 全靠键去查文案与按码统计。宿主若没重写 {@code toString()},
 *       摘要里就只有文案没有码。</li>
 *   <li><b>getMessageKey() / getArgs()</b>: 主干代码不用, 只有单测按它断言; 宿主类没这两个方法就要一并
 *       处理那些断言(见 {@code ProcessExceptionTest} 等)。</li>
 * </ul>
 *
 * 形态:
 * <pre>com.tesla.ities.process.exception.ProcessException[AU204402]: 流程定义版本不存在: myDef:3</pre>
 */
public class ProcessException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final Object[] NO_ARGS = new Object[0];

    /** 默认文案 bundle 的 classpath 路径(这份<b>默认</b>就是简体中文, 所以不做 locale 逐个试探)。 */
    private static final String BUNDLE = "/i18n/orchestrator-messages.properties";

    /** 键 -> 文案(带 {0} 占位符的原始 pattern); null 表示还没加载过。加载失败也回空表, 不再重试。 */
    private static volatile Map<String, String> bundle;

    /** 可替换的文案源: 消费者要接自己的国际化就设它, 传 null 恢复内置 bundle。 */
    private static volatile BiFunction<String, Object[], String> resolver;

    private final String messageKey;
    private final Object[] args;

    /** 只带键与可选参数: {@code new ProcessException("AU204401", defId)}。 */
    public ProcessException(String messageKey, Object... args) {
        super(resolve(messageKey, args));
        this.messageKey = messageKey;
        this.args = args == null ? NO_ARGS : args;
    }

    /** 带原始异常(cause 在前一位, 其余是参数): {@code new ProcessException("AU204701", e)}。 */
    public ProcessException(String messageKey, Throwable cause, Object... args) {
        super(resolve(messageKey, args), cause);
        this.messageKey = messageKey;
        this.args = args == null ? NO_ARGS : args;
    }

    /** 消息键(AU204001~AU204999), 供调用方分支与日志检索。 */
    public String getMessageKey() {
        return messageKey;
    }

    /** 填充文案用的参数(只读语义: 不要改)。 */
    public Object[] getArgs() {
        return args;
    }

    /**
     * 换掉文案来源(全局一次): 消费者想按自己的语种/词条出文案就设一个
     * {@code (key, args) -> 文案}, 返回 null 表示让内置 bundle 兜底。传 null 恢复纯内置。
     */
    public static void setResolver(BiFunction<String, Object[], String> r) {
        resolver = r;
    }

    /** 键 + args 解成文案; 任何一环失败都降级, 绝不抛。 */
    private static String resolve(String messageKey, Object[] rawArgs) {
        Object[] a = rawArgs == null ? NO_ARGS : rawArgs;
        BiFunction<String, Object[], String> r = resolver;
        if (r != null) {
            try {
                String text = r.apply(messageKey, a);
                if (text != null) {
                    return text;
                }
            } catch (RuntimeException ignore) {
                // 外部文案源炸了不该改变抛出的异常本身, 落回内置 bundle
            }
        }
        if (messageKey == null || messageKey.isEmpty()) {
            return "";
        }
        String pattern = bundle().get(messageKey);
        if (pattern == null) {
            return fallback(messageKey, a);
        }
        try {
            // 先一律转字符串再进 MessageFormat: Number 会被 locale 加成千分位(1,234,567 这种)
            Object[] strings = new Object[a.length];
            for (int i = 0; i < a.length; i++) {
                strings[i] = String.valueOf(a[i]);
            }
            return MessageFormat.format(pattern, strings);
        } catch (IllegalArgumentException e) {
            return fallback(messageKey, a);   // pattern 本身写坏了也不该盖掉原始故障
        }
    }

    /** 降级形态: {@code 键: 参数1 参数2}(无参数就只剩键) —— 参数不静默丢。 */
    private static String fallback(String messageKey, Object[] a) {
        if (a.length == 0) {
            return messageKey;
        }
        StringBuilder sb = new StringBuilder(messageKey).append(":");
        for (Object o : a) {
            sb.append(' ').append(String.valueOf(o));
        }
        return sb.toString();
    }

    /** 懒加载那份 bundle; 读不到回空表(于是所有键都走降级), 但绝不因取文案而抛。 */
    private static Map<String, String> bundle() {
        Map<String, String> b = bundle;
        if (b == null) {
            synchronized (ProcessException.class) {
                b = bundle;
                if (b == null) {
                    b = load();
                    bundle = b;
                }
            }
        }
        return b;
    }

    private static Map<String, String> load() {
        Map<String, String> m = new HashMap<>();
        try (InputStream in = ProcessException.class.getResourceAsStream(BUNDLE)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));   // 本文件按 UTF-8 存
                for (String name : p.stringPropertyNames()) {
                    m.put(name, p.getProperty(name));
                }
            }
        } catch (IOException | RuntimeException ignore) {
            // 文案读不到 = 全部走降级, 不该把流程启动/抛出这件事搞挂
        }
        return Collections.unmodifiableMap(m);
    }

    /** 类名[消息键]: 已解析文案 —— 库里那条 __error 与日志首行都用它。 */
    @Override
    public String toString() {
        return getClass().getName() + "[" + messageKey + "]: " + getMessage();
    }
}
