package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.exception.ProcessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ShardResolver 注册表: 启动时按 name() 收集所有 bean。 */
@Component
public class ShardResolverRegistry {
    private final Map<String, ShardResolver> resolvers = new HashMap<>();

    public ShardResolverRegistry(List<ShardResolver> beans) {
        for (ShardResolver r : beans) {
            resolvers.put(r.name(), r);
        }
    }

    public ShardResolver get(String name) {
        ShardResolver r = resolvers.get(name);
        if (r == null) {
            throw new ProcessException("AU204611", name);
        }
        return r;
    }
}
