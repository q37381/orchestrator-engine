package com.tesla.ities.process.core.runtime;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.core.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 条件求值器(无状态 Spring 单例, #5): 用于 IF(...) 和 action 的 when guard。
 * 先用 VariableResolver 把 ${...} 替换成实际值(ACTIVATION 期, 读 live vars/result), 再做轻量比较求值。
 * 支持: == != > < >= <=, && || !, 括号分组, 单引号字符串字面量; 裸字面量仅 "true" 为真(其余含 false/null/任意串皆为假)。
 *
 * 生产可替换为 SpEL / Aviator / MVEL(做成 ConditionEvaluator SPI)。
 * 铁律: guard 只能读持久化的 result/config/vars, 不得在求值里查活系统(保证恢复确定性)。
 *   (params 桶不落库 -> 条件里写 ${params.x} 激活期无持久化来源, 要拿创建参数用 ${config.x})
 * 表达式里的占位符两档都解不出时**会告警**(它不会报错, 而是连同 "${...}" 原文参与字面量比较,
 * 结果通常为 false -> 静默跳过), 排查时看这条 warn。
 */
@Component
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);

    private final VariableResolver resolver;

    public ConditionEvaluator(VariableResolver resolver) {
        this.resolver = resolver;
    }

    /** 求值。ctx 提供 result/config/vars 上溯。 */
    public boolean eval(String expr, Context ctx) {
        if (expr == null || expr.trim().isEmpty()) {
            return true;
        }
        String resolved = String.valueOf(resolver.resolve(expr.trim(), ctx, VariableResolver.Phase.ACTIVATION));
        if (resolved.contains("${")) {
            log.warn("条件表达式仍含未解析占位符(按字面量比较, 结果大概为 false): expr={} resolved={}", expr, resolved);
        }
        return evalBool(resolved);
    }

    // ---- 极简递归下降求值(骨架级, 够表达 guard/IF) ----
    private boolean evalBool(String e) {
        e = e.trim();
        // 整体被一对括号包裹(含 ((...)) 层层)时脱壳再递归: topLevel 会跳过括号内的运算符,
        // 若不脱壳, "(a || b)" 这类整体包裹的子表达式会走到字面量分支被误判。
        while (isFullyWrapped(e)) {
            e = e.substring(1, e.length() - 1).trim();
        }
        int or = topLevel(e, "||");
        if (or >= 0) {
            return evalBool(e.substring(0, or)) || evalBool(e.substring(or + 2));
        }
        int and = topLevel(e, "&&");
        if (and >= 0) {
            return evalBool(e.substring(0, and)) && evalBool(e.substring(and + 2));
        }
        if (e.startsWith("!")) {
            return !evalBool(e.substring(1));
        }
        for (String op : new String[]{"==", "!=", ">=", "<=", ">", "<"}) {
            int i = topLevel(e, op);
            if (i >= 0) {
                return compare(e.substring(0, i).trim(), op, e.substring(i + op.length()).trim());
            }
        }
        String v = strip(e);
        return "true".equalsIgnoreCase(v);
    }

    /** 整串是否被一对括号从首到尾完整包裹(忽略单引号内的括号)。 */
    private boolean isFullyWrapped(String e) {
        int n = e.length();
        if (n < 2 || e.charAt(0) != '(' || e.charAt(n - 1) != ')') {
            return false;
        }
        int depth = 0;
        boolean sq = false;
        for (int i = 0; i < n; i++) {
            char c = e.charAt(i);
            if (c == '\'') {
                sq = !sq;
            } else if (!sq) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    // 首个左括号在到达末尾前就闭合了 -> 不是整体包裹(如 "(a) && (b)")
                    if (depth == 0 && i < n - 1) {
                        return false;
                    }
                }
            }
        }
        return depth == 0;
    }

    private boolean compare(String l, String op, String r) {
        String ls = strip(l), rs = strip(r);
        Double ln = num(ls), rn = num(rs);
        if (ln != null && rn != null) {
            int c = Double.compare(ln, rn);
            switch (op) {
                case "==": return c == 0;
                case "!=": return c != 0;
                case ">":  return c > 0;
                case "<":  return c < 0;
                case ">=": return c >= 0;
                case "<=": return c <= 0;
            }
        }
        boolean eq = ls.equals(rs);
        if ("==".equals(op)) {
            return eq;
        }
        if ("!=".equals(op)) {
            return !eq;
        }
        throw new ProcessException("AU204501", String.valueOf(l) + op + r);
    }

    private int topLevel(String e, String op) {
        int depth = 0;
        boolean sq = false;
        for (int i = 0; i <= e.length() - op.length(); i++) {
            char c = e.charAt(i);
            if (c == '\'') {
                sq = !sq;
            } else if (!sq && (c == '(')) {
                depth++;
            } else if (!sq && (c == ')')) {
                depth--;
            }
            if (!sq && depth == 0 && e.startsWith(op, i)) {
                return i;
            }
        }
        return -1;
    }

    private String strip(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1);
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private Double num(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }
}
