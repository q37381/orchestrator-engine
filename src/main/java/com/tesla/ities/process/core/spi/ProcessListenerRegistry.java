package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.exception.ProcessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ProcessListener 注册表: 启动时按 name() 收集所有流程级监听器 bean。 */
@Component
public class ProcessListenerRegistry {
    private final Map<String, ProcessListener> byName = new HashMap<>();

    public ProcessListenerRegistry(List<ProcessListener> beans) {
        for (ProcessListener l : beans) {
            if (byName.put(l.name(), l) != null) {
                throw new ProcessException("AU204607", l.name());
            }
        }
    }

    public ProcessListener get(String name) {
        ProcessListener l = byName.get(name);
        if (l == null) {
            throw new ProcessException("AU204608", name);
        }
        return l;
    }
    public boolean contains(String name) {
        return byName.containsKey(name);
    }
}
