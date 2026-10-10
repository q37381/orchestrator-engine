package com.tesla.ities.process.core.runtime;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.core.define.DefinitionLoader;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.domain.ProcessDefinitionEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.mapper.ProcessDefinitionMapper;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import com.tesla.ities.process.util.Json;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时定义缓存: 按 (defId, version) 缓存定义的 **canonical JSON 快照**(YAML 只解析一次),
 * {@link #definition} 每次把快照读回一份**全新的私有副本**。
 *
 * 为什么借出副本而不是缓存里那个对象: 定义一旦被原样交出去, 监听器/handler 手里就是同一个引用
 * (ListenerDef.getConfig()、ctx.nodes()、NodeDef/ActionDef/ProcessDefinition 的全套 setter 都是活的),
 * 一处赋值就永久污染缓存, 而且库里那行还是原样——重启前无人能发现。改成出口读回副本后,
 * 谁改都只影响自己那一次驱动。
 *
 * 为什么走 JSON 往返而不是手写 copy(): 序列化覆盖整张对象图, 以后给定义类加字段自动被带上,
 * 不会发生"新字段忘了拷 = 悄悄共享缓存"这种最难查的漏。
 * 已知代价(用之前请知道):
 *  - **标量类型漂移**: SnakeYAML 把裸日期(如 {@code cutoff: 2026-10-08})解成 java.util.Date,
 *    经 JSON 往返变长整数, ${vars.cutoff} 插值出来的文本就跟着变了。定义里 vars/config 的值请只用
 *    String/Number/Boolean/null/List/Map(日期一律加引号)。
 *  - 借出多一次 Jackson 反序列化(典型定义亚毫秒), 仍比每次解析 YAML 便宜一个量级。
 *  - {@code roots}(flow 解析结果)不入快照, 副本各自惰性重解析一次 DSL——纯字符串扫描, 可忽略。
 *
 * 调用纪律: **一次驱动只准调一次**。每次返回的都是不同对象, 在循环/策略里反复取会让同一次驱动内
 * 出现两份互不相干的定义(物化与监听器各改各的)。现有三个入口 create / drive / rollback 都恰好各取一次,
 * Materializer 对齐用的也是 dc.def() 那一份, 引用同一性天然保持。
 *
 * 不需要失效入口: 定义表一表多版本且只 find/insert, key 含 version -> 发新版本天然是新条目,
 * 老条目继续服务钉死老版本的实例。唯一绕不过的情况是直接 UPDATE 同版本行(绕过引擎写库), 那只能重启清缓存。
 *
 * v1.2: VariableResolver/ConditionEvaluator 已改为无状态 Spring 单例(#5),
 *       vars 作为参数传入, 故本类不再按流程实例构造它们。
 */
@Component
public class DefinitionCache {

    private final ProcessDefinitionMapper defMapper;
    private final ProcessInstMapper processMapper;
    private final DefinitionLoader loader;
    /** key = defId:version, value = 定义的 canonical JSON 快照(解析一次, 长期驻留)。 */
    private final Map<String, String> snapshots = new ConcurrentHashMap<>();

    public DefinitionCache(ProcessDefinitionMapper defMapper, ProcessInstMapper processMapper,
                           DefinitionLoader loader) {
        this.defMapper = defMapper;
        this.processMapper = processMapper;
        this.loader = loader;
    }

    /**
     * 取定义: 命中快照则**读回一份私有副本**(与缓存里那份不再共享任何容器/对象), 未命中才查库解析 YAML。
     * 见类注释的调用纪律: 一次驱动只在入口取一次。
     */
    public ProcessDefinition definition(String defId, Integer version) {
        return Json.read(snapshot(defId, version), ProcessDefinition.class);
    }

    /** 定位快照: 未命中时查库 + 解析 YAML + 转 canonical JSON(每个 (defId, version) 只做一次)。 */
    private String snapshot(String defId, Integer version) {
        String key = defId + ":" + version;
        return snapshots.computeIfAbsent(key, k -> {
            ProcessDefinitionEntity row = defMapper.find(defId, version, null);
            if (row == null) {
                throw new ProcessException("AU204402", k);
            }
            return loader.toCanonicalJson(loader.load(row.getDefinition()));
        });
    }

    public ProcessInstEntity processInst(String procInstId) {
        return processMapper.findById(procInstId);
    }
}
