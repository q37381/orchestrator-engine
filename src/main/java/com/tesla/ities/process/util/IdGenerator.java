package com.tesla.ities.process.util;

import com.tesla.bcs.sequence.StringSequenceGenerator;
import com.tesla.ities.process.exception.ProcessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务键生成器: 对外标识用(不暴露物理自增主键 auto_id), 产出落 VARCHAR(20) 业务键列。
 *
 * <h3>一类业务键 = 一条序列 = 一个 Bean</h3>
 * 取号一律带上种类: {@code ids.next(IdKind.PROCESS_INST)}, 每个 {@link IdKind} 绑宿主上下文里的
 * 一个 {@link StringSequenceGenerator} Bean(一个 Bean 就是一条序列), 无参 {@code getStringSequence()} 取号:
 * <pre>{@code
 * IdKind.PROCESS_INST     -> processInstSequenceGenerator      -> process_inst.proc_inst_id
 * IdKind.NODE_INST        -> nodeInstSequenceGenerator         -> process_node_inst.node_inst_id
 * IdKind.LANE             -> laneSequenceGenerator             -> process_lane.lane_id
 * IdKind.ACTION_INST      -> actionInstSequenceGenerator       -> process_action_inst.action_inst_id
 * IdKind.ROLLBACK_RECORD  -> rollbackRecordSequenceGenerator   -> process_rollback_record.rollback_id
 * }</pre>
 * 注入只有一个点: Spring 把上下文里全部 {@link StringSequenceGenerator} Bean <b>按名字</b>给进来
 * ({@link #setSequenceBeans(Map)}), 引擎只挑 {@link IdKind#beanName()} 认得的五个名字——Bean 名在枚举里
 * 定义一次, 这里不留第二份字符串。<b>引擎代码零改动</b>即可接管; 名字对不上时改枚举那五行, 或用
 * {@link #setSequence(IdKind, StringSequenceGenerator)} 显式绑, 调用点一律不动。
 *
 * <h3>没接序列设施时的行为</h3>
 * 未注入的种类回落本地时间戳序列(首次回落打一条 WARN 列出还缺哪些 Bean), 让引擎自带定义、单测、
 * 本地跑都不必搭序列设施; 生产上五个 Bean 齐了就不会看到这条 WARN。
 *
 * <h3>约束</h3>
 *  - 业务键列宽 VARCHAR(20): 取号超长/取空当场抛 {@code ProcessException}(AU204801/AU204802), 不留到 SQL 报模糊错。
 *  - 同一类两次取号必须不同(引擎不去重, 撞号直接顶唯一索引)。
 */
@Component
public class IdGenerator {

    private static final Logger log = LoggerFactory.getLogger(IdGenerator.class);

    /** 业务键列宽(五张表的 *_id 一致)。 */
    private static final int MAX_LENGTH = 20;

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /** 本地兜底序列(仅在某个种类没注入 Bean 时用到)。 */
    private final AtomicLong localSeq = new AtomicLong(0);
    private final long epoch = 1_700_000_000_000L;   // 固定纪元, 压缩位宽

    /** IdKind -> 宿主注入的序列号生成器; 装配期写满, 运行期只读。 */
    private final Map<IdKind, StringSequenceGenerator> sequences = new ConcurrentHashMap<>();

    /** 兜底告警只打一次(缺哪几个 Bean 名在一条日志里列全, 不逐次刷屏)。 */
    private final AtomicBoolean fallbackWarned = new AtomicBoolean(false);

    // ========== 注入点: 按 Bean 名整Map收(SPRING 把上下文里全部该类型 Bean 按名字给进来) ==========

    /**
     * 上下文里所有 {@link StringSequenceGenerator} Bean(键 = Bean 名)。只挑 {@link IdKind} 认得的五个名字,
     * 名字口径只在 {@code IdKind.beanName()} 定义一次, 所以这里不写 {@code @Qualifier} 也不留五份字符串。
     * 一个都没接上时不被调用(required = false), 五类一律走本地兜底序列。
     */
    @Autowired(required = false)
    public void setSequenceBeans(Map<String, StringSequenceGenerator> beans) {
        if (beans == null) {
            return;
        }
        for (IdKind kind : IdKind.values()) {
            setSequence(kind, beans.get(kind.beanName()));
        }
    }

    /** 绑定某一类的序列号生成器(XML 装配/单测/Bean 名对不上时的显式入口); 传 null 表示该类走本地兜底。 */
    public void setSequence(IdKind kind, StringSequenceGenerator sequence) {
        if (sequence == null) {
            sequences.remove(kind);
        } else {
            sequences.put(kind, sequence);
        }
    }

    /** 本类当前用的生成器, null = 未注入(本地兜底)。 */
    public StringSequenceGenerator getSequence(IdKind kind) {
        return sequences.get(kind);
    }

    /** 还没接上序列设施的 Bean 名(启动自检与告警用)。 */
    public List<String> missingSequenceBeanNames() {
        List<String> missing = new ArrayList<>();
        for (IdKind kind : IdKind.values()) {
            if (!sequences.containsKey(kind)) {
                missing.add(kind.beanName());
            }
        }
        return missing;
    }

    /**
     * 生成一个业务键。
     *
     * @param kind 业务键种类(决定用哪一条序列 / 哪一个 Bean)
     */
    public String next(IdKind kind) {
        StringSequenceGenerator sequence = sequences.get(kind);
        if (sequence == null) {
            warnFallbackOnce();
            return guard(local(), kind);
        }
        return guard(sequence.getStringSequence(), kind);
    }

    /** 空值/超长当场拦(空业务键会落成一条查不到的死行, 超长顶 VARCHAR(20))。 */
    private String guard(String id, IdKind kind) {
        if (id == null || id.trim().isEmpty()) {
            throw new ProcessException("AU204801", kind.beanName());
        }
        if (id.length() > MAX_LENGTH) {
            throw new ProcessException("AU204802", kind.beanName(), String.valueOf(id.length()));
        }
        return id;
    }

    /** 兜底实现: 时间戳 + 自增序列 组合成单调递增 long, 再 base62 编码(约 11 位, 远小于 20)。 */
    private String local() {
        long ts = System.currentTimeMillis() - epoch;          // ~41 bit
        long n = localSeq.incrementAndGet() & 0xFFFF;          // 16 bit 同毫秒内序列
        return base62((ts << 16) | n);                         // 正数, < 2^57
    }

    private void warnFallbackOnce() {
        if (fallbackWarned.compareAndSet(false, true)) {
            List<String> missing = missingSequenceBeanNames();
            if (!missing.isEmpty()) {
                log.warn("业务键序列未装配, 以下种类回落本地时间戳序列(生产请提供同名 Bean): {}", missing);
            }
        }
    }

    private String base62(long v) {
        if (v == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        while (v > 0) {
            sb.append(ALPHABET[(int) (v % 62)]);
            v /= 62;
        }
        return sb.reverse().toString();
    }
}
