package com.tesla.ities.process.mapper;

import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ProcessInstEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * process_inst 数据访问。SQL 见 resources/META-INF/mapper/process/ProcessInstMapper.xml。
 *
 * 精简说明: 原 casStatus / claim / updateResult / updateVars 四条语句只差"写哪几列",
 * 统一收进一条动态 {@link #updateState}(只写非 null 的列); 下面的语义快捷方法是 default,
 * 不额外产生 SQL 语句。审计列 updator_id 由调用方显式下传(驱动/干预人; null = 不刷该列)。
 */
@Mapper
public interface ProcessInstMapper {

    int insert(ProcessInstEntity inst);

    ProcessInstEntity findById(@Param("id") String id);

    /**
     * 统一的行更新入口: 只写传入的非 null 列。
     *
     * @param fromStatus 非 null 即 CAS(仅当前状态匹配才更新, 返回 0 表示被抢先翻走)
     * @param toStatus   目标状态(null 不改 status)
     * @param result     流程结果 JSON(null 不改 result)
     * @param vars       运行期变量空间 JSON(null 不改 vars)
     * @param updatorId  最后更新用户(null 不改 updator_id)
     */
    int updateState(@Param("id") String id, @Param("fromStatus") String fromStatus,
                    @Param("toStatus") String toStatus,
                    @Param("result") String result, @Param("vars") String vars,
                    @Param("updatorId") String updatorId);

    /** 捞一批可认领(PENDING)的流程, 按 auto_id 升序。全局模式(worker 未声明能力)用它, 不看调度表。 */
    List<String> findClaimableIds(@Param("limit") int limit);

    /**
     * 路由认领(#分布式调度): 只捞"调度表里有行且命中本 worker 能力"的 PENDING 流程。
     * JOIN process_sched 收窄到 sched_unit = unit; zone 可选——流程钉了 sched_zone 才要求等于 worker.zone,
     * 流程 sched_zone 为 NULL(未区分区域, 如测试环境)则同单元任意 worker 都认领。无调度行的流程不会被主动调度。
     * 认领闸门仍是 process_inst.status='pending'(单行 CAS 保原子), 本方法只多一层路由过滤。
     */
    List<String> findClaimableIdsByRoute(@Param("unit") String unit,
                                         @Param("zone") String zone,
                                         @Param("limit") int limit);

    // ---------- 语义快捷(都走 updateState) ----------

    /** 翻流程状态(闸门 + 干预): 当前状态为 from 才翻到 to。 */
    default int casStatus(String id, String from, String to, String updatorId) {
        return updateState(id, from, to, null, null, updatorId);
    }

    /**
     * 流程级认领(#2): PENDING -> RUNNING。成功 = 本 owner 取得驱动权。
     * updatorId 由认领方传入(调度器无人值守 = null; 手动启动 = 启动人)。
     */
    default int claim(String id, String updatorId) {
        return casStatus(id, Status.PENDING.getStatus(), Status.RUNNING.getStatus(), updatorId);
    }

    /** 刷回运行期 vars(handler 通过 ctx.putVar 写入后)。 */
    default int updateVars(String id, String vars, String updatorId) {
        return updateState(id, null, null, null, vars, updatorId);
    }
}
