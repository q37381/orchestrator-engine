package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.Context;
import com.tt.ii.process.core.runtime.VariableResolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config 冻结器: 实例行 config 列的**两相占位符解析**收在这一处(原挂在 {@link Materializer} 上的静态工具)。
 *
 * 前身是 {@code Materializer.resolvePlan/asMap} 两个静态方法——静态工具挂在调度器上, 调用方既无法注入
 * 替身也无法统一观察, 且"解析"本就不是"位置对齐"的职责。收成本组件后: 物化期/激活期两条解析口径
 * 各一个具名方法, resolver 由调用方传入(DriveContext 的单例 / RestoreService 自持的那份), 本类零状态。
 *
 * 两相口径(与 {@link VariableResolver} 的 Phase 一一对应, 不放宽也不加严):
 *  - {@link #freezePlan}: 物化期(PLAN), ctx 传 null, ${vars.*}/${params.*} 用创建期 baseVars 就地烙成
 *    字面量, ${result.*}/${config.*} 解不出原样保留(#12)——输入先做防御拷贝, 定义里的 config 不被碰到;
 *  - {@link #freezeActivation}: 激活期(ACTIVATION), 在调用方给定的上下文上读 live vars 与 result,
 *    驱动与重放两条路径共用同一口径。
 *
 * 解不出的键(如指向不存在变量的 ${vars.x})由调用方 findUnresolved 拎出来告警(写 result.__unresolved),
 * 不在本类职责内。
 */
@Component
public class ConfigFreeze {

    /** 物化期 PLAN 解析: 用创建期 baseVars 替换 ${vars.*}/${params.*}; result/config 未解析原样保留(#12)。 */
    public Map<String, Object> freezePlan(DriveContext dc, Object rawConfig) {
        Object resolved = dc.resolver().resolve(new LinkedHashMap<>(asMap(rawConfig)), null,
                VariableResolver.Phase.PLAN, dc.baseVars());
        return asMap(resolved);
    }

    /** 激活期解析: ${result.*} 与运行期 live vars 在 ctx 现场取(驱动与重放同口径)。 */
    public Map<String, Object> freezeActivation(VariableResolver resolver, Context ctx, Object rawConfig) {
        return asMap(resolver.resolve(asMap(rawConfig), ctx, VariableResolver.Phase.ACTIVATION));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }
}
