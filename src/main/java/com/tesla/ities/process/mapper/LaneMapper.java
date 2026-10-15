package com.tesla.ities.process.mapper;

import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.LaneEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * lane 数据访问。SQL 见 resources/META-INF/mapper/process/LaneMapper.xml。
 *
 * 精简说明: findByNode / findByNodeAndShard 只差"是否按 shard_key 收窄", 合并为 {@link #findByNode};
 * setStatus / casStatus / activate / updateStatusResult / updateResult 合并为一条动态 {@link #updateState}。
 * 语义快捷方法都是 default, 不额外产生 SQL 语句; 审计列 updator_id 由调用方从上下文显式下传(null = 不刷)。
 */
@Mapper
public interface LaneMapper {

    /** 幂等物化: 命中 uk_node_shard 则忽略。 */
    int insertIgnore(LaneEntity lane);

    LaneEntity findById(@Param("id") String id);

    /**
     * 某分片节点下的 lane, 按 auto_id 升序。
     *
     * @param shardKey null = 该节点全部 lane; 非 null = 按唯一键 (node, shardKey) 只定位一条
     */
    List<LaneEntity> findByNode(@Param("nodeInstId") String nodeInstId, @Param("shardKey") String shardKey);

    /**
     * 统一的行更新入口: 只写传入的非 null 列。
     *
     * @param fromStatus 非 null 即 CAS(仅当前状态匹配才更新)
     * @param toStatus   目标状态(null 不改 status)
     * @param result     lane 级上下文 JSON(null 不改 result)
     * @param updatorId  最后更新用户(null 不改 updator_id)
     */
    int updateState(@Param("id") String id, @Param("fromStatus") String fromStatus,
                    @Param("toStatus") String toStatus, @Param("result") String result,
                    @Param("updatorId") String updatorId);

    // ---------- 以下为 lane 级认领路径(备用, 默认关闭; 见 #2) ----------

    int claim(@Param("id") String id);

    List<String> findClaimableLaneIds(@Param("limit") int limit);

    // ---------- 语义快捷(都走 findByNode / updateState) ----------

    /** 某分片节点下的全部 lane, 按声明顺序升序。 */
    default List<LaneEntity> findByNode(String nodeInstId) {
        return findByNode(nodeInstId, null);
    }

    /** 按 node + shardKey 定位单条 lane(find-or-create 幂等物化用; 命中唯一键 uk_node_shard)。 */
    default LaneEntity findByNodeAndShard(String nodeInstId, String shardKey) {
        List<LaneEntity> rows = findByNode(nodeInstId, shardKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 直接置状态(不校验前态)。 */
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

    /** 落状态 + lane 级上下文。 */
    default int updateStatusResult(String id, String to, String result, String updatorId) {
        return updateState(id, null, to, result, updatorId);
    }

    /** 只刷 lane 级上下文(不改状态)。 */
    default int updateResult(String id, String result, String updatorId) {
        return updateState(id, null, null, result, updatorId);
    }
}
