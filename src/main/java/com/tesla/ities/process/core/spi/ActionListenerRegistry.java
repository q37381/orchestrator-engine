package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.exception.ProcessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ActionListener 注册表: 启动时按 name() 收集所有动作级监听器 bean。 */
@Component
public class ActionListenerRegistry {
    private final Map<String, ActionListener> byName = new HashMap<>();

    public ActionListenerRegistry(List<ActionListener> beans) {
        for (ActionListener l : beans) {
            if (byName.put(l.name(), l) != null) {
                throw new ProcessException("AU204603", l.name());
            }
        }
    }

    public ActionListener get(String name) {
        ActionListener l = byName.get(name);
        if (l == null) {
            throw new ProcessException("AU204604", name);
        }
        return l;
    }
    public boolean contains(String name) {
        return byName.containsKey(name);
    }
}
