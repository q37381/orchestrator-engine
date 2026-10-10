package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.runtime.ConditionEvaluator;
import com.tesla.ities.process.core.runtime.VariableResolver;
import com.tesla.ities.process.exception.ProcessException;
import org.junit.Assert;
import org.junit.Test;

/**
 * 条件求值器全分支: 数值/字符串比较、六比较符、&& || ! 与括号、裸布尔/字面量、
 * 未解析占位符按字面量比较、空表达式恒真、非数值不可比较抛异常。
 */
public class ConditionEvaluatorTest {

    private final ConditionEvaluator ev = new ConditionEvaluator(new VariableResolver());

    private boolean eval(String expr) {
        return ev.eval(expr, new ProcessContext("p"));
    }

    @Test
    public void emptyOrNullIsTrue() {
        Assert.assertTrue(eval(null));
        Assert.assertTrue(eval("   "));
    }

    @Test
    public void numericComparisons() {
        Assert.assertTrue(eval("1 < 2"));
        Assert.assertTrue(eval("2 > 1"));
        Assert.assertTrue(eval("1 == 1"));
        Assert.assertTrue(eval("1 != 2"));
        Assert.assertTrue(eval("2 >= 2"));
        Assert.assertTrue(eval("1 <= 2"));
        Assert.assertFalse(eval("3 > 4"));
        Assert.assertFalse(eval("3 <= 2"));
    }

    @Test
    public void stringComparisonsWithQuotes() {
        Assert.assertTrue(eval("'mysql' == 'mysql'"));
        Assert.assertTrue(eval("'mysql' != 'oracle'"));
        Assert.assertFalse(eval("'a' == 'b'"));
    }

    @Test
    public void booleanLogicOperators() {
        Assert.assertTrue(eval("true && true"));
        Assert.assertFalse(eval("true && false"));
        Assert.assertTrue(eval("false || true"));
        Assert.assertFalse(eval("false || false"));
        Assert.assertTrue(eval("!false"));
        Assert.assertFalse(eval("!true"));
        Assert.assertTrue(eval("(true || false) && true"));
    }

    @Test
    public void bareLiteralsEvaluatedByTruthiness() {
        Assert.assertTrue(eval("true"));
        Assert.assertFalse(eval("false"));
        Assert.assertFalse(eval("null"));
        Assert.assertTrue(eval("anything"));   // 非空非 false/null -> 真
    }

    @Test(expected = ProcessException.class)
    public void nonNumericInequalityOperatorThrows() {
        eval("'a' > 'b'");
    }

    @Test
    public void unresolvedPlaceholderComparesAsLiteral() {
        // ${vars.absent} 解不出 -> 保留原文按字面量比较, 结果 false
        Assert.assertFalse(eval("${vars.absent} == 'x'"));
    }

    @Test
    public void placeholderResolvedFromVars() {
        ProcessContext ctx = new ProcessContext("p");
        ctx.putVar("n", 5);
        Assert.assertTrue(ev.eval("${vars.n} > 3", ctx));
        Assert.assertFalse(ev.eval("${vars.n} > 9", ctx));
    }
}
