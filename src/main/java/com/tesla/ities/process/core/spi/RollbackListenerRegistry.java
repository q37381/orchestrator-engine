package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.exception.ProcessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * RollbackListener 注册表: 启动时按 name() 收集所有回退级监听器 bean。
 *
 * 与其余三个注册表的唯一差别: 回退监听器是**可选能力**(定义里没声明 rollbackListeners 段也很常见),
 * 所以用 ObjectProvider 注入, 上下文里一个实现都没有时照常启动; 其余注册表沿用 List 注入。
 */
@Component
public class RollbackListenerRegistry {
    private final Map<String, RollbackListener> byName = new HashMap<>();

    public RollbackListenerRegistry(ObjectProvider<RollbackListener> beans) {
        for (RollbackListener l : beans) {
            if (byName.put(l.name(), l) != null) {
                throw new ProcessException("AU204609", l.name());
            }
        }
    }

    public RollbackListener get(String name) {
        RollbackListener l = byName.get(name);
        if (l == null) {
            throw new ProcessException("AU204610", name);
        }
        return l;
    }
    public boolean contains(String name) {
        return byName.containsKey(name);
    }
}
