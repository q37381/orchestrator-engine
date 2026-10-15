package com.tesla.ities.process.mapper;

import com.tesla.ities.process.domain.RollbackRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * rollback_record: 回退/补偿 + 向前重放历史(#16)。每个补偿/重放步骤一行, 关联原 action_inst(不改其状态)。
 * SQL 见 resources/META-INF/mapper/process/RollbackRecordMapper.xml。
 *
 * 精简说明: 原 findByProcess / findLatestByAction 只差筛选列, 合并为一个 {@link #find}(两个条件都可空)。
 */
@Mapper
public interface RollbackRecordMapper {

    int insert(RollbackRecordEntity r);

    /** 补偿/重放步骤落终态; to = RollbackStatus.getStatus() 小写规范值(调用方从枚举取, 不传枚举也不写字面量);
     *  updatorId = 发起回退/重放的操作人(null 不刷审计列)。 */
    int updateStatus(@Param("id") String id, @Param("to") String to, @Param("detail") String detail,
                     @Param("updatorId") String updatorId);

    /** 补偿/重放记录查询: 条件任选其一或都给, 按 auto_id(= 步骤执行顺序)升序。 */
    List<RollbackRecordEntity> find(@Param("procInstId") String procInstId,
                                    @Param("actionInstId") String actionInstId);

    // ---------- 语义快捷(都走 find) ----------

    /** 某流程的全部补偿/重放记录, 按步骤执行顺序升序(重放接线靠它一次取全, 空即"从没回退过")。 */
    default List<RollbackRecordEntity> findByProcess(String procInstId) {
        return find(procInstId, null);
    }

    /** 某 action 的补偿记录(通常 0/1 条): 取执行顺序最后一条 = 最新。 */
    default RollbackRecordEntity findLatestByAction(String actionInstId) {
        List<RollbackRecordEntity> rows = find(null, actionInstId);
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }
}
