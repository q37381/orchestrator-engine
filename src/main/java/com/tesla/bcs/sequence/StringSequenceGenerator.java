package com.tesla.bcs.sequence;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 序列号生成器 —— 引擎独立编译用的**替身**, 真身在宿主 bcs 依赖里。
 *
 * <h3>引擎对它的唯一要求</h3>
 * 有一个无参 {@code String getStringSequence()}: 一个 Bean 绑一条序列, 调一次取一个号。
 * 宿主工程已有同名类时**整份删掉本文件**即可(签名对上就接得上), 引擎侧那一个注入点
 * (见 {@code IdGenerator.setSequenceBeans} 与 {@code IdKind.beanName()})一个都不用改。
 *
 * <h3>替身行为(只为让引擎脱离宿主也能编译/跑测试)</h3>
 * 每个实例一条从 1 起的内存自增序列, 相当于 {@code CREATE SEQUENCE ... START WITH 1 INCREMENT BY 1};
 * 不持久化、不保证跨进程与跨重启唯一。生产环境必须用宿主那份。
 */
public class StringSequenceGenerator {

    /** 本实例的当前值(每次 getStringSequence() 自增后返回)。 */
    private final AtomicLong seq = new AtomicLong(0);

    /** 序列名(宿主那份一般也有这个属性, 用于配置与日志定位; 引擎不读它, 只读 Bean 名)。 */
    private String name;

    /** 取一个序列号。 */
    public String getStringSequence() {
        return String.valueOf(seq.incrementAndGet());
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
