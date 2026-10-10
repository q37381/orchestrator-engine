package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.define.ListenerDef;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.spi.ActionListenerRegistry;
import com.tesla.ities.process.core.spi.NodeListenerRegistry;
import com.tesla.ities.process.core.spi.ProcessListenerRegistry;
import com.tesla.ities.process.core.spi.RollbackListenerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 监听器引擎(四类监听器 + before/after 两切点, #4/#15)。
 *
 * 链的来源: 定义版本钉死(process_inst.def_version), 定义不可变 -> 推导结果恒定。调用方直接把
 * {@code List<ListenerDef>}(流程级与回退级来自 ProcessDefinition, 节点级来自 NodeInstEntity 组装期挂载,
 * action 级来自 ActionDef; 后两者均已合并全局链 globalNodeListeners/globalActionListeners, 全局在前)
 * 传入, 本类不查库、不转 Map, 保持无状态。
 *
 * 切点语义:
 *  - before(ctx): 抛异常 = 否决(向上传播, 调用方据此落 FAILED / 中止回退)。
 *  - after(ctx, status): 纯通知, 收到最终 status; 异常仅记录, 不改已定终态。
 * 执行顺序 = **声明顺序**(链原序, 不再有 order 排序字段); 按 name() 路由到对应注册表。
 */
@Component
public class ListenerEngine {

    private static final Logger log = LoggerFactory.getLogger(ListenerEngine.class);

    private final ProcessListenerRegistry procReg;
    private final NodeListenerRegistry nodeReg;
    private final ActionListenerRegistry actionReg;
    private final RollbackListenerRegistry rollbackReg;

    public ListenerEngine(ProcessListenerRegistry procReg, NodeListenerRegistry nodeReg,
                          ActionListenerRegistry actionReg, RollbackListenerRegistry rollbackReg) {
        this.procReg = procReg;
        this.nodeReg = nodeReg;
        this.actionReg = actionReg;
        this.rollbackReg = rollbackReg;
    }

    // ===================== PROCESS =====================

    public void processBefore(List<ListenerDef> chain, ProcessContext ctx) throws Exception {
        for (ListenerDef e : chainOf(chain)) {
            procReg.get(e.getName()).before(ctx, config(e));
        }
    }

    public void processAfter(List<ListenerDef> chain, ProcessContext ctx, Status status) {
        for (ListenerDef e : chainOf(chain)) {
            try {
                procReg.get(e.getName()).after(ctx, status, config(e));
            } catch (Exception ex) {
                log.warn("ProcessListener.after 异常(忽略) name={}", e.getName(), ex);
            }
        }
    }

    // ===================== NODE =====================

    public void nodeBefore(List<ListenerDef> chain, NodeContext ctx) throws Exception {
        for (ListenerDef e : chainOf(chain)) {
            nodeReg.get(e.getName()).before(ctx, config(e));
        }
    }

    public void nodeAfter(List<ListenerDef> chain, NodeContext ctx, Status status) {
        for (ListenerDef e : chainOf(chain)) {
            try {
                nodeReg.get(e.getName()).after(ctx, status, config(e));
            } catch (Exception ex) {
                log.warn("NodeListener.after 异常(忽略) name={}", e.getName(), ex);
            }
        }
    }

    // ===================== ACTION =====================

    public void actionBefore(List<ListenerDef> chain, ActionContext ctx) throws Exception {
        for (ListenerDef e : chainOf(chain)) {
            actionReg.get(e.getName()).before(ctx, config(e));
        }
    }

    public void actionAfter(List<ListenerDef> chain, ActionContext ctx, Status status) {
        for (ListenerDef e : chainOf(chain)) {
            try {
                actionReg.get(e.getName()).after(ctx, status, config(e));
            } catch (Exception ex) {
                log.warn("ActionListener.after 异常(忽略) name={}", e.getName(), ex);
            }
        }
    }

    // ===================== ROLLBACK(补偿路径的前置/收尾) =====================

    /** 回退前置(可否决): 抛异常向上传播, 由 RollbackService 中止整轮补偿并记入报告。 */
    public void rollbackBefore(List<ListenerDef> chain, ProcessContext ctx) throws Exception {
        for (ListenerDef e : chainOf(chain)) {
            rollbackReg.get(e.getName()).before(ctx, config(e));
        }
    }

    /** 回退收尾(纯通知): outcome 由 RollbackReport 聚合(FAILED > STOPPED > SUCCESS)。 */
    public void rollbackAfter(List<ListenerDef> chain, ProcessContext ctx, Status outcome) {
        for (ListenerDef e : chainOf(chain)) {
            try {
                rollbackReg.get(e.getName()).after(ctx, outcome, config(e));
            } catch (Exception ex) {
                log.warn("RollbackListener.after 异常(忽略) name={}", e.getName(), ex);
            }
        }
    }

    // ===================== 链解析 =====================

    private List<ListenerDef> chainOf(List<ListenerDef> chain) {
        if (chain == null || chain.isEmpty()) {
            return Collections.emptyList();
        }
        return chain;
    }

    private Map<String, Object> config(ListenerDef e) {
        if (e.getName() == null) {
            throw new ProcessException("AU204308");
        }
        return e.getConfig() == null ? Collections.emptyMap() : e.getConfig();
    }
}
