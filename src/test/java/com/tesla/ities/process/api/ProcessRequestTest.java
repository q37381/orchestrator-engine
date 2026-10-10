package com.tesla.ities.process.api;

import com.tesla.ities.process.core.model.Status;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语法糖 {@link ProcessRequest} 的回归: 它不该有任何自己的语义, 只做"攒参数 + 末尾转调门面对口方法"。
 *   - 链式填的 defId/version/user/params 必须**原样**到达 create / createAndStart 的四个位置参数;
 *  - 一条参数都没填 -> 传 null(不是空 Map), 与不走糖时落库文本一字不差;
 *  - 平铺键不做点号展开( {@code db.host} 就是叫 "db.host" 的键);
 *  - create() 只建不跑(不能偷偷摸到 createAndStart);
 *  - 同名后写覆盖先写; 交给门面的 Map 是拷贝, 引擎侧改不动 builder。
 */
public class ProcessRequestTest {

    /** 只记账的门面替身: 五个协作者全 null, 执行类入口被覆盖成"把收到的参数留下来"。 */
    private static class Recording extends ProcessFacade {
        String called;
        String defId;
        Integer version;
        Map<String, Object> params;
        String userId;
        int createCount;
        int createAndStartCount;
        ProcessExecution ret = new ProcessExecution("P1", Status.SUCCESS);

        Recording() {
            super(null, null, null, null, null);
        }

        @Override
        public String create(String defId, Integer version, Map<String, Object> params, String userId) {
            called = "create";
            this.defId = defId;
            this.version = version;
            this.params = params;
            this.userId = userId;
            createCount++;
            return "P1";
        }

        @Override
        public ProcessExecution createAndStart(String defId, Integer version, Map<String, Object> params, String userId) {
            called = "createAndStart";
            this.defId = defId;
            this.version = version;
            this.params = params;
            this.userId = userId;
            createAndStartCount++;
            return ret;
        }
    }

    @Test
    public void chainFeedsAllFourPositionalArgs() {
        Recording f = new Recording();
        ProcessExecution r = f.run("db-migration")
                .version(3)
                .user("u1001")
                .param("cluster", "prod-a")
                .param("tables", Arrays.asList("t_order", "t_user"))
                .start();

        Assert.assertEquals("start() 应转调 createAndStart", "createAndStart", f.called);
        Assert.assertEquals(1, f.createAndStartCount);
        Assert.assertEquals("db-migration", f.defId);
        Assert.assertEquals(Integer.valueOf(3), f.version);
        Assert.assertEquals("u1001", f.userId);
        Assert.assertEquals("prod-a", f.params.get("cluster"));
        Assert.assertEquals(Arrays.asList("t_order", "t_user"), f.params.get("tables"));
        Assert.assertSame("返回值就是门面给的那个 ProcessExecution, 糖不加工", f.ret, r);
    }

    @Test
    public void defaultsAreNullVersionNullUserAndNullParams() {
        Recording f = new Recording();
        f.run("db-migration").start();

        Assert.assertNull("不写 version = 由定义层取最新 PUBLISHED", f.version);
        Assert.assertNull("不写 user = 不记创建人", f.userId);
        Assert.assertNull("一条参数都没填要传 null, 而不是空 Map", f.params);
    }

    @Test
    public void createOnlyDelegatesToCreate() {
        Recording f = new Recording();
        String id = f.run("db-migration").param("cluster", "prod-a").create();

        Assert.assertEquals("P1", id);
        Assert.assertEquals("create()", "create", f.called);
        Assert.assertEquals(1, f.createCount);
        Assert.assertEquals("只创建不该接手执行", 0, f.createAndStartCount);
    }

    @Test
    public void laterWriteWinsAndExplicitNullIsKept() {
        Recording f = new Recording();
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("cluster", "from-map");
        external.put("batchSize", 200);

        f.run("db-migration")
                .param("cluster", "first")
                .params(external)                 // 批量灌: 同名覆盖前面手写的
                .param("tables", null)            // 显式 null 也照进桶, 不静默丢
                .start();

        Assert.assertEquals("from-map", f.params.get("cluster"));
        Assert.assertEquals(Integer.valueOf(200), f.params.get("batchSize"));
        Assert.assertTrue("值可以是 null", f.params.containsKey("tables"));
        Assert.assertNull(f.params.get("tables"));
    }

    @Test
    public void dottedKeyStaysFlat() {
        Recording f = new Recording();
        f.run("db-migration").param("db.host", "10.0.0.1").start();

        Assert.assertEquals("键名不做点号展开", Arrays.asList("db.host"),
                new ArrayList<>(f.params.keySet()));
        Assert.assertFalse("不该凭空长出嵌套桶", f.params.containsKey("db"));
        Assert.assertEquals(1, f.params.size());
    }

    @Test
    public void runWithMapSeedsParams() {
        Recording f = new Recording();
        Map<String, Object> seeded = new LinkedHashMap<>();
        seeded.put("cluster", "prod-b");

        f.run("db-migration", seeded).param("table", "t_order").start();

        Assert.assertEquals("prod-b", f.params.get("cluster"));
        Assert.assertEquals("t_order", f.params.get("table"));
        Assert.assertEquals("run(defId, map) 只是 params(map) 的简写", 2, f.params.size());
    }

    @Test
    public void handedMapIsACopyAndViewsAreReadOnly() {
        Recording f = new Recording();
        Map<String, Object> seeded = new LinkedHashMap<>();
        seeded.put("cluster", "prod-a");
        ProcessRequest req = f.run("db-migration", seeded).param("table", "t_order");

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
        f.params.put("mutated", "by-engine");      // 模拟引擎侧改收到的 Map
        Assert.assertEquals("交出去的是拷贝, 引擎改不动 builder", 2, req.params().size());
        Assert.assertFalse(req.params().containsKey("mutated"));
        Assert.assertFalse("外部 Map 事后新增的键不该漏进来", req.params().containsKey("injected"));
    }

    @Test
    public void paramsMapNullIsIgnored() {
        Recording f = new Recording();
        f.run("db-migration").param("cluster", "prod-a").params(null).start();

        Assert.assertEquals(1, f.params.size());
        Assert.assertEquals("prod-a", f.params.get("cluster"));

        f = new Recording();
        f.run("db-migration").params(null).start();
        Assert.assertNull("只有 params(null) 也算没填参数", f.params);
    }

    @Test
    public void gettersReflectAccumulatedState() {
        ProcessRequest req = new Recording().run("db-migration").version(7).user("u2").param("k", "v");
        Assert.assertEquals("db-migration", req.getDefId());
        Assert.assertEquals(Integer.valueOf(7), req.getVersion());
        Assert.assertEquals("u2", req.getUserId());
        Assert.assertEquals("v", req.params().get("k"));
    }
}
