package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.exception.ProcessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ActionHandler 注册表: 启动时按 name() 收集所有 bean。 */
@Component
public class ActionHandlerRegistry {
    private final Map<String, ActionHandler> handlers = new HashMap<>();

    public ActionHandlerRegistry(List<ActionHandler> beans) {
        for (ActionHandler h : beans) {
            ActionHandler prev = handlers.put(h.name(), h);
            if (prev != null) {
                throw new ProcessException("AU204601", h.name());
            }
        }
    }

    public ActionHandler get(String name) {
        ActionHandler h = handlers.get(name);
        if (h == null) {
            throw new ProcessException("AU204602", name);
        }
        return h;
    }

    public boolean contains(String name) {
        return handlers.containsKey(name);
    }
}
