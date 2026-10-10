package com.tesla.ities.process.web;

import com.tesla.ities.process.api.ProcessExecution;
import com.tesla.ities.process.api.ProcessFacade;
import com.tesla.ities.process.core.engine.RollbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST 入口: 创建/启动/干预/进度/回退。
 * 简单同步返回, 长流程执行由后台调度器驱动。业务键均为 varchar(20) 字符串。
 * 执行类入口(create?autoStart / start)返 procInstId + status; 其余保原语义。
 *
 * <p>刻意放在 {@code process.web} 而非 {@code process.api}: 引擎的组件扫描范围是 core/api/util
 * ({@link com.tesla.ities.process.config.ProcessEngineConfig}), 本类依赖 optional 的 spring-webmvc,
 * 落进扫描范围会让**没引 webmvc 的消费者**在容器预实例化时 NoClassDefFoundError。
 * 需要 HTTP 入口的消费者自行显式注册本包(或只取本类)。
 */
@RestController
@RequestMapping("/api/orchestrator")
public class OrchestratorController {

    private final ProcessFacade facade;

    public OrchestratorController(ProcessFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/processes")
    public Map<String, Object> create(@RequestBody CreateReq req) {
        // userId 由调用方传; 为 null 则不记创建人(引擎不自取会话)
        if (req.autoStart == null || !req.autoStart) {
            return ok("procInstId", facade.create(req.defId, req.version, req.params, req.userId));
        }
        return execution(facade.createAndStart(req.defId, req.version, req.params, req.userId));
    }

    @PostMapping("/processes/{id}/start")
    public Map<String, Object> start(@PathVariable String id) {
        return execution(facade.start(id));
    }

    @PostMapping("/processes/{id}/pause")
    public Map<String, Object> pause(@PathVariable String id, @RequestParam(defaultValue = "system") String operator) {
        return ok("paused", facade.pause(id, operator));
    }

    @PostMapping("/processes/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id, @RequestParam(defaultValue = "system") String operator) {
        return ok("resumed", facade.resume(id, operator));
    }

    @PostMapping("/processes/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id, @RequestParam(defaultValue = "system") String operator) {
        return ok("stopped", facade.stop(id, operator));
    }

    @PostMapping("/processes/{id}/rollback")
    public ResponseEntity<RollbackService.RollbackReport> rollback(
            @PathVariable String id, @RequestParam(defaultValue = "system") String operator) {
        facade.requestRollback(id, operator);
        return ResponseEntity.ok(facade.doRollback(id, operator));
    }

    /** 重试整个流程(以流程实例发起): 复位所有没跑成的行并重新入队, 重入即从断点续跑。 */
    @PostMapping("/processes/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id,
                                     @RequestParam(defaultValue = "system") String operator) {
        return ok("retried", facade.retry(id, operator));
    }

    @PostMapping("/actions/{actionInstId}/retry")
    public Map<String, Object> retryAction(@PathVariable String actionInstId,
                                           @RequestParam(defaultValue = "system") String operator) {
        return ok("retried", facade.retryAction(actionInstId, operator));
    }

    @PostMapping("/actions/{actionInstId}/skip")
    public Map<String, Object> skipAction(@PathVariable String actionInstId,
                                          @RequestParam(defaultValue = "system") String operator) {
        return ok("skipped", facade.skipAction(actionInstId, operator));
    }

    @GetMapping("/processes/{id}/progress")
    public Map<String, Object> progress(@PathVariable String id) {
        return facade.progress(id);
    }

    /** 执行类入口的统一响应: 流程实例 id + 状态(小写规范值) + 坏终态时那一条失败摘要。 */
    private Map<String, Object> execution(ProcessExecution e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("procInstId", e.getProcInstId());
        m.put("status", e.getStatusName());
        m.put("error", e.getError());
        return m;
    }

    private Map<String, Object> ok(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    public static class CreateReq {
        public String defId;
        public Integer version;
        public Boolean autoStart;
        public Map<String, Object> params;
        public String userId;    // 可选: 显式指定创建人; 缺省则由引擎自动捕获
    }
}
