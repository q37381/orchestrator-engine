package com.tesla.ities.process.core.context;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上下文基类: 持有 config/params/result 三个 map + 指向父上下文。
 * 子可读父(上溯); 读 config/params/result 时按 action -> lane -> node -> process innermost 胜出。
 *
 * 落库口径: **各层持久化的配置桶统一叫 config**(流程层那一份 = 创建输入); 四张表都**不建 params 列**,
 * params 只是当下驱动里的运行期参数桶(由 ctx.putParams 写入, 不随实例行重建);
 * result 桶则**四层都落各自的 result 列**, 流程级那一份由驱动路径脏刷(见 ProcessContext)。
 * 各层**相互隔离**: 引擎不会自动向任何上级桶写值(无 action 挂载、无组内冒泡),
 * 跨层共享一律由使用者显式放置(putResult 到目标层 / putVar 写流程级 vars)。
 * 占位符 ${params.*} 靠物化期的 baseVars 定型。
 *
 * 读法分两档, 按 key 读的都**返回泛型 T**(调用方直接赋值, 不需强转):
 *  - **本层直取**(平铺键整键命中, 不解析点号路径、不上溯): getConfig(key)/getParams(key)/getResult(key)
 *    各读一个桶——等价于 config().get(k) 但免强转。**没有**跨三桶合并的 get(key): 同名的 result 与 config
 *    到底读到哪个全看调用方记不记得优先级, 那种"随手一读"正是口径漂移的温床, 要哪桶就写哪桶;
 *  - **上溯解析**(首段在本层则桶内下钻, 否则逐级向父): resolveConfig(key)/resolveParams(key)/resolveResult(key)。
 * 整桶读法只有一套: config()/params()/result()(本层视图, 不重名到 getXxx)。
 *
 * 泛型口径: `String sql = ctx.getResult("sql")` 等价于手写强转——编译器在调用点插入 checkcast,
 * 所以**类型不符当场 ClassCastException**(不会漂到后面才发现)。需要先探测类型再分支的场景(如实例不可控的
 * JSON 内容), 请显式接 Object 再 instanceof, 不要赌泛型。
 *
 * vars 是流程级运行期变量空间(#7): 任意层 putVar 都**逐级向上委派**到 ProcessContext(不是"直接父即流程级"),
 * 由引擎刷回 process_inst.vars。userId 同理: 任意层 userId() 上溯到流程创建人。
 */
public abstract class Context {
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected final Map<String, Object> config = new LinkedHashMap<>();
    protected final Map<String, Object> params = new LinkedHashMap<>();
    protected final Map<String, Object> result = new LinkedHashMap<>();
    protected final Context parent;

    protected Context(Context parent) {
        this.parent = parent;
    }

    public Context parent() {
        return parent;
    }
    public Map<String, Object> config() {
        return config;
    }
    public Map<String, Object> params() {
        return params;
    }
    public Map<String, Object> result() {
        return result;
    }

    /** 本层 config 按 key 直取(平铺键, 不上溯不上钻)。 */
    public <T> T getConfig(String key) {
        return cast(config.get(key));
    }

    /** 本层 params 按 key 直取(平铺键, 不上溯不上钻)。 */
    public <T> T getParams(String key) {
        return cast(params.get(key));
    }

    /** 本层 result 按 key 直取(平铺键, 不上溯不上钻)。 */
    public <T> T getResult(String key) {
        return cast(result.get(key));
    }

    /** 泛型读取的唯一 cast 点(调用方的类型不符因此在调用点就地 CCE, 不后漂)。 */
    @SuppressWarnings("unchecked")
    private <T> T cast(Object v) {
        return (T) v;
    }

    /**
     * 本层 config 读; 支持点号嵌套。首段在本层则本层内下钻, 否则递归向上。
     * 与占位符 ${config.*}(VariableResolver.configFrom)同为"本层优先、逐级上溯", 只差一处边界:
     * 本方法首段命中即停在本站(下钻不中返回 null), 解析器还会继续往父层找。
     */
    public <T> T resolveConfig(String key) {
        return cast(resolveFrom(config, key, () -> parent == null ? null : parent.<Object>resolveConfig(key)));
    }

    /**
     * 本层 result 读; 支持点号嵌套(result.a.b)。首段在本层则本层内下钻, 否则递归向上(#10)。
     */
    public <T> T resolveResult(String key) {
        return cast(resolveFrom(result, key, () -> parent == null ? null : parent.<Object>resolveResult(key)));
    }

    /** 本层 params 读; 同上嵌套规则(递归向上)。 */
    public <T> T resolveParams(String key) {
        return cast(resolveFrom(params, key, () -> parent == null ? null : parent.<Object>resolveParams(key)));
    }

    /** 首段命中本层桶则桶内下钻, 未命中走向上回退。 */
    private Object resolveFrom(Map<String, Object> bucket, String key, java.util.function.Supplier<Object> upward) {
        int dot = key.indexOf('.');
        String first = dot < 0 ? key : key.substring(0, dot);
        if (bucket.containsKey(first)) {
            if (dot < 0) {
                return bucket.get(first);
            }
            return descend(bucket.get(first), key.substring(dot + 1));
        }
        return upward.get();
    }

    @SuppressWarnings("unchecked")
    private Object descend(Object cur, String rest) {
        for (String seg : rest.split("\\.")) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(seg);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    public void putResult(String key, Object value) {
        result.put(key, value);
    }
    public void putConfig(String key, Object value) {
        config.put(key, value);
    }
    public void putParams(String key, Object value) {
        params.put(key, value);
    }

    /** 用解析后的 config 整体替换(运行期 ACTIVATION 解析后)。 */
    public void replaceConfig(Map<String, Object> resolved) {
        config.clear();
        if (resolved != null) {
            config.putAll(resolved);
        }
    }

    /** 写流程级变量: 非流程层**逐级向上委派**到 ProcessContext。 */
    public void putVar(String key, Object value) {
        if (parent != null) {
            parent.putVar(key, value);
        }
    }

    /** 读流程级变量: 非流程层逐级向上委派。 */
    public <T> T getVar(String key) {
        return parent == null ? null : parent.getVar(key);
    }

    /** 读全部流程级变量(供解析器)。 */
    public Map<String, Object> vars() {
        return parent == null ? new LinkedHashMap<>() : parent.vars();
    }

    /** 流程创建人(启动时刻捕获的当前用户): 非流程层逐级向上委派。 */
    public String userId() {
        return parent == null ? null : parent.userId();
    }
}
