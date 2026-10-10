package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.exception.ProcessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** NodeListener 注册表: 启动时按 name() 收集所有节点级监听器 bean。 */
@Component
public class NodeListenerRegistry {
    private final Map<String, NodeListener> byName = new HashMap<>();

    public NodeListenerRegistry(List<NodeListener> beans) {
        for (NodeListener l : beans) {
            if (byName.put(l.name(), l) != null) {
                throw new ProcessException("AU204605", l.name());
            }
        }
    }

    public NodeListener get(String name) {
        NodeListener l = byName.get(name);
        if (l == null) {
            throw new ProcessException("AU204606", name);
        }
        return l;
    }
    public boolean contains(String name) {
        return byName.containsKey(name);
    }
}
