package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.core.runtime.VariableResolver;
import com.tt.ii.process.domain.ProcessInstEntity;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config 两相冻结口径回归({@link ConfigFreeze}): 从 Materializer 搬下来的静态工具, 语义必须逐字不变。
 *  - PLAN(物化期): ${vars.*}/${params.*} 用创建期 baseVars 烙成字面量, ${result.*} 原样留给激活期(#12);
 *  - ACTIVATION(激活期): ${result.*} 在当前上下文现场取(驱动/重放共用这一条路径);
 *  - 非 Map 输入一律回空 Map(与旧 asMap 同一兜底), 且解析绝不改动传入的那份定义 config。
 *
 * VariableResolver 用真身(@Spy): 这里钉的就是"两相各解哪些桶"的组合行为, 打桩就解不出要验的东西了。
 */
@RunWith(MockitoJUnitRunner.class)
public class ConfigFreezeTest {

    @Spy
    private VariableResolver resolver = new VariableResolver();

    private final ConfigFreeze freeze = new ConfigFreeze();

    private static ProcessInstEntity proc() {
        ProcessInstEntity p = new ProcessInstEntity();
        p.setProcInstId("p1");
        return p;
    }

    /** baseVars = 创建期 vars(定义 vars + 创建 params 已在门面创建时并进来), PLAN 相只认这一份。 */
    private static DriveContext dcWithVars(Map<String, Object> baseVars) {
        ProcessContext pctx = new ProcessContext("p1");
        pctx.putAllVars(baseVars);
        return DriveContext.create(proc(), null, pctx, new VariableResolver(), null);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** PLAN: vars/params 烙成字面量, result 留待激活期; 输入那份定义 config 不被改动。 */
    @Test
    public void planFreezesVarsAndParamsAndKeepsResult() {
        DriveContext dc = dcWithVars(map("table", "t_order", "env", "prod"));
        Map<String, Object> defCfg = map("sql", "SELECT * FROM ${vars.table}",
                "target", "${params.env}", "key", "${result.id}");

        Map<String, Object> frozen = freeze.freezePlan(dc, defCfg);

        Assert.assertEquals("SELECT * FROM t_order", frozen.get("sql"));
        Assert.assertEquals("prod", frozen.get("target"));
        Assert.assertEquals("${result.id}", frozen.get("key"));
        Assert.assertEquals("定义侧那份 config 原样不动(防御拷贝)", "SELECT * FROM ${vars.table}", defCfg.get("sql"));
    }

    /** 非 Map 的 config(null/串)兜底成空 Map, 与旧 asMap 一致, 绝不把 null 漏给调用方。 */
    @Test
    public void nonMapConfigFreezesToEmptyMap() {
        DriveContext dc = dcWithVars(map("k", "v"));
        Assert.assertTrue(freeze.freezePlan(dc, null).isEmpty());
        Assert.assertTrue(freeze.freezePlan(dc, "${vars.k}").isEmpty());
        Assert.assertTrue(freeze.freezeActivation(resolver, dc.processContext(), "裸串").isEmpty());
    }

    /** ACTIVATION: 在同一上下文现场读 result(驱动第 1) 步与重放路径共用这一口径)。 */
    @Test
    public void activationReadsResultOnGivenContext() {
        ProcessContext pctx = new ProcessContext("p1");
        pctx.putResult("rows", 7);

        Map<String, Object> live = freeze.freezeActivation(resolver, pctx, map("n", "${result.rows}"));

        Assert.assertEquals(7, live.get("n"));
    }
}
