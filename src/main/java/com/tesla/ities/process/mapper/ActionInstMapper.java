package com.tesla.ities.process.mapper;

import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ActionInstEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * action_inst 数据访问。SQL 见 resources/META-INF/mapper/process/ActionInstMapper.xml。
 *
 * 精简说明:
 *  - findInContainer / findByContainerAndDef 只差"是否按 action_def_id 收到自然键一条", 合并为
 *    {@link #findInContainer}(laneId/actionDefId/segment 都可空);
 *  - setStatus / casStatus / activate / updateResult 合并为一条动态 {@link #updateState},
 *    落 result 时同句 visit_count++(重跑观测), 与原 updateResult 语义一致。
 * 语义快捷方法都是 default, 不额外产生 SQL 语句; 审计列 updator_id 由调用方从上下文显式下传(null = 不刷)。
 */
@Mapper
public interface ActionInstMapper {

    /**
     * 幂等物化: 命中 uk_node_def_shard 则忽略。声明顺序由 auto_id 升序承载。
     * 注: 非分片行 shard_key=NULL 时唯一键不去重(NULL != NULL), 建行前必须先按自然键查存在。
     */
    int insertIgnore(ActionInstEntity a);

    ActionInstEntity findById(@Param("id") String id);

    /**
     * 容器(lane 或非分片 node)内的 action, 按 auto_id(声明顺序)升序。
     * laneId=null 匹配 lane_id IS NULL 的非分片行。
     *
     * @param actionDefId null = 容器内全部; 非 null = 按自然键 (node, lane, segment, actionDefId) 只定位一条
     * @param segment     null = 不按段收窄(进度视图要全量); 非 null = 只取该执行段(setup|main|setdown)
     */
    List<ActionInstEntity> findInContainer(@Param("nodeInstId") String nodeInstId,
                                           @Param("laneId") String laneId,
                                           @Param("actionDefId") String actionDefId,
                                           @Param("segment") String segment);

    /**
     * action 列表查询: nodeInstId / actionInstId 两个条件都可选(通常传其一, 都给则取交集),
     * 按 auto_id(声明顺序)升序。与 {@link #findInContainer} 的差别是不按 lane 收窄 ——
     * 传 nodeInstId 即拿到该节点所有 lane 的 action; 只传 actionInstId 即按主键取列表(0/1 条)。
     * 注: 两者都为 null 即全表扫描, 调用方至少给一个。
     */
    List<ActionInstEntity> find(@Param("nodeInstId") String nodeInstId,
                                @Param("actionInstId") String actionInstId);

    /**
     * 统一的行更新入口: 只写传入的非 null 列。
     *
     * @param fromStatus 非 null 即 CAS(仅当前状态匹配才更新)
     * @param toStatus   目标状态(null 不改 status)
     * @param result     终态结果 JSON(含 __error/__skipReason 保留键; 非 null 时 visit_count++)
     * @param updatorId  最后更新用户(null 不改 updator_id)
     */
    int updateState(@Param("id") String id, @Param("fromStatus") String fromStatus,
                    @Param("toStatus") String toStatus, @Param("result") String result,
                    @Param("updatorId") String updatorId);

    // ---------- 以下为 action 级认领路径(备用, 默认关闭; 见 #2) ----------

    int claim(@Param("id") String id);

    // ---------- 语义快捷(都走 findInContainer / updateState) ----------

    /** 容器(lane 或非分片 node)内全部 action(不按段收窄), 按声明顺序升序。 */
    default List<ActionInstEntity> findInContainer(String nodeInstId, String laneId) {
        return findInContainer(nodeInstId, laneId, null, null);
    }

    /**
     * 按自然键 (node, lane, segment, actionDefId) 定位单条 action(幂等物化 find-or-create 用)。
     * segment 必须显式给出——跨段可能同名 id, 不带段的定位会串段。
     * laneId=null 匹配非分片行; 非分片 shard_key=NULL 时 uk_node_def_shard 不去重, 故取声明顺序最早的一条。
     */
    default ActionInstEntity findByContainerAndDef(String nodeInstId, String laneId,
                                                   String actionDefId, String segment) {
        List<ActionInstEntity> rows = findInContainer(nodeInstId, laneId, actionDefId, segment);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 直接置状态(驱动期落库)。 */
    default int setStatus(String id, String to, String updatorId) {
        return updateState(id, null, to, null, updatorId);
    }

    /** CAS 翻状态: 当前为 from 才翻到 to。 */
    default int casStatus(String id, String from, String to, String updatorId) {
        return updateState(id, from, to, null, updatorId);
    }

    /** 激活: CREATED -> PENDING。 */
    default int activate(String id, String updatorId) {
        return casStatus(id, Status.CREATED.getStatus(), Status.PENDING.getStatus(), updatorId);
    }

    /** 落终态 + result(含 __error/__skipReason 保留键), 并 visit_count++(观测重跑)。 */
    default int updateResult(String id, String to, String result, String updatorId) {
        return updateState(id, null, to, result, updatorId);
    }
}
