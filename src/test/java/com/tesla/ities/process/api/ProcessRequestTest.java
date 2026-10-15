package com.tesla.ities.process.api;

import com.tesla.ities.process.core.model.Status;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语法糖 {@link ProcessRequest} 的回归: 它不该有任何自己的语义, 只做"攒参数 + 末尾转调门面对口方法"。
 *   - 链式填的 defId/version/user/params 必须**原样**到达 create / createAndStart 的四个位置参数;
 *   - 一条参数都没填 -> 传 null(不是空 Map), 与不走糖时落库文本一字不差;
 *   - 平铺键不做点号展开( {@code db.host} 就是叫 "db.host" 的键);
 *   - create() 只建不跑(不能偷偷摸到 createAndStart);
 *   - 同名后写覆盖先写; 交给门面的 Map 是拷贝, 引擎侧改不动 builder。
 *
 * 装配口径: 门面是 {@code @Mock} 字段(只回放 run(..) 那句"把糖挂回门面"), 四个位置参数一律
 * {@code @Captor} 抓出来断言 —— 糖的全部语义就是"转调时参数长什么样"。
 */
@RunWith(MockitoJUnitRunner.class)
public class ProcessRequestTest {

    @Mock
    private ProcessFacade facade;

    @Captor
    private ArgumentCaptor<String> defId;
    @Captor
    private ArgumentCaptor<Integer> version;
    @Captor
    private ArgumentCaptor<Map> params;
    @Captor
    private ArgumentCaptor<String> userId;

    private final ProcessExecution execution = new ProcessExecution("P1", Status.SUCCESS);

    /** 门面的两个 run 入口回放真糖(构造器包级私有, 本类同包可直接 new), 执行类入口只留返回值。 */
    @Before
    public void sugarStaysBoundToTheMockedFacade() {
        when(facade.run(anyString())).thenAnswer(inv -> new ProcessRequest(facade, inv.<String>getArgument(0)));
        when(facade.run(anyString(), any())).thenAnswer(inv ->
                new ProcessRequest(facade, inv.<String>getArgument(0)).params(inv.<Map<String, Object>>getArgument(1)));
        when(facade.create(anyString(), any(), any(), any())).thenReturn("P1");
        when(facade.createAndStart(anyString(), any(), any(), any())).thenReturn(execution);
    }

    @Test
    public void chainFeedsAllFourPositionalArgs() {
        ProcessExecution r = facade.run("db-migration")
                .version(3)
                .user("u1001")
                .param("cluster", "prod-a")
                .param("tables", Arrays.asList("t_order", "t_user"))
                .start();

        verify(facade).createAndStart(defId.capture(), version.capture(), params.capture(), userId.capture());
        Assert.assertEquals("db-migration", defId.getValue());
        Assert.assertEquals(Integer.valueOf(3), version.getValue());
        Assert.assertEquals("u1001", userId.getValue());
        Assert.assertEquals("prod-a", params.getValue().get("cluster"));
        Assert.assertEquals(Arrays.asList("t_order", "t_user"), params.getValue().get("tables"));
        Assert.assertSame("返回值就是门面给的那个 ProcessExecution, 糖不加工", execution, r);
    }

    @Test
    public void defaultsAreNullVersionNullUserAndNullParams() {
        facade.run("db-migration").start();

        verify(facade).createAndStart(defId.capture(), version.capture(), params.capture(), userId.capture());
        Assert.assertNull("不写 version = 由定义层取最新 PUBLISHED", version.getValue());
        Assert.assertNull("不写 user = 不记创建人", userId.getValue());
        Assert.assertNull("一条参数都没填要传 null, 而不是空 Map", params.getValue());
    }

    @Test
    public void createOnlyDelegatesToCreate() {
        String id = facade.run("db-migration").param("cluster", "prod-a").create();

        Assert.assertEquals("P1", id);
        verify(facade).create(defId.capture(), version.capture(), params.capture(), userId.capture());
        Assert.assertEquals("db-migration", defId.getValue());
        Assert.assertEquals("prod-a", params.getValue().get("cluster"));
        verify(facade, never()).createAndStart(anyString(), any(), any(), any());   // 只创建不该接手执行
    }

    @Test
    public void laterWriteWinsAndExplicitNullIsKept() {
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("cluster", "from-map");
        external.put("batchSize", 200);

        facade.run("db-migration")
                .param("cluster", "first")
                .params(external)                 // 批量灌: 同名覆盖前面手写的
                .param("tables", null)            // 显式 null 也照进桶, 不静默丢
                .start();

        verify(facade).createAndStart(defId.capture(), version.capture(), params.capture(), userId.capture());
        Assert.assertEquals("from-map", params.getValue().get("cluster"));
        Assert.assertEquals(Integer.valueOf(200), params.getValue().get("batchSize"));
        Assert.assertTrue("值可以是 null", params.getValue().containsKey("tables"));
        Assert.assertNull(params.getValue().get("tables"));
    }

    @Test
    public void dottedKeyStaysFlat() {
        facade.run("db-migration").param("db.host", "10.0.0.1").start();

        verify(facade).createAndStart(defId.capture(), version.capture(), params.capture(), userId.capture());
        Assert.assertEquals("键名不做点号展开", Arrays.asList("db.host"),
                new ArrayList<>(params.getValue().keySet()));
        Assert.assertFalse("不该凭空长出嵌套桶", params.getValue().containsKey("db"));
        Assert.assertEquals(1, params.getValue().size());
    }

    @Test
    public void runWithMapSeedsParams() {
        Map<String, Object> seeded = new LinkedHashMap<>();
        seeded.put("cluster", "prod-b");

        facade.run("db-migration", seeded).param("table", "t_order").start();

        verify(facade).createAndStart(defId.capture(), version.capture(), params.capture(), userId.capture());
        Assert.assertEquals("prod-b", params.getValue().get("cluster"));
        Assert.assertEquals("t_order", params.getValue().get("table"));
        Assert.assertEquals("run(defId, map) 只是 params(map) 的简写", 2, params.getValue().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void handedMapIsACopyAndViewsAreReadOnly() {
        Map<String, Object> seeded = new LinkedHashMap<>();
        seeded.put("cluster", "prod-a");
        ProcessRequest req = facade.run("db-migration", seeded).param("table", "t_order");

        seeded.put("injected", "later");          // 灌完再改外部 Map: 不该影响已攒的参数
        Assert.assertSame("链式方法返的就是同一个 builder", req, req.param("table", "t_order"));

        Assert.assertEquals(2, req.params().size());
        try {
            req.params().put("x", "y");
            Assert.fail("params() 应只读");
        } catch (UnsupportedOperationException expected) {
            // 只读视图
        }

        req.start();
        verify(facade).createAndStart(defId.capture(), version.capture(), params.capture(), userId.capture());
        Map<String, Object> handed = params.getValue();
        handed.put("mutated", "by-engine");       // 模拟引擎侧改收到的 Map
        Assert.assertEquals("交出去的是拷贝, 引擎改不动 builder", 2, req.params().size());
        Assert.assertFalse(req.params().containsKey("mutated"));
        Assert.assertFalse("外部 Map 事后新增的键不该漏进来", req.params().containsKey("injected"));
    }

    @Test
    public void paramsMapNullIsIgnored() {
        facade.run("db-migration").param("cluster", "prod-a").params(null).start();
        facade.run("db-migration").params(null).start();

        verify(facade, times(2)).createAndStart(eq("db-migration"), any(), params.capture(), any());
        Assert.assertEquals(1, params.getAllValues().get(0).size());
        Assert.assertEquals("prod-a", params.getAllValues().get(0).get("cluster"));
        Assert.assertNull("只有 params(null) 也算没填参数", params.getAllValues().get(1));
    }

    @Test
    public void gettersReflectAccumulatedState() {
        ProcessRequest req = facade.run("db-migration").version(7).user("u2").param("k", "v");
        Assert.assertEquals("db-migration", req.getDefId());
        Assert.assertEquals(Integer.valueOf(7), req.getVersion());
        Assert.assertEquals("u2", req.getUserId());
        Assert.assertEquals("v", req.params().get("k"));
    }
}
