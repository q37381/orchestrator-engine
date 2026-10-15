package com.tesla.ities.process.unit;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.util.Errors;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Locale;

/**
 * 引擎统一异常与消息键映射: 键解析、参数填充、cause 保留、toString 带键、以及"取消息绝不抛"的兜底。
 *
 * 这里刻意按**键**断言而不是按中文断言: 文案是可改的展示层, 键才是调用方与日志检索的契约。
 * 只有个别处直接断中文(如 {@link #keyResolvesChineseTextFromBundle}), 用来证明 bundle 真被读到了。
 *
 * 异常自己就是被测对象(无协作者), 所以本类只用统一的 {@code MockitoJUnitRunner} 口径跑;
 * 换文案源那几处传的是 {@code setResolver} 的入参(lambda 即该 API 的正常用法), 不是测试替身。
 */
@RunWith(MockitoJUnitRunner.class)
public class ProcessExceptionTest {

    /** 主干在用的全部消息键 —— 新增抛出点必须同时补 bundle, 这条用例就是那道闸。 */
    private static final String[] ALL_KEYS = {
            "AU204001",
            "AU204101", "AU204102", "AU204103", "AU204104", "AU204105", "AU204106", "AU204107", "AU204108", "AU204109", "AU204110",
            "AU204201", "AU204202", "AU204203", "AU204204", "AU204205", "AU204206", "AU204207",
            "AU204208", "AU204209", "AU204210", "AU204211", "AU204212", "AU204213", "AU204214",
            "AU204301", "AU204302", "AU204303", "AU204304", "AU204305", "AU204306", "AU204307", "AU204308", "AU204309", "AU204310",
            "AU204401", "AU204402",
            "AU204501",
            "AU204601", "AU204602", "AU204603", "AU204604", "AU204605", "AU204606",
            "AU204607", "AU204608", "AU204609", "AU204610", "AU204611",
            "AU204701", "AU204702", "AU204703", "AU204704",
            "AU204801",
    };

    @Test
    public void keyResolvesChineseTextFromBundle() {
        ProcessException e = new ProcessException("AU204302", "ghostNode");
        Assert.assertEquals("AU204302", e.getMessageKey());
        Assert.assertEquals("flow 引用了未定义的节点: ghostNode", e.getMessage());
        Assert.assertArrayEquals(new Object[]{"ghostNode"}, e.getArgs());
    }

    /** toString 必须带键: 引擎把异常收成一句 result.__error, 类名已不再区分错误种类。 */
    @Test
    public void toStringCarriesKey() {
        ProcessException e = new ProcessException("AU204402", "myDef:3");
        Assert.assertEquals(ProcessException.class.getName() + "[AU204402]: 流程定义版本不存在: myDef:3", e.toString());
        // 落库摘要走的就是 toString(见 Errors.describe)
        Assert.assertEquals("节点驱动异常(node=x): " + ProcessException.class.getName()
                        + "[AU204402]: 流程定义版本不存在: myDef:3",
                Errors.describe(e, "节点驱动异常", "node=x"));
    }

    @Test
    public void nullKeyYieldsEmptyTextWithoutThrowing() {
        Assert.assertEquals("", new ProcessException(null, "a").getMessage());
        Assert.assertNull(new ProcessException(null, "a").getMessageKey());
    }

    /** 键没配进 bundle: 降级成"键: 参数", 参数不静默丢, 也不二次抛异常盖掉原始故障。 */
    @Test
    public void unknownKeyFallsBackToKeyWithArgs() {
        Assert.assertEquals("AU204999: a b", new ProcessException("AU204999", "a", "b").getMessage());
        Assert.assertEquals("AU204998", new ProcessException("AU204998").getMessage());
    }

    /** MessageFormat 会把 Number 按 locale 加成千分位, 这里必须看到的是原值。 */
    @Test
    public void numericArgsAreNotGrouped() {
        String msg = new ProcessException("AU204205", "deep", 1234567).getMessage();
        Assert.assertTrue(msg, msg.contains("1234567"));
        Assert.assertFalse("不该出现千分位: " + msg, msg.contains("1,234,567"));
    }

    /** cause 重载: 原始异常保留(栈可追), 文案里带上 cause 的信息。 */
    @Test
    public void causeIsPreserved() {
        IllegalStateException origin = new IllegalStateException("boom");
        ProcessException e = new ProcessException("AU204703", origin, "ProcessDefinition");
        Assert.assertSame(origin, e.getCause());
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("ProcessDefinition"));
    }

    @Test
    public void nullArgsTreatedAsEmpty() {
        ProcessException e = new ProcessException("AU204308", (Object[]) null);
        Assert.assertEquals(0, e.getArgs().length);
        Assert.assertEquals("监听器声明缺少 name", e.getMessage());
    }

    /** 每个在用的键都要能在 bundle 里解出真文案(解不出会退化成"键: 参数", 于是能被发现)。 */
    @Test
    public void everyUsedKeyResolvesToBundleText() {
        for (String key : ALL_KEYS) {
            String text = new ProcessException(key, "X", "Y").getMessage();
            Assert.assertNotEquals("bundle 缺键: " + key, key, text);
            Assert.assertFalse("bundle 空文案: " + key, text.isEmpty());
            Assert.assertTrue(key + " 解出的文案不该退化成兜底格式: " + text,
                    !text.startsWith(key + ":") && !text.equals(key));
        }
    }

    /** 键格式: AU2040 段内 6 位数字, 落在 AU204001~AU204999。 */
    @Test
    public void keysMatchDeclaredRange() {
        for (String key : ALL_KEYS) {
            Assert.assertTrue(key, key.matches("AU204\\d{3}"));
            Assert.assertTrue("越界: " + key, key.compareTo("AU204001") >= 0 && key.compareTo("AU204999") <= 0);
        }
    }

    /** 消费者可以整体换掉文案源; 传 null 则恢复引擎默认那份(静态入口必须可还原, 否则用例间会串)。 */
    @Test
    public void resolverCanBeOverriddenAndRestored() {
        try {
            ProcessException.setResolver((key, args) ->
                    "AU204302".equals(key) ? "自定义文案 " + args[0] : null);   // 返回 null = 这个键我不认, 让内置兜底
            Assert.assertEquals("自定义文案 n1", new ProcessException("AU204302", "n1").getMessage());
            Assert.assertEquals("流程定义版本不存在: myDef:3", new ProcessException("AU204402", "myDef:3").getMessage());
            // 外部文案源自己炸了也不该改变抛出的异常: 落回内置 bundle
            ProcessException.setResolver((key, args) -> {
                throw new IllegalStateException("resolver boom");
            });
            Assert.assertEquals("流程定义版本不存在: myDef:3", new ProcessException("AU204402", "myDef:3").getMessage());
        } finally {
            ProcessException.setResolver(null);
        }
        Assert.assertEquals("flow 引用了未定义的节点: n1", new ProcessException("AU204302", "n1").getMessage());
    }

    /** 内置那份 bundle 就是默认语种(中文), 所以宿主 JVM 是 en 也照出中文, 不做 locale 逐个试探。 */
    @Test
    public void defaultBundleIsLocaleIndependent() {
        Locale origin = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            Assert.assertEquals("非分片 action 没有分片 payload, 无法取值",
                    new ProcessException("AU204001").getMessage());
        } finally {
            Locale.setDefault(origin);
        }
    }
}
