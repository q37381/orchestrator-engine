package com.tesla.ities.process.unit;

import com.tesla.ities.process.util.Errors;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * {@link Errors} 的口径: 摘要一句含"语境(定位): 类名: 消息"、消息为 null 也不空、并发包装先拆、
 * 从 result 桶/JSON 都能读回那句(没记 = null)。
 *
 * 全是静态工具方法, 没有可替身的协作者, 本类只用统一的 {@code MockitoJUnitRunner} 口径跑。
 */
@RunWith(MockitoJUnitRunner.class)
public class ErrorsTest {

    @Test
    public void describeCarriesContextLocatorAndClassName() {
        Assert.assertEquals("action 执行异常(node=x/action=a): java.lang.IllegalStateException: boom",
                Errors.describe(new IllegalStateException("boom"), "action 执行异常", "node=x/action=a"));
        // 只给语境 / 只给定位 / 都不给
        Assert.assertEquals("驱动流程异常: java.lang.IllegalStateException: boom",
                Errors.describe(new IllegalStateException("boom"), "驱动流程异常"));
        Assert.assertEquals("node=x: java.lang.IllegalStateException: boom",
                Errors.describe(new IllegalStateException("boom"), null, "node=x"));
        Assert.assertEquals("java.lang.IllegalStateException: boom",
                Errors.describe(new IllegalStateException("boom")));
    }

    @Test
    public void nullMessageStillReadableAndNullSafe() {
        // NPE 这类没消息的异常: 旧写法(getMessage())落库就是 null, 现在至少还有类名
        Assert.assertEquals("java.lang.NullPointerException", Errors.describe(new NullPointerException()));
        Assert.assertNull(Errors.describe(null));
    }

    @Test
    public void concurrentWrapperIsUnwrapped() {
        Throwable real = new IllegalStateException("worker boom");
        Assert.assertSame(real, Errors.unwrap(new ExecutionException(real)));
        Assert.assertSame(real, Errors.unwrap(real));   // 不是包装类就原样回
        Assert.assertEquals("并行子任务异常: java.lang.IllegalStateException: worker boom",
                Errors.describe(new ExecutionException("pooled", real), "并行子任务异常"));
    }

    @Test
    public void readFromResultBucketAndJson() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("rows", 3);
        Assert.assertNull(Errors.readFrom(res));
        res.put(Errors.KEY, "节点驱动异常(node=x): java.lang.IllegalStateException: boom");
        Assert.assertEquals("节点驱动异常(node=x): java.lang.IllegalStateException: boom", Errors.readFrom(res));
        Assert.assertEquals(Errors.readFrom(res), Errors.readFrom("{\"rows\":3,\"__error\":\""
                + "节点驱动异常(node=x): java.lang.IllegalStateException: boom\"}"));

        Assert.assertNull(Errors.readFrom(Collections.<String, Object>emptyMap()));
        Assert.assertNull(Errors.readFrom((Map<String, Object>) null));
        Assert.assertNull(Errors.readFrom((String) null));
        Assert.assertNull(Errors.readFrom(""));
        Assert.assertNull("读坏 JSON 当成没记, 不抛给调用方", Errors.readFrom("not json"));
    }
}
