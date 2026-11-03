package com.tt.ii.process.core.engine;

import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.constants.ReservedKeys;
import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.core.define.ProcessDefinition;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.runtime.ConditionEvaluator;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.core.runtime.DefinitionCache;
import com.tt.ii.process.core.runtime.VariableResolver;
import com.tt.ii.process.domain.ProcessDefinitionEntity;
import com.tt.ii.process.domain.ProcessInstEntity;
import com.tt.ii.process.domain.ProcessSchedEntity;
import com.tt.ii.process.mapper.ProcessDefinitionMapper;
import com.tt.ii.process.mapper.ProcessInstMapper;
import com.tt.ii.process.mapper.ProcessSchedMapper;
import com.tt.ii.process.util.IdGenerator;
import com.tt.ii.process.util.IdKind;
import com.tt.ii.process.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建/计划服务: 流程实例创建 + flow 物化。
 *
 *  - 定义定位: version 为空时取最新 PUBLISHED 版本; 否则按 (defId, version) 精确定位。
 *  - process_inst 初始: status=PENDING, config=创建输入(与其他层同名列, params 桶不落库),
 *    vars=定义 vars 合并创建 params(供物化期 PLAN 解析;
 *    两桶同名会被 warn —— params 覆盖 vars, 且物化期 ${vars.k} 与 ${params.k} 取到同一值、运行期才分岔)。
 *    监听器链不落快照列——定义版本钉死, 驱动时从定义推导。
 *  - 当前用户(#1): 引擎不自取会话——创建方把已获取好的 userId 传进来, 写 process_inst.creator_id/updator_id
 *    与 ProcessContext.userId; 后续各级实例行的 creator_id 都取流程创建人, updator_id 同样由上下文下传。
 *  - 物化: EAGER 创建期递归物化**整棵节点树**(ensureRoots, 节点级分母稳定; lane/action 属执行细节,
 *    一律驱动期建); LAZY(默认)创建期**不建任何节点行**, 驱动走查到第 i 个根才建第 i 个
 *    (Materializer.rootAt), 子层同理逐个建。
 *
 * 创建参数统一收成 {@link CreateRequest}(原 3/4/6 参重载保留并委托), 后续加字段不再扩散位置参数。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final ProcessInstMapper processMapper;
    private final ProcessDefinitionMapper defMapper;
    private final DefinitionCache defCache;
    private final Materializer materializer;
    private final ContextFactory ctxFactory;
    private final VariableResolver resolver;
    private final ConditionEvaluator evaluator;
    private final IdGenerator ids;
    private final ProcessSchedMapper schedMapper;   // 可选路由: null = 不支持写调度行(仅无路由创建路径)

    public PlanService(ProcessInstMapper processMapper, ProcessDefinitionMapper defMapper,
                       DefinitionCache defCache, Materializer materializer, ContextFactory ctxFactory,
                       VariableResolver resolver, ConditionEvaluator evaluator, IdGenerator ids) {
        this(processMapper, defMapper, defCache, materializer, ctxFactory, resolver, evaluator, ids, null);
    }

    @Autowired
    public PlanService(ProcessInstMapper processMapper, ProcessDefinitionMapper defMapper,
                       DefinitionCache defCache, Materializer materializer, ContextFactory ctxFactory,
                       VariableResolver resolver, ConditionEvaluator evaluator, IdGenerator ids,
                       ProcessSchedMapper schedMapper) {
        this.processMapper = processMapper;
        this.defMapper = defMapper;
        this.defCache = defCache;
        this.materializer = materializer;
        this.ctxFactory = ctxFactory;
        this.resolver = resolver;
        this.evaluator = evaluator;
        this.ids = ids;
        this.schedMapper = schedMapper;
    }

    /** 创建流程实例(无创建人); 等价 createProcess(defId, version, params, null)。 */
    public String createProcess(String defId, Integer version, Map<String, Object> params) {
        return createProcess(CreateRequest.of(defId, version, params, null));
    }

    /**
     * 创建流程实例(流程 PENDING; 物化按定义 EAGER/LAZY), 返回 procInstId。
     *
     * @param userId 流程创建人(由调用方获取好传进来): 落 creator_id/updator_id 并进 ProcessContext.userId
     */
    public String createProcess(String defId, Integer version, Map<String, Object> params, String userId) {
        return createProcess(CreateRequest.of(defId, version, params, userId));
    }

    /**
     * 创建流程实例 + **可选路由**(分布式调度): 给了 schedUnit 才往调度表写一行 -> 该流程进"主动调度池";
     * 没给 schedUnit 就不写 -> 调度器不主动认领它(仍可同步 {@code facade.start} 触发)。
     * schedZone 可选: 测试环境常不区分区域, 留空(NULL)则同单元任意 worker 都能认领; 生产环境钉了区域才要求同区域 worker。
     *
     * <p>两行写入(process_inst + process_sched)非同一事务(引擎不托管事务, 由消费者按需在外部包裹):
     * 先插 process_inst 再插 process_sched, 若调度行写失败, 最坏是该流程留在 pending 但未被主动调度
     * (路由模式下无调度行 = 不被认领), 不会被错误执行, 属安全失败。
     */
    public String createProcess(String defId, Integer version, Map<String, Object> params, String userId,
                                String schedUnit, String schedZone) {
        return createProcess(CreateRequest.of(defId, version, params, userId, schedUnit, schedZone));
    }

    /** 创建请求的唯一实现入口(参数对象化, 后续加字段不再扩散位置参数)。 */
    public String createProcess(CreateRequest req) {
        String defId = req.defId;
        Integer version = req.version;
        Map<String, Object> params = req.params;
        String userId = req.userId;
        String schedUnit = req.schedUnit;
        String schedZone = req.schedZone;
        ProcessDefinition def;
        int defVersion;
        if (version == null) {
            ProcessDefinitionEntity latest = defMapper.findLatestPublished(defId);
            if (latest == null) {
                throw new ProcessException("AU204401", defId);
            }
            defVersion = latest.getVersion();
            def = defCache.definition(defId, defVersion);
        } else {
            defVersion = version;
            def = defCache.definition(defId, defVersion);
        }

        String procInstId = ids.next(IdKind.PROCESS_INST);      // 流程实例 id 走 processInstSequenceGenerator
        // 初始 vars = 定义 vars + 创建参数合并(物化期 PLAN 解析的 baseVars 来源)
        Map<String, Object> baseVars = new LinkedHashMap<>(def.getVars());
        if (params != null) {
            for (String k : params.keySet()) {
                if (def.getVars().containsKey(k)) {
                    // 两桶在物化期同源(都读 baseVars): 同名时无法区分 ${vars.k} 与 ${params.k}, 且运行期会漂移
                    log.warn("创建参数与定义 vars 同名(参数覆盖 vars, 物化期两种写法取同一值) def={} v={} key={}",
                            defId, defVersion, k);
                }
            }
            baseVars.putAll(params);
        }

        ProcessInstEntity inst = new ProcessInstEntity();
        inst.setProcInstId(procInstId);
        inst.setProcessDefId(defId);
        inst.setDefVersion(defVersion);
        inst.setStatus(Status.PENDING.getStatus());
        inst.setConfig(configOf(def, params));   // 创建参数落**流程级 config**(与 node/lane/action 同名同语义); settleOnStop 策略一并钉死
        inst.setVars(Json.write(baseVars));
        inst.setCreatorId(userId);        // 启动流程的当前用户(外部传入)
        inst.setUpdatorId(userId);
        processMapper.insert(inst);

        // 可选路由: 给了 schedUnit 才写调度行(进主动调度池); sched_zone 可选(测试环境常不区分, NULL=不钉区域)。
        if (schedMapper != null && hasText(schedUnit)) {
            ProcessSchedEntity sched = new ProcessSchedEntity();
            sched.setProcInstId(procInstId);
            sched.setSchedUnit(schedUnit);
            sched.setSchedZone(hasText(schedZone) ? schedZone : null);
            sched.setCreatorId(userId);
            sched.setUpdatorId(userId);
            schedMapper.insert(sched);
        }

        ProcessContext pctx = ctxFactory.process(inst);   // userId 从 creator_id 回填上下文
        pctx.attachDefinition(def);      // 监听器可读全部节点定义
        DriveContext dc = DriveContext.create(inst, def, pctx, resolver, evaluator);
        if (def.isEager()) {
            materializer.ensureRoots(dc, def.orderedRoots(), true);   // EAGER: 创建期一次性建全整棵节点树(lane/action 驱动期建)
        }
        // LAZY: 不预建节点行, 驱动走查到第 i 个根才建(rootAt); 子层同样执行到才建; 两档 lane/action 一律驱动期建

        log.info("创建流程实例 proc={} def={} v={} materialization={} user={} route={}/{}",
                procInstId, defId, defVersion, def.getMaterialization(), userId, schedUnit, schedZone);
        return procInstId;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * 创建请求值对象: 把 defId/version/params/userId/schedUnit/schedZone 六个位置参数收成一份不可变现场。
     * 原有 3/4/6 参重载全部委托到这里, 门面签名保持不变。
     */
    public static final class CreateRequest {
        public final String defId;
        public final Integer version;
        public final Map<String, Object> params;
        public final String userId;
        public final String schedUnit;
        public final String schedZone;

        private CreateRequest(String defId, Integer version, Map<String, Object> params, String userId,
                              String schedUnit, String schedZone) {
            this.defId = defId;
            this.version = version;
            this.params = params;
            this.userId = userId;
            this.schedUnit = schedUnit;
            this.schedZone = schedZone;
        }

        public static CreateRequest of(String defId, Integer version, Map<String, Object> params, String userId) {
            return new CreateRequest(defId, version, params, userId, null, null);
        }

        public static CreateRequest of(String defId, Integer version, Map<String, Object> params, String userId,
                                       String schedUnit, String schedZone) {
            return new CreateRequest(defId, version, params, userId, schedUnit, schedZone);
        }
    }

    /**
     * 创建期 config 组成: 创建参数原样 + **停止策略钉死**(定义声明了 settleOnStop 才写 __settleOnStop)。
     * 钉进流程行而不是每次停止时查定义: 定义版本默认钉死, 策略恒定; 停止入口(InterventionService)
     * 与驱动(ExecutionEngine)只读流程行, 不多一条定义依赖。
     */
    private static String configOf(ProcessDefinition def, Map<String, Object> params) {
        if (!def.settleOnStopEnabled()) {
            return Json.write(params);   // 未声明: 与既有行为逐字一致(不添任何键)
        }
        Map<String, Object> cfg = params == null
                ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(params);
        cfg.put(ReservedKeys.SETTLE_ON_STOP, Boolean.TRUE);
        return Json.write(cfg);
    }
}