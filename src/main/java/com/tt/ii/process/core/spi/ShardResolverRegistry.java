package com.tt.ii.process.core.spi;

import com.tt.ii.process.exception.ProcessException;
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
            if (resolvers.putIfAbsent(r.name(), r) != null) {
                throw new ProcessException("AU204612", r.name());   // 与 handler/监听器注册表同口径: 重名当场拒
            }
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
