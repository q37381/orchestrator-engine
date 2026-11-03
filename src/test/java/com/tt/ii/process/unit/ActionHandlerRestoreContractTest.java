package com.tt.ii.process.unit;

import com.tt.ii.process.core.context.ActionContext;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.spi.ActionHandler;
import org.junit.Assert;
import org.junit.Test;

/**
 * {@link ActionHandler#restore} SPI 契约单测: 用匿名实现验接口默认方法的语义(Mockito mock 跑不到 default 委托)。
 *  - 不重写 restore: 默认委托 {@link ActionHandler#handle}, 于是不必为每个 handler 单独写恢复逻辑, handle 幂等即可重放;
 *  - 重写了 restore: 走自定义现场施加, 不再碰 handle。
 */
public class ActionHandlerRestoreContractTest {

    @Test
    public void defaultRestoreDelegatesToHandle() throws Exception {
        int[] handleCalls = {0};
        ActionHandler h = new ActionHandler() {
            @Override
            public String name() {
                return "x";
            }
            @Override
            public Status handle(ActionContext ctx) {
                handleCalls[0]++;
                return Status.SUCCESS;
            }
        };

        h.restore(null);   // 默认实现转调 handle(本替身不碰 ctx, 故传 null 也安全)

        Assert.assertEquals("默认 restore 应恰好触发一次 handle", 1, handleCalls[0]);
    }

    @Test
    public void overriddenRestoreDoesNotCallHandle() throws Exception {
        int[] handleCalls = {0};
        int[] restoreCalls = {0};
        ActionHandler h = new ActionHandler() {
            @Override
            public String name() {
                return "x";
            }
            @Override
            public Status handle(ActionContext ctx) {
                handleCalls[0]++;
                return Status.SUCCESS;
            }
            @Override
            public void restore(ActionContext ctx) {
                restoreCalls[0]++;   // 自定义现场施加, 不委托 handle
            }
        };

        h.restore(null);

        Assert.assertEquals(1, restoreCalls[0]);
        Assert.assertEquals("重写后不该再走 handle", 0, handleCalls[0]);
    }
}
