package com.tesla.ities.process.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流程调用请求: {@link ProcessFacade} 的**语法糖**(不是新契约), 把"手搓 params Map + 记参数顺序"
 * 收敛成链式填空, 末尾 {@link #create()} / {@link #start()} 一步落到既有门面对口方法上。
 *
 * 唯一入口是 {@code facade.run(defId)}(构造器包级私有), 本类**不新增任何引擎语义**:
 *  - {@link #create()} == {@code facade.create(defId, version, params, userId)}(流程 PENDING, 等调度器捞);
 *  - {@link #start()}  == {@code facade.createAndStart(defId, version, params, userId)}(返 id + 状态,
 *    状态口径见 {@link ProcessExecution}: 同线程 inline 驱动下通常是终态, 但不承诺)。
 * 引擎/PlanService 的签名一律没动, 老调用方照旧。
 *
 * 写法示例:
 * <pre>
 *   ProcessExecution r = facade.run("db-migration")
 *           .version(3)                       // 可省 = 最新 PUBLISHED
 *           .user("u1001")                    // 可省 = 不记创建人(引擎不自取会话)
 *           .param("cluster", "prod-a")
 *           .param("tables", Arrays.asList("t_order", "t_user"))
 *           .start();
 * </pre>
 *
 * 口径(拍板过的三条, 别再改):
 *  - **参数一个都没写时传 null**, 与 {@code createAndStart(defId, null, null)} 完全等价
 *    —— process_inst.config 落库文本与不走糖时一字不差(不走"空 Map 更干净"那套, 免得存量行为漂移)。
 *  - **键名不做点号展开**: {@code param("db.host", v)} 就是平铺键 {@code "db.host"}, 与直接传 Map 同解;
 *    要嵌套结构就 {@code param("db", map)}。${config.db.host} 本来就支持点号下钻, 平铺键照样能解。
 *  - 只到"构建 + 触发"为止: 进度/干预仍用 {@code facade.progress(id)} 等, 本类不吞掉查询接口。
 *
 * 同名键**后写覆盖先写**({@link #params(Map)} 亦然), 与直接往 Map 里 put 一致;
 * 值可以显式为 null(照旧进桶, 不静默丢弃)。
 *
 * 一次性、非线程安全: 别当字段复用、别跨线程共享同一个 builder。
 */
public final class ProcessRequest {

    private final ProcessFacade facade;
    private final String defId;
    private Integer version;                     // null = 定义层取最新 PUBLISHED
    private String userId;                       // null = 不记创建人
    private String schedUnit;                    // 可选路由: 服务单元(null = 不路由, 不进主动调度池)
    private String schedZone;                    // 可选路由: 网络区域(null = 不路由)
    private final Map<String, Object> params = new LinkedHashMap<>();

    /** 只允许 {@link ProcessFacade#run(String)} 造, 保证入口唯一。 */
    ProcessRequest(ProcessFacade facade, String defId) {
        this.facade = facade;
        this.defId = defId;
    }

    /** 钉死定义版本; 不调用则取最新 PUBLISHED。 */
    public ProcessRequest version(int v) {
        this.version = v;
        return this;
    }

    /** 创建人(由调用方取好传入, 落 creator_id/updator_id); 不调用则不记。 */
    public ProcessRequest user(String userId) {
        this.userId = userId;
        return this;
    }

    /**
     * 可选路由(分布式调度): 指定本流程要落到的服务单元 + 网络区域。
     * 调了才写调度行 -> 参与调度器主动认领; 不调(或任一为 null)则不路由, 只能同步 {@link #start()} 触发。
     */
    public ProcessRequest on(String schedUnit, String schedZone) {
        this.schedUnit = schedUnit;
        this.schedZone = schedZone;
        return this;
    }

    /** 追加一个创建参数(进流程级 config, 子层用 ${config.k} 沿父链上溯即可读到)。 */
    public ProcessRequest param(String key, Object value) {
        params.put(key, value);
        return this;
    }

    /** 批量灌参数(整体传外部已有的 Map); 传 null = 不灌。 */
    public ProcessRequest params(Map<String, Object> m) {
        if (m != null) {
            params.putAll(m);
        }
        return this;
    }

    /** 只创建不执行(流程 PENDING, 由调度器/后续 {@code facade.start(id)} 接手), 返 procInstId。 */
    public String create() {
        // 未设路由 -> 走原四参门面对口(与不走糖时一字不差); 设了才走带路由的重载。
        return schedUnit == null && schedZone == null
                ? facade.create(defId, version, snapshot(), userId)
                : facade.create(defId, version, snapshot(), userId, schedUnit, schedZone);
    }

    /** 创建并立即启动(全自动执行主入口), 返流程实例 id + 调用结束时的状态。 */
    public ProcessExecution start() {
        return schedUnit == null && schedZone == null
                ? facade.createAndStart(defId, version, snapshot(), userId)
                : facade.createAndStart(defId, version, snapshot(), userId, schedUnit, schedZone);
    }

    public String getDefId() {
        return defId;
    }

    /** 生效版本; null = 未指定(由定义层取最新 PUBLISHED)。 */
    public Integer getVersion() {
        return version;
    }

    public String getUserId() {
        return userId;
    }

    /** 已填参数的只读快照(调试/断言用; 传给引擎的是另一份拷贝)。 */
    public Map<String, Object> params() {
        return Collections.unmodifiableMap(params);
    }

    /** 空桶传 null(与直接调门面传 null 等价), 非空才给一份可变拷贝(免得引擎侧改到 builder)。 */
    private Map<String, Object> snapshot() {
        return params.isEmpty() ? null : new LinkedHashMap<>(params);
    }
}
