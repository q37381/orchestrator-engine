package com.tesla.ities.process.mapper;

import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.NodeInstEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * node_inst 数据访问。SQL 见 resources/mapper/orchestrator/NodeInstMapper.xml。
 *
 * 精简说明:
 *  - findRoots / findChildren 只差"parent_id IS NULL 还是等于某值", 合并为 {@link #findByParent};
 *  - casStatus / setStatus / markRunning / activate / updateStatusResult / updateConfig 只差"写哪几列",
 *    合并为一条动态 {@link #updateState};
 *  - 原 setPending(join 计数)与 countByStatus(状态计数)无调用方且无人读 pending 列, 已移除
 *    (进度统计由 ProgressService 在内存聚合实例行)。
 * 语义快捷方法都是 default, 不额外产生 SQL 语句; 审计列 updator_id 由调用方从上下文显式下传
 * (驱动期 = 流程创建人, 干预期 = 操作人; 传 null 则不刷该列)。
 */
@Mapper
public interface NodeInstMapper {

    /** 幂等物化: 命中唯一键则忽略(崩溃后重放不重复插)。 */
    int insertIgnore(NodeInstEntity n);

    NodeInstEntity findById(@Param("id") String id);

    /**
     * 某流程下的节点行, 按 auto_id(声明顺序)升序 —— 驱动时与定义子表达式按下标对齐。
     *
     * @param parentId null = 顶层根行(setup + flow + setdown 三段的根), 否则 = 该父节点的子行
     */
    List<NodeInstEntity> findByParent(@Param("procInstId") String procInstId, @Param("parentId") String parentId);

    /**
     * 统一的行更新入口: 只写传入的非 null 列。
     *
     * @param fromStatus 非 null 即 CAS(仅当前状态匹配才更新)
     * @param toStatus   目标状态(null 不改 status)
     * @param result     容器聚合结果 JSON(null 不改 result)
     * @param config     节点 config JSON(含 IF 决策 __chosen, null 不改 config)
     * @param updatorId  最后更新用户(null 不改 updator_id)
     */
    int updateState(@Param("id") String id, @Param("fromStatus") String fromStatus,
                    @Param("toStatus") String toStatus,
                    @Param("result") String result, @Param("config") String config,
                    @Param("updatorId") String updatorId);

    // ---------- 以下为节点级认领路径(备用, 默认关闭; 见 #2) ----------

    int claim(@Param("id") String id);

    List<String> findClaimableNodeIds(@Param("limit") int limit);

    // ---------- 语义快捷(都走 findByParent / updateState) ----------

    /** 顶层根节点(setup + flow + setdown 三段的根行), 按声明顺序升序。 */
    default List<NodeInstEntity> findRoots(String procInstId) {
        return findByParent(procInstId, null);
    }

    /** 某父节点的子行, 按声明顺序升序(与定义子表达式按下标对齐)。 */
    default List<NodeInstEntity> findChildren(String procInstId, String parentId) {
        return findByParent(procInstId, parentId);
    }

    /** 直接置状态(不校验前态), 用于驱动期落库。 */
    default int setStatus(String id, String to, String updatorId) {
        return updateState(id, null, to, null, null, updatorId);
    }

    /** CAS 翻状态: 当前为 from 才翻到 to。 */
    default int casStatus(String id, String from, String to, String updatorId) {
        return updateState(id, from, to, null, null, updatorId);
    }

    /** 激活: CREATED -> PENDING。 */
    default int activate(String id, String updatorId) {
        return casStatus(id, Status.CREATED.getStatus(), Status.PENDING.getStatus(), updatorId);
    }

    /** owner 驱动: 开始跑该节点。 */
    default int markRunning(String id, String updatorId) {
        return setStatus(id, Status.RUNNING.getStatus(), updatorId);
    }

    /** 落状态 + 结果(容器聚合)。 */
    default int updateStatusResult(String id, String to, String result, String updatorId) {
        return updateState(id, null, to, result, null, updatorId);
    }

    /** 落节点 config(IF 决策 __chosen 等运行期写入)。 */
    default int updateConfig(String id, String config, String updatorId) {
        return updateState(id, null, null, null, config, updatorId);
    }
}
