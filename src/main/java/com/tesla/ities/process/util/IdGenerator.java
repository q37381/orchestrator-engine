package com.tesla.ities.process.util;

import com.tesla.bcs.sequence.StringSequenceGenerator;
import com.tesla.ities.process.exception.ProcessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务键生成器: 对外标识用(不暴露物理自增主键 auto_id), 产出落 VARCHAR(32) 业务键列。
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
 * 引擎不自造号(不再有本地时间戳兜底): 取号时该种类没绑上 Bean 就当场抛 {@code ProcessException}(AU204801),
 * 报清缺的是哪个 Bean 名。启动期不受影响(注入是 required = false), 但取号必须有真序列——业务键的唯一性
 * 只能由宿主那条序列保证, 引擎这里给不出第二个号源。
 *
 * <h3>约束</h3>
 *  - 同一类两次取号必须不同(引擎不去重, 撞号直接顶唯一索引)。
 *  - 号源本身负责格式: 业务键列宽 VARCHAR(32), 取值范围由宿主那条序列定, 引擎不拦不改写。
 */
@Component
public class IdGenerator {

    /** IdKind -> 宿主注入的序列号生成器; 装配期写满, 运行期只读。 */
    private final Map<IdKind, StringSequenceGenerator> sequences = new ConcurrentHashMap<>();

    // ========== 注入点: 按 Bean 名整Map收(Spring 把上下文里全部该类型 Bean 按名字给进来) ==========

    /**
     * 上下文里所有 {@link StringSequenceGenerator} Bean(键 = Bean 名)。只挑 {@link IdKind} 认得的五个名字,
     * 名字口径只在 {@code IdKind.beanName()} 定义一次, 所以这里不写 {@code @Qualifier} 也不留五份字符串。
     * 一个都没接上时不被调用(required = false), 五类取号都当场报错。
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

    /** 绑定某一类的序列号生成器(XML 装配/单测/Bean 名对不上时的显式入口); 传 null 表示解绑该类(取号即报错)。 */
    public void setSequence(IdKind kind, StringSequenceGenerator sequence) {
        if (sequence == null) {
            sequences.remove(kind);
        } else {
            sequences.put(kind, sequence);
        }
    }

    /** 本类当前用的生成器, null = 未注入(取号会当场报错)。 */
    public StringSequenceGenerator getSequence(IdKind kind) {
        return sequences.get(kind);
    }

    /**
     * 生成一个业务键。
     *
     * @param kind 业务键种类(决定用哪一条序列 / 哪一个 Bean)
     * @throws ProcessException AU204801 —— 这一类还没绑定序列 Bean(报清缺的是哪个名字)
     */
    public String next(IdKind kind) {
        StringSequenceGenerator sequence = sequences.get(kind);
        if (sequence == null) {
            throw new ProcessException("AU204801", kind.beanName());
        }
        return sequence.getStringSequence();
    }
}
