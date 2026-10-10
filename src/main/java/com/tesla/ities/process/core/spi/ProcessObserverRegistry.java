package com.tesla.ities.process.core.spi;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OrchestrationObserver 注册表: 启动时收集所有全局观察者 Bean, 按声明顺序保持。
 */
@Component
public class ProcessObserverRegistry {
    private final List<ProcessObserver> all;

    public ProcessObserverRegistry(List<ProcessObserver> beans) {
        this.all = beans == null ? Collections.emptyList() : new ArrayList<>(beans);
    }

    public List<ProcessObserver> all() {
        return all;
    }
}
