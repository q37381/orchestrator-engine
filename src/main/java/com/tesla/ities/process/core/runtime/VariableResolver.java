package com.tesla.ities.process.core.runtime;

import com.tesla.ities.process.core.context.Context;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 占位符解析器(#5 无状态 Spring 单例)。引擎**不写死任何业务键名**: "按某个运行期值挑一份片段"
 * 由通用的**间接键**(路径里再嵌占位符)表达, 早期 dialect/dialects 保留名已取消。
 *
 * 规则: ${A.B.C}, 桶内点号 = 向下嵌套。首 token 在保留作用域集(vars/params/config/result + 四个锁定层)
 * 则由引擎解析; **不在保留集一律当作外来模板语法**(${table}、${srcDb}、handler 自用的任何写法),
 * 原样保留、不报错 —— 引擎不替业务定性。保留写法取不到值同样原样保留(只由 findUnresolved 报出)。
 *  - vars.*   : 流程级变量空间(运行期可读可写)。
 *  - params.* : 创建输入, 当前层向上上溯。**引擎不持久化 params 桶**: 物化期(PLAN)从 baseVars 解(创建参数已并入
 *              流程 vars 列, 所以任何一次驱动都解得出并烙成字面量); 激活期只能读到当下驱动里内存写入的值。
 *  - config.* : 各层落库的配置桶, 当前层向上上溯(innermost 胜)——流程层的 config 就是创建输入, 子层直接可读。
 *  - result.* : 结果桶, 当前层向上上溯(innermost 胜)。
 *  - process/node/lane/action.<bucket>.* : 锁定层级。
 *
 * 间接键: 占位符路径里可再嵌占位符, 由内向外先插值、再按合成后的路径取值:
 *   ${vars.dialects.${vars.dialect}.lockSql}   多方言片段集中声明在 vars
 *   ${config.sqlByType.${vars.dbKind}}         片段就近声明在本层 config, 字典键名与指针变量名都由使用者定
 *   ${vars.limits.${params.env}.qps}           指针也可以是创建参数/上一层 result(任何可解析的占位符)
 * 插值只发生在最外层轮次(FRAGMENT 轮不再插值), 所以"插值 -> 取值 -> 填值"的链条深度恒定, 自引用不会递归。
 * 指针变量此刻没有值(如 dialect 待运行期检测) -> 整条占位符原样保留, 选支因而天然落到激活期。
 * **部分解析**: 指针有值但外层取不到值时(${config.${vars.a}.table} 中 a=xxx 而 config.xxx.table 不存在),
 * 回写的是已插值的 "${config.xxx.table}" 而不是原文 —— 指针在解算那一刻就固化, 后续不再依赖 vars.a 当时的值。
 *
 * 取到的值本身可能仍是模板(选中那支片段里带 ${vars.table}): 外层轮对其再填一轮(FRAGMENT 轮)。
 * FRAGMENT 轮的唯一区别是**不再插值、不再二次填值**(故深度恒定、自引用不递归); 两档对解不出的值都是原样保留。
 *
 * 两档时机(Phase):
 *  - PLAN: ctx 传 null, vars/params 用入参 baseVars(创建期初始 vars); result.* 与 config.* 返回 null
 *          (原样保留, 留待激活期 —— config 要沿上下文链上溯, 无 ctx 就无从起算)。
 *  - ACTIVATION: ctx 传当前上下文, vars 用运行期 live vars(handler 已 putVar), result.* 与 config.* 正常解析。
 *
 * **实际边界**(重要): 区分"常量"与"活引用"的不是桶名, 而是**物化时刻能否解出**——
 *  - PLAN 解得出 -> 烙成字面量落进 config 列, 此后 vars 怎么变都不会回头影响该行(激活期再走一遍也是 no-op);
 *  - PLAN 解不出 -> 占位符原样留在列里, 该值退化为活引用, 每次执行现取(方言靠前置步骤检测出来就属于这一类)。
 * 所以 vars 的"替换字面量"本意在"物化即可解"前提下成立; 依赖它运行期现取时, 用 findUnresolved 把残留拎出来。
 *
 * 无状态: baseVars 作为方法入参, 不持有字段 -> 可做单例、可注入、可测。
 */
@Component
public class VariableResolver {

    public enum Phase { PLAN, ACTIVATION }

    /**
     * 解析轮次 —— **存在的唯一理由是限定递归深度**, 删掉它就没了屏障:
     *  - FULL(外层): 允许路径插值(间接键) + 对取到的值再填一轮;
     *  - FRAGMENT(内层填值): 既不插值也不再填值。
     * 若 FRAGMENT 也允许填值, vars={a:"${vars.b}", b:"${vars.a}"} 就是无限递归;
     * 若它允许插值, 片段里写 "X ${config.sqlByType.${vars.dbKind}.lock}"(指向自己)也是无限递归
     * —— 两者都由"内层只走一跳、结果再不对表"斩断, 于是链条深度恒定 2。两档对解不出的值都是原样保留。
     */
    private enum Pass { FULL, FRAGMENT }

    /** ACTIVATION 便捷入口(baseVars 传 null, 用 ctx live vars)。 */
    public Object resolve(Object raw, Context ctx, Phase phase) {
        return resolve(raw, ctx, phase, null);
    }

    public Object resolve(Object raw, Context ctx, Phase phase, Map<String, Object> baseVars) {
        return resolve(raw, ctx, phase, baseVars, Pass.FULL);
    }

    private Object resolve(Object raw, Context ctx, Phase phase, Map<String, Object> baseVars, Pass pass) {
        if (raw instanceof String) {
            return resolveString((String) raw, ctx, phase, baseVars, pass);
        }
        if (raw instanceof Map) {
            Map<?, ?> src = (Map<?, ?>) raw;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : src.entrySet()) {
                out.put(String.valueOf(e.getKey()), resolve(e.getValue(), ctx, phase, baseVars, pass));
            }
            return out;
        }
        if (raw instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) raw) {
                out.add(resolve(o, ctx, phase, baseVars, pass));
            }
            return out;
        }
        return raw;
    }

    private Object resolveString(String s, Context ctx, Phase phase, Map<String, Object> baseVars, Pass pass) {
        if (s.startsWith("${") && closing(s, 0) == s.length() - 1) {
            String path = interpolateIfOuter(s.substring(2, s.length() - 1).trim(), ctx, phase, baseVars, pass);
            Object v = lookup(path, ctx, phase, baseVars, pass);
            return v == null ? "${" + path + "}" : v;   // 未解析 -> 按已插值的路径原样回写
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf("${", i);
            if (start < 0) {
                sb.append(s, i, s.length());
                break;
            }
            sb.append(s, i, start);
            int end = closing(s, start);
            if (end < 0) {
                sb.append(s, i, s.length());
                break;
            }
            String path = interpolateIfOuter(s.substring(start + 2, end).trim(), ctx, phase, baseVars, pass);
            Object v = lookup(path, ctx, phase, baseVars, pass);
            sb.append(v == null ? "${" + path + "}" : String.valueOf(v));
            i = end + 1;
        }
        return sb.toString();
    }

    /** 外层轮才插值路径(间接键); 内层填值轮原样使用, 以此限定递归深度。 */
    private String interpolateIfOuter(String expr, Context ctx, Phase phase, Map<String, Object> baseVars, Pass pass) {
        return pass == Pass.FULL ? interpolate(expr, ctx, phase, baseVars) : expr;
    }

    /**
     * 取值 + 外层轮把值再填一轮。入参 path 已是插值后的最终路径;
     * null = 解不出(调用方按 "${path}" 回写, 保留已插值部分)。
     */
    private Object lookup(String path, Context ctx, Phase phase, Map<String, Object> baseVars, Pass pass) {
        Object v = lookupPath(path, ctx, phase, baseVars);
        if (v == null) {
            return null;
        }
        return pass == Pass.FULL ? resolve(v, ctx, phase, baseVars, Pass.FRAGMENT) : v;
    }

    /** 把路径里内嵌的 ${...} 换成其值; 内层取不到则那一段原样带着(外层整体大概率也失配 -> 按部分解析回写)。 */
    private String interpolate(String expr, Context ctx, Phase phase, Map<String, Object> baseVars) {
        if (expr.indexOf("${") < 0) {
            return expr;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            int start = expr.indexOf("${", i);
            if (start < 0) {
                sb.append(expr, i, expr.length());
                break;
            }
            sb.append(expr, i, start);
            int end = closing(expr, start);
            if (end < 0) {
                sb.append(expr.substring(start));
                break;
            }
            Object v = lookup(expr.substring(start + 2, end).trim(), ctx, phase, baseVars, Pass.FRAGMENT);
            sb.append(v == null ? expr.substring(start, end + 1) : String.valueOf(v));
            i = end + 1;
        }
        return sb.toString();
    }

    /** 锁定层前缀(定位到该层上下文后按其桶取值)。 */
    private static final String[] LEVELS = {"process.", "node.", "lane.", "action."};

    /** 引擎认识的占位符前缀: 只有这些开头的残留算"该解而没解出来"。 */
    private static final String[] RESERVED = {"vars.", "params.", "config.", "result.",
            "process.", "node.", "lane.", "action."};

    /** 按已合成的路径取值; null = 不属于引擎语法, 或本阶段取不到(两种都由调用方原样保留)。 */
    private Object lookupPath(String expr, Context ctx, Phase phase, Map<String, Object> baseVars) {
        Map<String, Object> base = baseVars == null ? EMPTY : baseVars;
        if (expr.startsWith("vars.")) {
            return nested(effectiveVars(ctx, phase, base), expr.substring("vars.".length()));
        }
        if (expr.startsWith("result.")) {
            if (phase == Phase.PLAN) {
                return null;        // 留待激活期
            }
            return ctx == null ? null : ctx.resolveResult(expr.substring("result.".length()));
        }
        if (expr.startsWith("params.")) {
            if (phase == Phase.PLAN) {
                return nested(base, expr.substring("params.".length())); // 创建参数已并入 baseVars
            }
            return ctx == null ? null : ctx.resolveParams(expr.substring("params.".length()));
        }
        if (expr.startsWith("config.")) {
            return ctx == null ? null : configFrom(ctx, expr.substring("config.".length()));
        }
        for (String level : LEVELS) {
            if (expr.startsWith(level)) {
                Context target = locate(ctx, level.substring(0, level.length() - 1));
                String rest = expr.substring(level.length());
                if (target == null) {
                    return null;
                }
                if (rest.startsWith("result.")) {
                    return target.resolveResult(rest.substring("result.".length()));
                }
                if (rest.startsWith("params.")) {
                    return target.resolveParams(rest.substring("params.".length()));
                }
                if (rest.startsWith("config.")) {
                    return bucket(target.config(), rest.substring("config.".length()));
                }
                if (rest.startsWith("vars.")) {
                    return nested(target.vars(), rest.substring("vars.".length()));
                }
            }
        }
        // 首 token 不在保留集: 不是引擎的写法(业务自己的模板语法), 原样交还, 不定罪不报错
        return null;
    }

    private static final Map<String, Object> EMPTY = new LinkedHashMap<>();

    /**
     * 解析完成后残留的占位符路径(如 "sql"、"conn.host"、"items[0]")。空 = 已全部定型为字面量。
     * **只报引擎该解而没解出的**(首 token 在保留集), ${table} 这类外来模板不算残留、不报警。
     * 用途: 把"本阶段解不出来"从静默变成可见(NodeDriver 写 result.__unresolved + 告警)。
     */
    public List<String> findUnresolved(Object node) {
        List<String> out = new ArrayList<>();
        collectUnresolved(node, "", out);
        return out;
    }

    /** 串里是否存在"保留写法但没解出来"的占位符; 外来模板与不成对的 ${ 不算。 */
    private boolean hasEnginePlaceholder(String s) {
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf("${", i);
            if (start < 0) {
                return false;
            }
            int end = closing(s, start);
            if (end < 0) {
                return false;
            }
            String expr = s.substring(start + 2, end).trim();
            for (String r : RESERVED) {
                if (expr.startsWith(r)) {
                    return true;
                }
            }
            i = end + 1;
        }
        return false;
    }

    private void collectUnresolved(Object node, String path, List<String> out) {
        if (node instanceof String) {
            if (hasEnginePlaceholder((String) node)) {
                out.add(path.isEmpty() ? "<root>" : path);
            }
            return;
        }
        if (node instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                String key = String.valueOf(e.getKey());
                collectUnresolved(e.getValue(), path.isEmpty() ? key : path + "." + key, out);
            }
            return;
        }
        if (node instanceof List) {
            List<?> l = (List<?>) node;
            for (int i = 0; i < l.size(); i++) {
                collectUnresolved(l.get(i), path + "[" + i + "]", out);
            }
        }
    }

    /**
     * 桶内取值: 先平铺精确命中(兼容键名本身带点), 未命中再按点号下钻嵌套。
     * 与 vars 的下钻语义一致 —— 字典/嵌套结构都可直接按路径选, 键名对引擎透明。
     */
    private Object bucket(Map<String, Object> root, String path) {
        Object v = root.get(path);
        return v != null ? v : nested(root, path);
    }

    /**
     * ${config.*}: 从本层沿父链上溯, innermost 胜(与 ${result.*}/${params.*} 同口径);
     * 每一层都先平铺精确取、未命中再点号下钻。流程层的 config(= 创建输入)因此对子层可读。
     * 锁定层写法(${process.config.x} 等)不走这里 —— 锁定了层就只读那一层的本桶, 不再上溯。
     */
    private Object configFrom(Context ctx, String path) {
        for (Context c = ctx; c != null; c = c.parent()) {
            Object v = bucket(c.config(), path);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** PLAN(ctx=null)用入参 baseVars; ACTIVATION 用运行期 live vars(上溯到 ProcessContext)。 */
    private Map<String, Object> effectiveVars(Context ctx, Phase phase, Map<String, Object> baseVars) {
        if (phase == Phase.PLAN || ctx == null) {
            return baseVars == null ? EMPTY : baseVars;
        }
        Map<String, Object> live = ctx.vars();
        return live == null ? (baseVars == null ? EMPTY : baseVars) : live;
    }

    private Context locate(Context ctx, String level) {
        Context c = ctx;
        while (c != null) {
            String simple = c.getClass().getSimpleName().toLowerCase();   // e.g. processcontext
            if (simple.startsWith(level)) {
                return c;
            }
            c = c.parent();
        }
        return null;
    }

    /** start 处的 "${" 匹配的 '}' 下标(容忍内嵌占位符); 不闭合返回 -1。 */
    private static int closing(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            if (s.startsWith("${", i)) {
                depth++;
                i++;
            } else if (s.charAt(i) == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(seg);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }
}
