package com.tt.ii.process.core.runtime;

import com.tt.ii.process.constants.ReservedKeys;
import com.tt.ii.process.domain.ProcessInstEntity;
import com.tt.ii.process.util.Json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 停止先收尾(settleOnStop)的两枚流程级 config 保留键口径(写入/读取的唯一地点):
 *
 *  - {@link ReservedKeys#SETTLE_ON_STOP}: **创建期**由 PlanService 从定义钉进 process_inst.config,
 *    之后不再变(定义版本本就钉死); 停止入口据此决定"两阶段"还是"硬停"。
 *  - {@link ReservedKeys#STOP_REQUESTED}: **停止请求**的唯一信号。stop() 写入后, 取消闸门
 *    (CancellationRegistry)读它当"已取消", 驱动侧据此在动作边界收尾; 收尾轮落终态后清除,
 *    retry 重新驱动前也清除(否则重跑第一轮就会被判定为又要收尾)。
 *
 * 为什么放 config: owner 的脏桶刷写(ProcessStateFlusher)只写 vars/result, 不会覆盖 config,
 * 别的进程写进去的标识不会被驱动线程冲掉; config 也只在创建时写过一次, 没有并发写竞争。
 * 读一律**容错**: config 是引擎自己写的 JSON, 读坏不外抛(闸门在每步都判, 不能因一行坏数据卡死)。
 */
public final class StopRequest {

    private StopRequest() {
    }

    /** 本流程是否启用了"停止先收尾"(创建期钉死的策略; 未钉/旧行 = 硬停)。 */
    public static boolean settleOnStop(ProcessInstEntity p) {
        return p != null && flag(p.getConfig(), ReservedKeys.SETTLE_ON_STOP);
    }

    /** 本流程是否已有未消化的停止请求(两阶段停止的进行中状态)。 */
    public static boolean isRequested(ProcessInstEntity p) {
        return p != null && flag(p.getConfig(), ReservedKeys.STOP_REQUESTED);
    }

    /** 在 config JSON 上并入 __stopRequested=true, 返回新 JSON(原 JSON 的业务键原样保留)。 */
    public static String markConfig(String configJson) {
        Map<String, Object> cfg = new LinkedHashMap<>(read(configJson));
        cfg.put(ReservedKeys.STOP_REQUESTED, Boolean.TRUE);
        return Json.write(cfg);
    }

    /**
     * 摘掉 __stopRequested, 返回新 JSON; **本来就没有标识时返回 null**(调用方据此跳过写库)。
     * 其余 config 键原样保留(创建输入不能因清标识被抹掉)。
     */
    public static String clearConfig(String configJson) {
        Map<String, Object> cfg = new LinkedHashMap<>(read(configJson));
        if (cfg.remove(ReservedKeys.STOP_REQUESTED) == null) {
            return null;
        }
        return Json.write(cfg);
    }

    private static boolean flag(String configJson, String key) {
        // 热路径(闸门每步都判): 没有这个键就绝不走 JSON 解析——绝大多数流程的 config 里根本没这两枚键
        if (configJson == null || configJson.indexOf(key) < 0) {
            return false;
        }
        return Boolean.TRUE.equals(read(configJson).get(key));
    }

    private static Map<String, Object> read(String configJson) {
        try {
            return Json.readMap(configJson);
        } catch (RuntimeException e) {
            return Collections.emptyMap();
        }
    }
}
