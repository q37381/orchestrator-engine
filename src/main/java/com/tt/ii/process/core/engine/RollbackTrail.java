package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.model.RollbackStatus;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.RollbackRecordEntity;
import com.tt.ii.process.mapper.RollbackRecordMapper;
import com.tt.ii.process.util.IdGenerator;
import com.tt.ii.process.util.IdKind;
import com.tt.ii.process.util.Json;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补偿/重放记录(rollback_record)的**统一写入口径**。
 *
 * 前身 {@link RollbackService#record}/{@link RestoreService#record} 与两处 detail() 逐字重复, 各写一遍
 * rollback_record 的字段; 任何一方加字段都会悄悄漂移。现在只留一份: begin(占位) -> update(终态),
 * detail JSON 形状固定为 {@code {"reason":..., "error":...}}(键序与既有落库一致)。
 *
 * 口径:
 *  - begin: 先落占位行(COMPENSATING / RESTORING), 返回 rollbackId; 后续 update 同一行;
 *  - update: 只改 status 与 detail, 不动 creatorId;
 *  - latestCompensated: 只看最新一条(COMPENSATE_FAILED / BLOCKED 仍欠撤销, 下次还要再试);
 *  - history: 按 auto_id 升序返回(由 mapper 保证), 供重放按"最新一条"归并。
 */
@Component
public class RollbackTrail {

    private final RollbackRecordMapper rollbackMapper;
    private final IdGenerator ids;

    public RollbackTrail(RollbackRecordMapper rollbackMapper, IdGenerator ids) {
        this.rollbackMapper = rollbackMapper;
        this.ids = ids;
    }

    /** 写一条占位记录, 返回业务键 rollbackId。 */
    public String begin(String procInstId, ActionInstEntity action, RollbackStatus status,
                        String operator, String reason) {
        String rid = ids.next(IdKind.ROLLBACK_RECORD);
        RollbackRecordEntity r = new RollbackRecordEntity();
        r.setRollbackId(rid);
        r.setProcInstId(procInstId);
        r.setActionInstId(action.getActionInstId());
        r.setActionDefId(action.getActionDefId());
        r.setStatus(status.getStatus());
        r.setOperator(operator);
        r.setCreatorId(operator);           // 发起补偿/重放的用户
        r.setUpdatorId(operator);
        r.setDetail(detail(reason, null));
        rollbackMapper.insert(r);
        return rid;
    }

    /** 把占位行更新为终态(COMPENSATED / COMPENSATE_FAILED / BLOCKED / RESTORED / RESTORE_FAILED)。 */
    public void update(String rollbackId, RollbackStatus status, String operator, String reason, String error) {
        rollbackMapper.updateStatus(rollbackId, status.getStatus(), detail(reason, error), operator);
    }

    /** 该 action 最新一条补偿记录是否已是 COMPENSATED(= 副作用已撤销, 不重复撤销)。 */
    public boolean latestCompensated(String actionInstId) {
        RollbackRecordEntity last = rollbackMapper.findLatestByAction(actionInstId);
        return last != null && RollbackStatus.COMPENSATED.getStatus().equals(last.getStatus());
    }

    /** 某流程的补偿/重放历史(mapper 保证 auto_id 升序)。 */
    public List<RollbackRecordEntity> history(String procInstId) {
        return rollbackMapper.findByProcess(procInstId);
    }

    /** detail JSON 形状固定 reason -> error(键序与既有落库逐字一致)。 */
    private static String detail(String reason, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", reason);
        if (error != null) {
            m.put("error", error);
        }
        return Json.write(m);
    }
}
