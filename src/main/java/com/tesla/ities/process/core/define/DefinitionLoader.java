package com.tesla.ities.process.core.define;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.util.Json;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定义加载器: YAML 文本 -> ProcessDefinition。SafeConstructor 防任意对象实例化; 发布期再做 name/schema 静态校验。
 *
 * SnakeYAML 2.x 适配: 2.0 起 SafeConstructor 的**无参构造已删除**, 必须传 LoaderOptions
 * (1.33 已有同参构造, 所以本写法两边都能编/能跑)。
 *
 * 变更:
 *  - #3: 不再从 YAML 解析 processDefinition/version(以表列为准, 外部传入)。
 *  - vars 取代 variables+dialects; materialization 可选; actions 去 output, ref→handler。
 *  - body 更名为 flow(节点执行顺序); 监听器只解析 name/config, **按声明顺序执行**(不再有 order 字段)。
 *  - rollbackListeners 段与 listeners 同构(回退级 RollbackListener), 补偿不走 driveRoots, 靠它拿回退自己的切点。
 *  - globalNodeListeners / globalActionListeners 段同构解析(**全局**节点级/action 级链): 声明一次,
 *    生效链在消费点与本地声明合并(ProcessDefinition.mergeWithGlobals, 全局在前)。
 *  - 顶层 errorPolicy = **全局**错误策略(与节点同名属性同一个键名, 按层区分): 节点未声明则用它,
 *    在物化期烙进实例行的 error_policy 列(ProcessDefinition.effectiveErrorPolicy)。
 *  - 节点可选 name(展示名); map 键仍作 id(flow DSL 的引用键), 两者互不影响。
 *  - **聚合节点(group)**: 节点声明 {@code group: {flow: ..., nodes: ...}} 即成为一个命名串行容器——
 *    对外仍是 flow 里的一个名字(一个校验项 = 一个节点), 内部各步骤是子节点。装载期展开:
 *    子节点提升进顶层 nodes(键 = "<组>.<子>")、flow 里的组名重写成 {@code GROUP:组(组.子1,组.子2)},
 *    两者都是可序列化字段, 故定义 JSON 快照自带展开结果(见 expandGroups)。
 *  - **命名并行容器**: flow 里 {@code PARALLEL:标签(a,b)} 的命名后缀(写在关键字与括号之间)同样指向 nodes 下的一条
 *    纯容器声明, 于是并行也能就近声明 errorPolicy / when / config / 节点级监听器(匿名 {@code PARALLEL(a,b)} 一律吃全局, 行为不变)。
 *    命名是用户手写的, 装载期做配对校验(见 validateNamedParallels): 拼错名字或声明了没引用都当场拒,
 *    不留"策略设了没生效"这种要跑到运行期才发现的坑。
 *  - **段名写了但底下留空**(YAML 解成 null)与"整段缺失"同口径回落空容器: getOrDefault 对"键存在值为 null"
 *    仍返回 null, 而 vars 为 null 会在创建期 new LinkedHashMap<>(def.getVars()) 直接 NPE。
 */
@Component
public class DefinitionLoader {

    /** 定义 blob 是 MEDIUMTEXT(最大 16MB); 2.x 默认 codePointLimit 会截大定义, 这里显式放宽。 */
    private static final int MAX_DEFINITION_CHARS = 8 * 1024 * 1024;

    @SuppressWarnings("unchecked")
    public ProcessDefinition load(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(MAX_DEFINITION_CHARS);   // 两边版本都存在该 setter, 不依赖默认值差异
        Yaml y = new Yaml(new SafeConstructor(options));
        Map<String, Object> parsed = y.load(yaml);
        Map<String, Object> root = parsed == null ? new LinkedHashMap<>() : parsed;   // 空文档也当"什么都没声明"
        ProcessDefinition def = new ProcessDefinition();
        def.setVars(mapOr(root.get("vars")));                             // vars: 段留空 = 空 map
        def.setSetup((List<String>) root.get("setup"));
        def.setSetdown((List<String>) root.get("setdown"));
        def.setFlow(str(root.get("flow")));
        def.setErrorPolicy(str(root.get("errorPolicy")));        // 全局错误策略(节点未声明时的回落)

        Object mat = root.get("materialization");
        if (mat != null) {
            // of() 大小写不敏感且与 Locale 无关(原 toUpperCase() 吃默认 Locale, 土耳其语等环境会变形)
            ProcessDefinition.Materialization m = ProcessDefinition.Materialization.of(String.valueOf(mat));
            if (m == null) {
                throw new ProcessException("AU204201", mat);
            }
            def.setMaterialization(m);
        }

        def.setListeners(parseListeners((List<Object>) root.get("listeners")));
        def.setRollbackListeners(parseListeners((List<Object>) root.get("rollbackListeners")));   // 回退前置/收尾
        def.setGlobalNodeListeners(parseListeners((List<Object>) root.get("globalNodeListeners")));     // 全局节点级
        def.setGlobalActionListeners(parseListeners((List<Object>) root.get("globalActionListeners")));  // 全局 action 级

        Map<String, NodeDef> nodes = new LinkedHashMap<>();
        Map<String, Object> groupSections = new LinkedHashMap<>();   // 声明了 group: 段的节点键 -> 原始段
        for (Map.Entry<String, Object> e : mapOr(root.get("nodes")).entrySet()) {
            Map<String, Object> m = mapOr(e.getValue());   // 节点只写了个 id 底下全空 -> 空 map(不 NPE)
            nodes.put(e.getKey(), parseNode(e.getKey(), m));
            if (m.get("group") != null) {
                groupSections.put(e.getKey(), m.get("group"));
            }
        }
        def.setNodes(nodes);
        expandGroups(def, groupSections);   // 聚合节点: 子节点提升 + flow 重写(必须落在可序列化字段里)
        validateNamedParallels(def, groupSections.keySet());   // 命名并行容器: 后缀与声明必须成对(在展开之后, 此时 flow 是最终形状)
        return def;
    }

    /** 单个节点声明 -> NodeDef(map 键作 id; 聚合节点的 group 段由 {@link #expandGroups} 另行处理)。 */
    @SuppressWarnings("unchecked")
    private NodeDef parseNode(String id, Map<String, Object> m) {
        NodeDef nd = new NodeDef();
        nd.setId(id);
        nd.setName(str(m.get("name")));                // 展示名(可选)
        nd.setHandler(str(m.get("handler")));
        nd.setShard(str(m.get("shard")));
        nd.setLaneExecution(str(m.get("laneExecution")));
        nd.setActionExecution(str(m.get("actionExecution")));
        nd.setWhen(str(m.get("when")));
        nd.setCondition(str(m.get("condition")));
        nd.setErrorPolicy(str(m.get("errorPolicy")));
        nd.setConfig(mapOr(m.get("config")));
        // 节点级监听器(NodeListener)
        nd.setListeners(parseListeners((List<Object>) m.get("listeners")));
        List<ActionDef> ads = new ArrayList<>();
        for (Object ao : listOr(m.get("actions"))) {
            Map<String, Object> am = (ao instanceof Map) ? (Map<String, Object>) ao : null;
            if (am != null) {
                ActionDef ad = new ActionDef();
                ad.setId(str(am.get("id")));
                ad.setHandler(str(am.get("handler")));
                ad.setWhen(str(am.get("when")));
                ad.setConfig(mapOr(am.get("config")));
                ad.setListeners(parseListeners((List<Object>) am.get("listeners")));   // action 级(ActionListener)
                ads.add(ad);
            }
        }
        if (!ads.isEmpty()) {
            nd.setActions(ads);
        }
        return nd;
    }

    // ===================== 聚合节点(group)展开 =====================

    /** 提升后的子节点键分隔符: 组 spaceCheck 下的 fetchSrc -> "spaceCheck.fetchSrc"。 */
    private static final String GROUP_KEY_SEP = ".";
    /** 嵌套深度上限(YAML 本身是树, 不会成环; 上限只防手写出病态深的定义)。 */
    private static final int MAX_GROUP_DEPTH = 8;

    /**
     * 把 {@code nodes.<key>.group} 展开成 flow 里的 GROUP 表达式, 并把组内子节点**提升**进顶层 nodes。
     *
     * 为什么必须重写 flow 字符串(而不是只改内存里的表达式树): DefinitionCache 存的是定义的 canonical JSON
     * 快照, 而 ProcessDefinition.roots 是 transient —— 借出的副本各自按 flow 字符串重新解析 DSL。
     * 展开结果若只活在内存, 副本就会退回未展开状态, 驱动期报"flow 引用了未定义节点"。
     * 提升后的子节点键(带组前缀)与重写后的 flow 都是普通 bean 属性, 快照自带, 副本天然一致。
     *
     * 展开后的形状: {@code flow: "spaceCheck"} + {@code nodes.spaceCheck.group.flow: "fetchSrc,diffSpace"}
     * -> {@code flow: "GROUP:spaceCheck(spaceCheck.fetchSrc,spaceCheck.diffSpace)"},
     *    nodes 里多出 spaceCheck.fetchSrc / spaceCheck.diffSpace 两条(组自己那条保留, 承载 name/when/errorPolicy/config)。
     */
    private void expandGroups(ProcessDefinition def, Map<String, Object> groupSections) {
        if (groupSections.isEmpty()) {
            return;   // 没有聚合节点: flow 原样(手写 GROUP:名(...) 也走这条, 由解析器直接认)
        }
        Map<String, NodeDef> declared = def.getNodes();
        Map<String, NodeDef> flat = new LinkedHashMap<>();          // 展开后的全量节点表(保持声明顺序)
        Map<String, DslParser.Expr> groups = new LinkedHashMap<>();  // 组键 -> 展开好的 Group 表达式

        for (Map.Entry<String, NodeDef> e : declared.entrySet()) {
            String key = e.getKey();
            NodeDef nd = e.getValue();
            Object section = groupSections.get(key);
            if (section != null) {
                requirePureContainer(nd, key);
            }
            flat.put(key, nd);
            if (section != null) {
                groups.put(key, buildGroup(key, section, declared, flat, groupSections, 1));
            }
        }
        def.setNodes(flat);
        def.setFlow(DslParser.toFlow(substituteAll(DslParser.parseFlow(def.getFlow()), groups)));

        // setup/setdown 只承载叶子节点引用(它们是 List<String>, 表达不了容器), 聚合节点放 flow 里
        for (String ref : orEmpty(def.getSetup())) {
            if (groups.containsKey(ref)) {
                throw new ProcessException("AU204202", ref);
            }
        }
        for (String ref : orEmpty(def.getSetdown())) {
            if (groups.containsKey(ref)) {
                throw new ProcessException("AU204203", ref);
            }
        }
    }

    /** 命名容器(聚合节点 group / 带命名后缀的并行)是纯容器: 再声明 handler/actions/shard/laneExecution/actionExecution 会与容器语义冲突(且被静默忽略), 直接拒。 */
    private void requirePureContainer(NodeDef nd, String key) {
        if (nd.getHandler() != null || nd.getShard() != null || nd.getLaneExecution() != null
                || nd.getActionExecution() != null
                || (nd.getActions() != null && !nd.getActions().isEmpty())) {
            throw new ProcessException("AU204204", key);
        }
    }

    // ===================== 命名并行容器(flow 命名后缀)校验 =====================

    /**
     * 校验 flow 里的并行命名后缀与 nodes 声明成对。三条规矩都为了同一件事: **别让"我明明设了策略却没生效"拖到运行期**。
     *  1. 后缀指向的键必须在 nodes 下声明——手写 {@code GROUP:名(...)} 名字没声明是**允许**的(退化匿名串行容器,
     *     那是既有口径), 但并行命名是用户手敲的, 拼错就静默回落全局策略, 必须当场拒;
     *  2. 那条声明必须是纯容器(与聚合节点同规矩, 复用 {@link #requirePureContainer});
     *  3. 声明成了纯容器却哪儿都没引用 = 死配置, 也拒。顶层 group 键不在此列(那是既有口径, 不在本次改动范围)。
     */
    private void validateNamedParallels(ProcessDefinition def, Set<String> groupKeys) {
        Set<String> referenced = new LinkedHashSet<>();
        Set<String> labels = new LinkedHashSet<>();
        for (DslParser.Expr e : def.orderedRoots()) {   // setup + flow + setdown, 与前向走查同一份序
            collectRefs(e, referenced, labels);
        }
        for (String label : labels) {
            NodeDef nd = def.node(label);
            if (nd == null) {
                throw new ProcessException("AU204212", label);
            }
            requirePureContainer(nd, label);
        }
        for (Map.Entry<String, NodeDef> e : def.getNodes().entrySet()) {
            String key = e.getKey();
            if (labels.contains(key) || referenced.contains(key) || groupKeys.contains(key)
                    || !isContainerShaped(e.getValue())) {
                continue;
            }
            throw new ProcessException("AU204213", key);
        }
    }

    /** 前序收集表达式树里的引用键: referenced = 一切被走查到的键(叶子/组名/并行命名), labels = 并行容器的命名后缀。 */
    private void collectRefs(DslParser.Expr ex, Set<String> referenced, Set<String> labels) {
        if (ex instanceof DslParser.Leaf) {
            referenced.add(((DslParser.Leaf) ex).nodeRef);
        } else if (ex instanceof DslParser.Parallel) {
            DslParser.Parallel p = (DslParser.Parallel) ex;
            if (p.name != null) {
                referenced.add(p.name);
                labels.add(p.name);
            }
            for (DslParser.Expr item : p.items) {
                collectRefs(item, referenced, labels);
            }
        } else if (ex instanceof DslParser.Group) {
            DslParser.Group g = (DslParser.Group) ex;
            referenced.add(g.name);
            for (DslParser.Expr item : g.items) {
                collectRefs(item, referenced, labels);
            }
        } else if (ex instanceof DslParser.If) {
            DslParser.If iff = (DslParser.If) ex;
            collectRefs(iff.thenBranch, referenced, labels);
            if (iff.elseBranch != null) {
                collectRefs(iff.elseBranch, referenced, labels);
            }
        }
    }

    /** 只声明了容器属性(无 handler/actions/shard/laneExecution/actionExecution)的节点: 它只能是某个容器的声明, 不该沦为没人引用的死配置。 */
    private static boolean isContainerShaped(NodeDef nd) {
        return nd.getHandler() == null && nd.getShard() == null && nd.getLaneExecution() == null
                && nd.getActionExecution() == null
                && (nd.getActions() == null || nd.getActions().isEmpty());
    }

    /** 递归展开一个 group 段: 子节点提升进 flat(键加组前缀), 组内 flow 的引用重绑定, 返回 Group 表达式。 */
    private DslParser.Expr buildGroup(String prefix, Object section, Map<String, NodeDef> declared,
                                      Map<String, NodeDef> flat, Map<String, Object> groupSections, int depth) {
        if (depth > MAX_GROUP_DEPTH) {
            throw new ProcessException("AU204205", prefix, MAX_GROUP_DEPTH);
        }
        Map<String, Object> g = mapOr(section);
        String innerFlow = str(g.get("flow"));
        if (innerFlow == null || innerFlow.trim().isEmpty()) {
            throw new ProcessException("AU204206", prefix);
        }
        Map<String, Object> innerRaw = mapOr(g.get("nodes"));
        if (innerRaw.isEmpty()) {
            throw new ProcessException("AU204207", prefix);
        }

        // 1) 子节点先解析并提升(嵌套的 group 递归展开, 深度 +1)
        Map<String, DslParser.Expr> innerGroups = new LinkedHashMap<>();
        for (Map.Entry<String, Object> ie : innerRaw.entrySet()) {
            String childKey = ie.getKey();
            String lifted = prefix + GROUP_KEY_SEP + childKey;
            if (flat.containsKey(lifted) || declared.containsKey(lifted)) {
                throw new ProcessException("AU204208", lifted);
            }
            Map<String, Object> cm = mapOr(ie.getValue());
            NodeDef cnd = parseNode(lifted, cm);
            Object cSection = cm.get("group");
            if (cSection != null) {
                requirePureContainer(cnd, lifted);
            }
            flat.put(lifted, cnd);
            if (cSection != null) {
                innerGroups.put(childKey, buildGroup(lifted, cSection, declared, flat, groupSections, depth + 1));
            }
        }

        // 2) 组内 flow 解析 + 引用重绑定(组内名 -> 提升键; 组内嵌套聚合 -> 换成 Group 表达式)
        List<DslParser.Expr> items = new ArrayList<>();
        for (DslParser.Expr ex : DslParser.parseFlow(innerFlow)) {
            items.add(rebind(ex, prefix, innerRaw.keySet(), innerGroups, declared, flat, groupSections));
        }
        DslParser.Group group = new DslParser.Group(prefix);
        group.items.addAll(items);
        return group;
    }

    /**
     * 组内 flow 的引用重绑定。四种情况:
     *  1. 组内声明的嵌套聚合节点 -> 直接换成它的 Group 表达式;
     *  2. 组内声明的普通子节点 -> 键加组前缀;
     *  3. 引用了**另一个顶层聚合节点** -> 装载期就报错: 组名在 flow 里才会被替换成 GROUP 表达式,
     *     组内引用它会按叶子物化成一条没有 handler 的 ACTION 行, 驱动期才炸(离根因很远);
     *  4. 其余外层节点 -> 原样(引用外层节点作为本组的一个步骤, 合法), 不在外层也不在组内则报错。
     */
    private DslParser.Expr rebind(DslParser.Expr ex, String prefix, Set<String> innerKeys,
                                  Map<String, DslParser.Expr> innerGroups,
                                  Map<String, NodeDef> declared, Map<String, NodeDef> flat,
                                  Map<String, Object> groupSections) {
        if (ex instanceof DslParser.Leaf) {
            String ref = ((DslParser.Leaf) ex).nodeRef;
            DslParser.Expr nested = innerGroups.get(ref);
            if (nested != null) {
                return nested;
            }
            if (innerKeys.contains(ref)) {
                return new DslParser.Leaf(prefix + GROUP_KEY_SEP + ref);
            }
            if (groupSections.containsKey(ref)) {
                throw new ProcessException("AU204209", prefix, ref);
            }
            if (!declared.containsKey(ref) && !flat.containsKey(ref)) {
                throw new ProcessException("AU204210", prefix, ref);
            }
            return ex;   // 引用外层节点: 原样(物化时成为本组的子行)
        }
        if (ex instanceof DslParser.Parallel) {
            DslParser.Parallel out = new DslParser.Parallel();
            out.name = ((DslParser.Parallel) ex).name;   // 命名必须带过去: 展开会重建表达式树, 漏一行 = 静默丢命名
            for (DslParser.Expr item : ((DslParser.Parallel) ex).items) {
                out.items.add(rebind(item, prefix, innerKeys, innerGroups, declared, flat, groupSections));
            }
            return out;
        }
        if (ex instanceof DslParser.If) {
            DslParser.If src = (DslParser.If) ex;
            DslParser.If out = new DslParser.If();
            out.condition = src.condition;
            out.thenBranch = rebind(src.thenBranch, prefix, innerKeys, innerGroups, declared, flat, groupSections);
            out.elseBranch = src.elseBranch == null ? null
                    : rebind(src.elseBranch, prefix, innerKeys, innerGroups, declared, flat, groupSections);
            return out;
        }
        throw new ProcessException("AU204211", prefix, ex.getClass().getSimpleName());
    }

    /** 顶层 flow 里把组名换成展开好的 Group 表达式(其余表达式原样重建)。 */
    private List<DslParser.Expr> substituteAll(List<DslParser.Expr> exprs, Map<String, DslParser.Expr> groups) {
        List<DslParser.Expr> out = new ArrayList<>();
        for (DslParser.Expr ex : exprs) {
            out.add(substitute(ex, groups));
        }
        return out;
    }

    private DslParser.Expr substitute(DslParser.Expr ex, Map<String, DslParser.Expr> groups) {
        if (ex instanceof DslParser.Leaf) {
            DslParser.Expr g = groups.get(((DslParser.Leaf) ex).nodeRef);
            return g != null ? g : ex;
        }
        if (ex instanceof DslParser.Parallel) {
            DslParser.Parallel out = new DslParser.Parallel();
            out.name = ((DslParser.Parallel) ex).name;   // 同上: 顶层组替换也不能丢并行命名
            out.items.addAll(substituteAll(((DslParser.Parallel) ex).items, groups));
            return out;
        }
        if (ex instanceof DslParser.If) {
            DslParser.If src = (DslParser.If) ex;
            DslParser.If out = new DslParser.If();
            out.condition = src.condition;
            out.thenBranch = substitute(src.thenBranch, groups);
            out.elseBranch = src.elseBranch == null ? null : substitute(src.elseBranch, groups);
            return out;
        }
        return ex;   // 手写的 GROUP:名(...) 原样保留
    }

    private static List<String> orEmpty(List<String> v) {
        return v == null ? new ArrayList<>() : v;
    }

    @SuppressWarnings("unchecked")
    private List<ListenerDef> parseListeners(List<Object> raw) {
        List<ListenerDef> lds = new ArrayList<>();
        if (raw == null) {
            return lds;
        }
        for (Object o : raw) {
            Map<String, Object> m = (Map<String, Object>) o;
            ListenerDef ld = new ListenerDef();
            ld.setName(str(m.get("name")));
            ld.setConfig((Map<String, Object>) m.get("config"));
            lds.add(ld);   // 顺序 = 声明顺序, 执行时不再按 order 排序
        }
        return lds;
    }

    public String toCanonicalJson(ProcessDefinition def) {
        return Json.write(def);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 段写了但留空(null) / 整段缺失 一律回落空 map, 不给下游留 NPE 种子。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOr(Object v) {
        return v == null ? new LinkedHashMap<>() : (Map<String, Object>) v;
    }

    /** 同上, 列表版(actions 段留空不能让 for-each NPE)。 */
    @SuppressWarnings("unchecked")
    private static List<Object> listOr(Object v) {
        return v == null ? new ArrayList<>() : (List<Object>) v;
    }
}