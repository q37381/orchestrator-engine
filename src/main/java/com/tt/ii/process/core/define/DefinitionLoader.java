package com.tt.ii.process.core.define;

import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.util.Json;
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
 *  - body 更名为 flow(节点执行顺序); 段值既可是逗号分隔的字符串(可多行书写, 空白由 DslParser 跳过),
 *    也可是 YAML 列表(每项 = 一个顶层表达式, 装载期按顶层逗号拼回 DSL 字符串); 监听器只解析 name/config,
 *    **按声明顺序执行**(不再有 order 字段)。
 *  - rollbackListeners 段与 listeners 同构(回退级 RollbackListener), 补偿不走 driveRoots, 靠它拿回退自己的切点。
 *  - globalNodeListeners / globalActionListeners 段同构解析(**全局**节点级/action 级链): 声明一次,
 *    生效链在消费点与本地声明合并(ProcessDefinition.mergeWithGlobals, 全局在前)。
 *  - 顶层 errorPolicy = **全局**错误策略(与节点同名属性同一个键名, 按层区分): 节点未声明则用它,
 *    在物化期烙进实例行的 error_policy 列(ProcessDefinition.effectiveErrorPolicy)。
 *  - 顶层 autoRollback = **自动回退**开关(见 {@link #declaredSwitch}): 只认 true/false, 配了 true 就在驱动
 *    终态为 FAILED/STOPPED 时由引擎自动逆序补偿(生效点在 ExecutionEngine, 不在本类)。
 *  - 顶层 settleOnStop = **停止先收尾**开关: 只认 true/false(拼错拒 AU204234), 配了 true 就把 "停止" 从
 *    "硬翻 STOPPED" 改成**两阶段**——停止入口只落 __stopRequested 标识, 下一次调度进入收尾轮(setdown/finally)
 *    后才落 STOPPED(生效点在 InterventionService/ExecutionEngine, 策略创建期钉进 process_inst.config)。
 *  - 节点可选 name(展示名); map 键仍作 id(flow DSL 的引用键), 两者互不影响。
 *  - **聚合节点(group)**: 节点声明 {@code group: {flow: ..., nodes: ...}} 即成为一个命名串行容器——
 *    对外仍是 flow 里的一个名字(一个校验项 = 一个节点), 内部各步骤是子节点。装载期展开:
 *    子节点提升进顶层 nodes(键 = "<组>.<子>")、flow 里的组名重写成 {@code GROUP:组(组.子1,组.子2)},
 *    两者都是可序列化字段, 故定义 JSON 快照自带展开结果(见 expandGroups)。
 *  - **命名并行容器**: flow 里 {@code PARALLEL:标签(a,b)} 的命名后缀(写在关键字与括号之间)同样指向 nodes 下的一条
 *    纯容器声明, 于是并行也能就近声明 errorPolicy / when / config / 节点级监听器(匿名 {@code PARALLEL(a,b)} 一律吃全局, 行为不变)。
 *    命名是用户手写的, 装载期做配对校验(见 validateNamedParallels): 拼错名字或声明了没引用都当场拒,
 *    不留"策略设了没生效"这种要跑到运行期才发现的坑。
 *  - **节点级 setup/setdown 段**(节点回路三段): 叶子节点(含分片叶子/组内子节点)可声明"内联动作序列"
 *    setup(主段之前)/setdown(收尾, finally 语义), 与 actions 段同构解析; 段与主段合并 id 唯一(AU204215),
 *    段缺主段拒(AU204216); 段字段是普通属性, 随定义 JSON 快照往返。
 *  - **GROUP 级 setup/setdown 段**: 聚合节点的段包住**整个子节点序列**(组回路三段: 组 setup -> 组内子节点逐个
 *    走查 -> 组 setdown); 段与开关(setup/setdown/repeatSegments)可写在 **group: 段内**(与 flow 级
 *    setup/flow/setdown 三段同形, 推荐)或**与 group: 同级**(旧写法), 两处等价、同一字段两处都写拒(AU204235);
 *    与子节点无关的字段(handler/actions/shard/执行档)照旧拒(AU204204); 同一份声明既是组又被当叶子引用且带段/开关
 *    拒(AU204226); 命名并行仍是纯容器, 段与开关依旧 AU204204。
 *  - **组级段 = 叶子子节点引用**(v1.4, 与流程级 setup/setdown 同形): 段值按**声明形状**分流——纯字符串列表
 *    = 组内子节点引用(嵌入时重绑定为提升键并注入 Group 表达式的头尾), 纯动作 map 列表 = 内联动作(v1.3 口径
 *    原样保留); 同一条里混用/非字符串非 map/两段形状不一致拒(AU204230); 节点引用出现在**叶子**声明的段上拒
 *    (AU204231, 节点级段只接受内联动作); 组级引用必须命中本组 group.nodes 下直接声明的叶子子节点(AU204232,
 *    嵌套聚合节点与组外节点都不行), 且不得与主体 flow 引用同一个子节点(AU204233)。
 *  - **段重臂开关 repeatSegments**(v1.1): 节点级/GROUP 级/流程级同名布尔开关, 开了段"每趟驱动重跑"(复位口径在走查引擎);
 *    无段可作用的死配置与非法值都当场拒(AU204217/AU204218/AU204219, 归一化 true/false 存快照);
 *    命名并行声明开关拒(AU204204)。
 *  - **段不跟随分片开关 segmentsFollowShard**(v1.3): 分片叶子声明 false 时节点 setup/setdown **不跟随分片**——
 *    整节点各跑一次(段行 lane 恒 null, 主体仍逐 lane); 未声明/true 保持"每 lane 一个完整三段回路"。
 *    仅对"分片且有节点段"的叶子有意义: 无段声明(AU204228)或非分片(AU204229)的 false 是死配置当场拒,
 *    非法值 AU204227; 容器(GROUP/命名并行)一律 AU204204。
 *  - **段校验统一在展开后做**(见 validateSegmentsByUsage): 同一条 nodes 声明的用途(GROUP/叶子)要到最终 flow
 *    里才定型, parseNode 只解析挂载、不判主体——组因没有 handler/actions 不会被误判成"段缺主段"。
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
        def.setFlow(flowText(root.get("flow")));                          // 串或 YAML 列表均可(列表按顶层逗号拼回 DSL)
        def.setErrorPolicy(str(root.get("errorPolicy")));        // 全局错误策略(节点未声明时的回落)
        def.setAutoRollback(declaredSwitch(root.get("autoRollback")));   // 自动回退开关
        def.setSettleOnStop(declaredSettleSwitch(root.get("settleOnStop")));   // 停止先收尾开关
        def.setRepeatSegments(declaredRepeatSegments(root.get("repeatSegments")));   // 段重复执行开关(顶层镜像)

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
        validateSegmentsByUsage(def, groupSections.keySet());   // 段校验: 展开后按每条声明的实际用途(GROUP/叶子)分类
        validateProcessRepeatSegments(def);   // 顶层 repeatSegments 死配置守门(与节点级 AU204217 同口径)
        return def;
    }

    /** 单个节点声明 -> NodeDef(map 键作 id; group: 段内的组级 setup/setdown/repeatSegments 在此按声明归一, 容器展开由 {@link #expandGroups} 另行处理)。 */
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
        nd.setRepeatSegments(declaredRepeatSegments(groupScoped(m, "repeatSegments", id)));   // 段重复执行开关(v1.1)
        nd.setSegmentsFollowShard(declaredSegmentsFollowShard(m.get("segmentsFollowShard")));   // 段是否跟随分片(v1.3)
        nd.setConfig(mapOr(m.get("config")));
        // 节点级监听器(NodeListener)
        nd.setListeners(parseListeners((List<Object>) m.get("listeners")));
        List<ActionDef> ads = parseActions(m.get("actions"), id, true);
        if (!ads.isEmpty()) {
            nd.setActions(ads);
        }
        // setup/setdown 段按**声明形状**分流(v1.4): 纯字符串列表 = 组级节点引用; 纯动作 map 列表 = 内联动作;
        // 同一条里混用或两段形状不一致当场拒(AU204230)。写法两处等价(group: 段内 / 与 group: 同级, 见 groupScoped);
        // 用途合法性(组用引用/叶用内联)与引用重绑定都推迟到展开之后(validateSegmentsByUsage / buildGroup)。
        Object setupDecl = groupScoped(m, "setup", id);
        Object setdownDecl = groupScoped(m, "setdown", id);
        int setupShape = segmentShape(setupDecl);
        int setdownShape = segmentShape(setdownDecl);
        if (setupShape < 0 || setdownShape < 0
                || (setupShape != 0 && setdownShape != 0 && setupShape != setdownShape)) {
            throw new ProcessException("AU204230", id,
                    setupShape < 0 ? "setup" : setdownShape < 0 ? "setdown" : "setup/setdown");
        }
        if (setupShape == SEGMENT_SHAPE_REFS) {
            nd.setSetupRefs(refList(setupDecl));
        } else if (setupShape == SEGMENT_SHAPE_ACTIONS) {
            List<ActionDef> setupAds = parseActions(setupDecl, id, false);
            if (!setupAds.isEmpty()) {
                nd.setSetup(setupAds);
            }
        }
        if (setdownShape == SEGMENT_SHAPE_REFS) {
            nd.setSetdownRefs(refList(setdownDecl));
        } else if (setdownShape == SEGMENT_SHAPE_ACTIONS) {
            List<ActionDef> setdownAds = parseActions(setdownDecl, id, false);
            if (!setdownAds.isEmpty()) {
                nd.setSetdown(setdownAds);
            }
        }
        return nd;
    }

    /**
     * 组级声明字段(setup/setdown/repeatSegments)的取值: **group: 段内**(与 flow 级 setup/flow/setdown 三段同形)与
     * **节点上**(与 group: 同级, 旧写法)两处等价; 同一字段两处都写 = 歧义, 当场拒(AU204235)。
     * group: 不是 map 时只认节点级声明(容器形状错误由 buildGroup 的 AU204206 兜住)。
     */
    private Object groupScoped(Map<String, Object> m, String field, String id) {
        Object inner = null;
        Object section = m.get("group");
        if (section instanceof Map) {
            inner = ((Map<?, ?>) section).get(field);
        }
        Object outer = m.get(field);
        if (inner != null && outer != null) {
            throw new ProcessException("AU204235", id, field);
        }
        return outer != null ? outer : inner;
    }

    /** 段声明形状: 空 / 内联动作(map 项) / 节点引用(字符串项); 负值 = 混用或出现其他类型。 */
    private static final int SEGMENT_SHAPE_EMPTY = 0;
    private static final int SEGMENT_SHAPE_ACTIONS = 1;
    private static final int SEGMENT_SHAPE_REFS = 2;

    /** 段声明形状探测(见 SEGMENT_SHAPE_*): map 与字符串同列混用、或出现其他类型 -> 负值。 */
    private static int segmentShape(Object section) {
        List<Object> items = listOr(section);
        if (items.isEmpty()) {
            return SEGMENT_SHAPE_EMPTY;
        }
        boolean anyMap = false;
        boolean anyStr = false;
        for (Object o : items) {
            if (o instanceof Map) {
                anyMap = true;
            } else if (o instanceof String) {
                anyStr = true;
            } else {
                return -1;
            }
        }
        return anyMap && anyStr ? -1 : (anyMap ? SEGMENT_SHAPE_ACTIONS : SEGMENT_SHAPE_REFS);
    }

    /** 节点引用列表(v1.4): 形状已判纯字符串, 这里只做归一(丢 null 项)。 */
    private static List<String> refList(Object section) {
        List<String> out = new ArrayList<>();
        for (Object o : listOr(section)) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /**
     * actions / setup / setdown 三个动作序列段的同构解析(列表; 每项结构 = ActionDef 的字段集合)。
     * allowActionSegments: 仅**主段动作**允许携带 action 级段标记(v1.2): 级 setup/setdown 两段继续同构解析;
     * 其余层(节点段项/段动作)一律拒(深度锁 1, AU204222)。
     */
    @SuppressWarnings("unchecked")
    private List<ActionDef> parseActions(Object section, String nodeId, boolean allowActionSegments) {
        List<ActionDef> out = new ArrayList<>();
        for (Object ao : listOr(section)) {
            Map<String, Object> am = (ao instanceof Map) ? (Map<String, Object>) ao : null;
            if (am != null) {
                ActionDef ad = new ActionDef();
                ad.setId(str(am.get("id")));
                ad.setHandler(str(am.get("handler")));
                ad.setWhen(str(am.get("when")));
                ad.setConfig(mapOr(am.get("config")));
                ad.setListeners(parseListeners((List<Object>) am.get("listeners")));   // action 级(ActionListener)
                if (allowActionSegments) {
                    // action 级段(v1.2): 段动作自身不得再带标记(这里传 false); 空段同口径(不挂空列表)
                    List<ActionDef> ws = parseActions(am.get("setup"), nodeId, false);
                    List<ActionDef> wd = parseActions(am.get("setdown"), nodeId, false);
                    if (!ws.isEmpty()) {
                        ad.setSetup(ws);
                    }
                    if (!wd.isEmpty()) {
                        ad.setSetdown(wd);
                    }
                } else {
                    List<ActionDef> ws = parseActions(am.get("setup"), nodeId, false);
                    List<ActionDef> wd = parseActions(am.get("setdown"), nodeId, false);
                    if (!ws.isEmpty() || !wd.isEmpty()) {
                        throw new ProcessException("AU204222", nodeId,
                                ad.getId() != null ? ad.getId() : ad.getHandler());
                    }
                }
                out.add(ad);
            }
        }
        return out;
    }

    /**
     * **叶子用途**的节点段校验(段解析后、展开后):
     *  1. 声明段(非空)的节点必须有主段(handler 或 actions)——否则段没有可包裹的主体, 驱动期连一条主 action 都物化不出;
     *  2. 显式声明 id 的段动作与主段(含单 handler 合成的那条)/另一段合并唯一——跨段同名没有正当场景,
     *     物理上 segment 虽已进唯一键, 业务键层面仍当场拒(空 id 不参与)。
     */
    private void validateSegments(NodeDef nd, List<ActionDef> setupAds, List<ActionDef> setdownAds) {
        if ((nd.getSetupRefs() != null && !nd.getSetupRefs().isEmpty())
                || (nd.getSetdownRefs() != null && !nd.getSetdownRefs().isEmpty())) {
            throw new ProcessException("AU204231", nd.getId());   // 叶子用途: 节点级段只接受内联动作
        }
        if (setupAds.isEmpty() && setdownAds.isEmpty()) {
            if (nd.repeatSegmentsEnabled()) {
                throw new ProcessException("AU204217", nd.getId());   // 死配置: 开了段重复执行却没有段可作用
            }
            if (!nd.segmentsFollowShardEnabled()) {
                throw new ProcessException("AU204228", nd.getId());   // 死配置: 声明段不跟随分片却没有段可作用
            }
        } else {
            if (nd.getHandler() == null && (nd.getActions() == null || nd.getActions().isEmpty())) {
                throw new ProcessException("AU204216", nd.getId());
            }
            if (!nd.segmentsFollowShardEnabled() && !nd.isSharded()) {
                throw new ProcessException("AU204229", nd.getId());   // 死配置: 非分片节点没有分片可"不跟随"
            }
            Set<String> seen = new LinkedHashSet<>();
            for (ActionDef ad : nd.effectiveActions()) {   // 主段 = actions 列表, 或单 handler 合成的那条(与物化同口径)
                if (ad.getId() != null) {
                    seen.add(ad.getId());
                }
            }
            requireSegmentIdsUnique(seen, nd.getId(), setupAds);
            requireSegmentIdsUnique(seen, nd.getId(), setdownAds);
        }
        validateActionSegments(nd);   // v1.2: action 级段校验与节点段无关, 无段也要跑
    }

    /** 段动作显式 id 合并唯一(空 id 不参与); 跨两段/与主段重名都当场拒(AU204215)。 */
    private void requireSegmentIdsUnique(Set<String> seen, String nodeId, List<ActionDef> ads) {
        for (ActionDef ad : ads) {
            if (ad.getId() != null && !seen.add(ad.getId())) {
                throw new ProcessException("AU204215", nodeId, ad.getId());
            }
        }
    }

    // ===================== 段校验(展开后按用途分类: GROUP / 叶子) =====================

    /**
     * 段声明的装载校验必须**在聚合节点展开之后**做: 同一条 nodes 声明的"用途"要到最终 flow 里才定型 ——
     *  - **GROUP 用途**(声明了 group 段, 或 flow 里被 {@code GROUP:名(...)} 引用): 段包裹整个子节点序列,
     *    主体是子节点表, 不校验"段缺主段"(组本就没有 handler/actions); 段动作 id 跨两段合并唯一,
     *    同一段内有效动作键也必须唯一(段行按 (node, lane, segment, 键) 定位, 重复键会让两条声明复用同一行);
     *  - **叶子用途**: 原有节点级段/action 级段校验逐字保留(AU204215/216/217/220~224);
     *  - 两种用途同时成立且声明了段/开关 -> AU204226: 一份定义不能既当组又当叶子, 段到底包裹谁没有唯一解。
     *
     * 为什么不下放到 parseNode: 那里还不知道声明会被谁引用(group 段展开/组内引用重绑定都在其后)。
     */
    private void validateSegmentsByUsage(ProcessDefinition def, Set<String> groupSections) {
        Set<String> groupRefs = new LinkedHashSet<>();
        Set<String> leafRefs = new LinkedHashSet<>();
        for (DslParser.Expr e : def.orderedRoots()) {
            collectUsage(e, groupRefs, leafRefs);
        }
        for (Map.Entry<String, NodeDef> e : def.getNodes().entrySet()) {
            String key = e.getKey();
            NodeDef nd = e.getValue();
            boolean asGroup = groupSections.contains(key) || groupRefs.contains(key);
            boolean asLeaf = leafRefs.contains(key);
            if (asGroup && asLeaf && hasSegmentDecl(nd)) {
                throw new ProcessException("AU204226", key);
            }
            if (asGroup) {
                validateGroupSegments(nd);
            } else {
                validateSegments(nd, actionDefsOrEmpty(nd.getSetup()), actionDefsOrEmpty(nd.getSetdown()));
            }
        }
    }

    /** 段声明未写(=null)时回落空列表(与"键存在值为 null 同口径"配套)。 */
    private static List<ActionDef> actionDefsOrEmpty(List<ActionDef> v) {
        return v == null ? new ArrayList<ActionDef>() : v;
    }

    /** 表达式树里的引用分类: Group 名收进 groupRefs, 叶子引用收进 leafRefs(命名并行后缀不算叶子用法)。 */
    private void collectUsage(DslParser.Expr ex, Set<String> groupRefs, Set<String> leafRefs) {
        if (ex instanceof DslParser.Group) {
            groupRefs.add(((DslParser.Group) ex).name);
        } else if (ex instanceof DslParser.Leaf) {
            leafRefs.add(((DslParser.Leaf) ex).nodeRef);
        }
        for (DslParser.Expr child : ex.children()) {
            collectUsage(child, groupRefs, leafRefs);
        }
    }
    
    /** 是否声明了段或段开关(用途冲突判据; 只看声明本身, 不看段是否为空)。 */
    private static boolean hasSegmentDecl(NodeDef nd) {
        return (nd.getSetup() != null && !nd.getSetup().isEmpty())
                || (nd.getSetdown() != null && !nd.getSetdown().isEmpty())
                || (nd.getSetupRefs() != null && !nd.getSetupRefs().isEmpty())
                || (nd.getSetdownRefs() != null && !nd.getSetdownRefs().isEmpty())
                || nd.getRepeatSegments() != null
                || nd.getSegmentsFollowShard() != null;
    }

    /**
     * GROUP 用途的段校验(主体 = 子节点序列, group.flow/nodes 的存在性已由 AU204206/207 兜住):
     *  1. 与组容器形状冲突的字段已在 {@link #requireGroupShape} 拒(AU204204);
     *  2. 显式 id 的段动作跨两段合并唯一(AU204215, 与节点级同口径);
     *  3. 同一段内的**有效动作键**(id, 未声明则回落 handler)不得重复 —— 段行定位就是按它去重的;
     *  4. repeatSegments 死配置(AU204217)。
     */
    private void validateGroupSegments(NodeDef nd) {
        requireGroupShape(nd, nd.getId());
        List<ActionDef> setupAds = actionDefsOrEmpty(nd.getSetup());
        List<ActionDef> setdownAds = actionDefsOrEmpty(nd.getSetdown());
        boolean hasRefs = (nd.getSetupRefs() != null && !nd.getSetupRefs().isEmpty())
                || (nd.getSetdownRefs() != null && !nd.getSetdownRefs().isEmpty());   // v1.4: 节点引用也是段
        if (setupAds.isEmpty() && setdownAds.isEmpty() && !hasRefs) {
            if (nd.repeatSegmentsEnabled()) {
                throw new ProcessException("AU204217", nd.getId());
            }
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        requireSegmentIdsUnique(seen, nd.getId(), setupAds);
        requireSegmentIdsUnique(seen, nd.getId(), setdownAds);
        requireSegmentKeysDistinct(setupAds, nd.getId());
        requireSegmentKeysDistinct(setdownAds, nd.getId());
    }

    /** 同一段内有效动作键不得重复: 段行按 (node, lane, segment, 键) 定位, 重复键会让两条声明复用同一实例行。 */
    private void requireSegmentKeysDistinct(List<ActionDef> ads, String nodeId) {
        Set<String> keys = new LinkedHashSet<>();
        for (ActionDef ad : ads) {
            String key = ad.getId() != null ? ad.getId() : ad.getHandler();
            if (key != null && !keys.add(key)) {
                throw new ProcessException("AU204225", nodeId,
                        "段内动作键重复: " + key + "(未声明 id 时按 handler 计, 重复声明请给不同 id)");
            }
        }
    }

    /**
     * v1.2 action 级段的装载校验(单 handler 节点无显式动作 -> 无级段):
     *  1. 级段**必须**同动作成对: 只挂 setup(AU204220)/只挂 setdown(AU204221)一律拒——
     *     "拆分式多动作级段"由此自然被拦(两侧各命中单边);
     *  2. 段动作携带开闭标记的嵌套已在 parseActions 拒(AU204222);
     *  3. 段动作显式 id 与主段/节点段/其他级段合并唯一(AU204223)——段行无处安锚, 这是"段行可识别"的唯一依据;
     *  4. 声明级段的节点与 actionExecution: PARALLEL 互斥(AU204224, 级段有先后语义)。
     */
    private void validateActionSegments(NodeDef nd) {
        List<ActionDef> mains = nd.getActions();
        if (mains == null || mains.isEmpty()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ActionDef ad : mains) {
            if (ad.getId() != null) {
                seen.add(ad.getId());
            }
        }
        List<ActionDef> nodeSetup = nd.getSetup();
        if (nodeSetup != null) {
            for (ActionDef ad : nodeSetup) {
                if (ad.getId() != null) {
                    seen.add(ad.getId());
                }
            }
        }
        List<ActionDef> nodeSetdown = nd.getSetdown();
        if (nodeSetdown != null) {
            for (ActionDef ad : nodeSetdown) {
                if (ad.getId() != null) {
                    seen.add(ad.getId());
                }
            }
        }
        boolean hasSegments = false;
        for (ActionDef ad : mains) {
            List<ActionDef> ws = ad.getSetup();
            List<ActionDef> wd = ad.getSetdown();
            boolean hasS = ws != null && !ws.isEmpty();
            boolean hasD = wd != null && !wd.isEmpty();
            if (hasS != hasD) {
                throw new ProcessException(hasS ? "AU204220" : "AU204221", nd.getId(), ad.getId());
            }
            if (!hasS) {
                continue;
            }
            hasSegments = true;
            for (ActionDef w : ws) {
                requireActionSegmentIdUnique(seen, nd.getId(), w);
            }
            for (ActionDef w : wd) {
                requireActionSegmentIdUnique(seen, nd.getId(), w);
            }
        }
        if (hasSegments && nd.isActionParallel()) {
            throw new ProcessException("AU204224", nd.getId());
        }
    }

    /** 段动作显式 id 合并唯一(空 id 不参与); 跨级段重名/与主段或节点段重名都当场拒。 */
    private void requireActionSegmentIdUnique(Set<String> seen, String nodeId, ActionDef w) {
        if (w.getId() != null && !seen.add(w.getId())) {
            throw new ProcessException("AU204223", nodeId, w.getId());
        }
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
                requireGroupShape(nd, key);   // GROUP 形状: 不可声明动作/分片/执行档; 段与 repeatSegments 是组的正当声明
            }
            flat.put(key, nd);
            if (section != null) {
                groups.put(key, buildGroup(key, section, nd, declared, flat, groupSections, 1));
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

    /** 命名容器(聚合节点 group / 带命名后缀的并行)的**纯容器形状**检查(GROUP 与并行共用的部分): 再声明 handler/actions/shard/laneExecution/actionExecution/segmentsFollowShard 会与容器语义冲突(且被静默忽略), 直接拒。 */
    private void requireGroupShape(NodeDef nd, String key) {
        if (nd.getHandler() != null || nd.getShard() != null || nd.getLaneExecution() != null
                || nd.getActionExecution() != null
                || nd.getSegmentsFollowShard() != null
                || (nd.getActions() != null && !nd.getActions().isEmpty())) {
            throw new ProcessException("AU204204", key);
        }
    }

    /** 命名并行(带命名后缀的 PARALLEL)是**纯**容器: 在 GROUP 的形状约束上再加段与 repeatSegments —— 并行的子项是表达式, 段没有可包裹的动作序列。 */
    private void requirePureContainer(NodeDef nd, String key) {
        requireGroupShape(nd, key);
        if ((nd.getSetup() != null && !nd.getSetup().isEmpty())
                || (nd.getSetdown() != null && !nd.getSetdown().isEmpty())
                || nd.getRepeatSegments() != null) {
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

    /**
     * 前序收集表达式树里的引用键: referenced = 一切被走查到的键(叶子/组名/并行命名), labels = 并行容器的命名后缀。
     * 按 {@code defRef()}/{@code containerLabel()}/{@code children()} 自述递归, 不再按类型分派。
     */
    private void collectRefs(DslParser.Expr ex, Set<String> referenced, Set<String> labels) {
        String ref = ex.defRef();
        if (ref != null) {
            referenced.add(ref);
        }
        String label = ex.containerLabel();
        if (label != null) {
            labels.add(label);
        }
        for (DslParser.Expr child : ex.children()) {
            collectRefs(child, referenced, labels);
        }
    }

    /** 只声明了容器属性(无 handler/actions/shard/laneExecution/actionExecution)的节点: 它只能是某个容器的声明, 不该沦为没人引用的死配置。 */
    private static boolean isContainerShaped(NodeDef nd) {
        return nd.getHandler() == null && nd.getShard() == null && nd.getLaneExecution() == null
                && nd.getActionExecution() == null
                && (nd.getActions() == null || nd.getActions().isEmpty());
    }

    /**
     * 递归展开一个 group 段: 子节点提升进 flat(键加组前缀), 组内 flow 的引用重绑定, 返回 Group 表达式。
     * 组级段=节点引用(v1.4)也在这里重绑定(局部键 -> 提升键, 写回 nd 存快照)并**注入 Group 子项的头尾**——
     * 段节点与主体同批按 index 对齐物化(EAGER 预建/LAZY 建行都据 children 对齐), 段/主体分界由 nd 的引用列表声明。
     */
    private DslParser.Expr buildGroup(String prefix, Object section, NodeDef nd, Map<String, NodeDef> declared,
                                      Map<String, NodeDef> flat, Map<String, Object> groupSections, int depth) {
        if (depth > MAX_GROUP_DEPTH) {
            throw new ProcessException("AU204205", prefix, MAX_GROUP_DEPTH);
        }
        Map<String, Object> g = mapOr(section);
        String innerFlow = flowText(g.get("flow"));
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
                requireGroupShape(cnd, lifted);   // 嵌套组同为 GROUP 用途
            }
            flat.put(lifted, cnd);
            if (cSection != null) {
                innerGroups.put(childKey, buildGroup(lifted, cSection, cnd, declared, flat, groupSections, depth + 1));
            }
        }

        // 2) 组内 flow 解析 + 引用重绑定(组内名 -> 提升键; 组内嵌套聚合 -> 换成 Group 表达式)
        List<DslParser.Expr> items = new ArrayList<>();
        for (DslParser.Expr ex : DslParser.parseFlow(innerFlow)) {
            items.add(rebind(ex, prefix, innerRaw.keySet(), innerGroups, declared, flat, groupSections));
        }

        // 3) 组级段=节点引用(v1.4): 重绑定 + 校验后写回 nd(存快照), 并把段节点注入 Group 子项的头尾
        Set<String> bodyRefs = new LinkedHashSet<>();
        collectLeafRefs(items, bodyRefs);
        List<String> setupRefs = rebindGroupSegmentRefs(nd.getSetupRefs(), prefix, innerRaw.keySet(), innerGroups, bodyRefs);
        List<String> setdownRefs = rebindGroupSegmentRefs(nd.getSetdownRefs(), prefix, innerRaw.keySet(), innerGroups, bodyRefs);
        if (!setupRefs.isEmpty()) {
            nd.setSetupRefs(setupRefs);
        }
        if (!setdownRefs.isEmpty()) {
            nd.setSetdownRefs(setdownRefs);
        }
        List<DslParser.Expr> all = new ArrayList<>();
        for (String ref : setupRefs) {
            all.add(new DslParser.Leaf(ref));
        }
        all.addAll(items);
        for (String ref : setdownRefs) {
            all.add(new DslParser.Leaf(ref));
        }
        DslParser.Group group = new DslParser.Group(prefix);
        group.items.addAll(all);
        return group;
    }

    /**
     * 组级段引用的重绑定与校验(v1.4): 组内局部键 -> 提升键(写回 nd 存快照, 驱动期据它切段与重臂)。
     *  1. 只能引用本组 group.nodes 下**直接声明**的叶子子节点——嵌套聚合节点(自带回路)与组外节点一律拒(AU204232);
     *  2. 不得与组主体 flow 引用同一个子节点(AU204233): 一个子节点不能既在段又在主体;
     *  3. 同一段内引用不得重复(段子节点按 index 对齐物化, 重复会建两行相同定义的子行, 同 AU204225 家族)。
     * 返回提升键列表(空 = 未声明该段)。
     */
    private List<String> rebindGroupSegmentRefs(List<String> refs, String prefix, Set<String> innerKeys,
                                                Map<String, DslParser.Expr> innerGroups, Set<String> bodyRefs) {
        if (refs == null || refs.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String ref : refs) {
            if (innerGroups.containsKey(ref) || !innerKeys.contains(ref)) {
                throw new ProcessException("AU204232", prefix, ref);
            }
            if (bodyRefs.contains(prefix + GROUP_KEY_SEP + ref)) {
                throw new ProcessException("AU204233", prefix, ref);
            }
            if (!seen.add(ref)) {
                throw new ProcessException("AU204225", prefix, "段内引用重复: " + ref);
            }
            out.add(prefix + GROUP_KEY_SEP + ref);
        }
        return out;
    }

    /** 收集表达式树里全部叶子引用键(组主体"谁在主体里"的取证, 与段引用互斥判据配套)。 */
    private static void collectLeafRefs(List<DslParser.Expr> exprs, Set<String> out) {
        for (DslParser.Expr ex : exprs) {
            if (ex instanceof DslParser.Leaf) {
                out.add(((DslParser.Leaf) ex).nodeRef);
            } else {
                collectLeafRefs(ex.children(), out);
            }
        }
    }

    /**
     * 组内 flow 的引用重绑定。叶子(组内步骤)四种情况:
     *  1. 组内声明的嵌套聚合节点 -> 直接换成它的 Group 表达式;
     *  2. 组内声明的普通子节点 -> 键加组前缀;
     *  3. 引用了**另一个顶层聚合节点** -> 装载期就报错: 组名在 flow 里才会被替换成 GROUP 表达式,
     *     组内引用它会按叶子物化成一条没有 handler 的 ACTION 行, 驱动期才炸(离根因很远);
     *  4. 其余外层节点 -> 原样(引用外层节点作为本组的一个步骤, 合法), 不在外层也不在组内则报错。
     *
     * 容器(PARALLEL/IF)一律按 {@link DslParser.ContainerExpr#withChildren} 原样重建(命名不能丢),
     * 所以这里不再逐类型开分支; 组内手写 {@code GROUP:名(...)} 仍在装载期就拒(它不是可重建的容器表达式)。
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
        if (!(ex instanceof DslParser.ContainerExpr)) {
            throw new ProcessException("AU204211", prefix, ex.getClass().getSimpleName());
        }
        List<DslParser.Expr> items = new ArrayList<>();
        for (DslParser.Expr item : ex.children()) {
            items.add(rebind(item, prefix, innerKeys, innerGroups, declared, flat, groupSections));
        }
        return ((DslParser.ContainerExpr) ex).withChildren(items);
    }

    /** 顶层 flow 里把组名换成展开好的 Group 表达式(其余表达式原样重建)。 */
    private List<DslParser.Expr> substituteAll(List<DslParser.Expr> exprs, Map<String, DslParser.Expr> groups) {
        List<DslParser.Expr> out = new ArrayList<>();
        for (DslParser.Expr ex : exprs) {
            out.add(substitute(ex, groups));
        }
        return out;
    }

    /** 叶子命中组名就换成展开好的 Group 表达式; 容器按 withChildren 原样重建; 手写的 {@code GROUP:名(...)} 原样保留。 */
    private DslParser.Expr substitute(DslParser.Expr ex, Map<String, DslParser.Expr> groups) {
        if (ex instanceof DslParser.Leaf) {
            DslParser.Expr g = groups.get(((DslParser.Leaf) ex).nodeRef);
            return g != null ? g : ex;
        }
        if (!(ex instanceof DslParser.ContainerExpr)) {
            return ex;   // 组名已是展开结果, 组内子项不再替换
        }
        List<DslParser.Expr> items = new ArrayList<>();
        for (DslParser.Expr item : ex.children()) {
            items.add(substitute(item, groups));
        }
        return ((DslParser.ContainerExpr) ex).withChildren(items);
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

    /**
     * flow 段取值(顶层与 {@code group.flow} 同口径): 逗号分隔的字符串, 或 YAML 列表
     * (每项 = 一个顶层表达式, 这里按顶层逗号拼回 DSL 字符串 —— 定义快照仍只存字符串,
     * 借出的副本重解析结果天然一致)。字符串里的换行/缩进/空白由 {@link DslParser} 统一跳过。
     * 列表项留空(YAML {@code -} 解成 null)直接忽略, 等价于没写那一行。
     */
    private static String flowText(Object v) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof List)) {
            return String.valueOf(v);
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : (List<?>) v) {
            if (item == null) {
                continue;
            }
            String s = String.valueOf(item).trim();
            if (s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 顶层布尔开关声明值(autoRollback)的装载期校验: 只认 true / false(大小写不敏感)。
     * 段没写 / 写了空白 = 没声明(回 null, 由消费点按关闭算); **写了但拼错当场拒** ——
     * 静默当"没配"处理就等于"我明明配了自动回退, 失败了却没回退", 那种坑要拖到事故现场才看得见。
     * 返回归一化的小写文本, 所以定义快照里永远存 true/false, 与 YAML 那行怎么写无关。
     */
    private String declaredSwitch(Object v) {
        String s = str(v);
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        Boolean b = ProcessDefinition.parseSwitch(s);
        if (b == null) {
            throw new ProcessException("AU204214", s);
        }
        return b.toString();
    }

    /** settleOnStop 开关声明值: 与 autoRollback 同口径, 拼错当场拒(AU204234; 留空 = 硬停)。 */
    private String declaredSettleSwitch(Object v) {
        String s = str(v);
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        Boolean b = ProcessDefinition.parseSwitch(s);
        if (b == null) {
            throw new ProcessException("AU204234", s);
        }
        return b.toString();
    }

    /** repeatSegments 开关声明值(节点级/流程级同一字段名): 与 autoRollback 同口径, 拼错当场拒(AU204219)。 */
    private String declaredRepeatSegments(Object v) {
        String s = str(v);
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        Boolean b = ProcessDefinition.parseSwitch(s);
        if (b == null) {
            throw new ProcessException("AU204219", s);
        }
        return b.toString();
    }

    /** segmentsFollowShard 开关声明值(仅分片叶子): 与 repeatSegments 同口径, 拼错当场拒(AU204227)。 */
    private String declaredSegmentsFollowShard(Object v) {
        String s = str(v);
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        Boolean b = ProcessDefinition.parseSwitch(s);
        if (b == null) {
            throw new ProcessException("AU204227", s);
        }
        return b.toString();
    }

    /** 顶层 repeatSegments 的死配置校验: 开了开关却没有流程级 setup/setdown 段可作用, 当场拒(与节点级 AU204217 同口径)。 */
    private void validateProcessRepeatSegments(ProcessDefinition def) {
        if (def.repeatSegmentsEnabled() && def.setupSize() == 0 && def.setdownSize() == 0) {
            throw new ProcessException("AU204218");
        }
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