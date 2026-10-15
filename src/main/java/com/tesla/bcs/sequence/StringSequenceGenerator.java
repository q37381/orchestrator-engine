package com.tesla.bcs.sequence;

/**
 * 序列号生成器 —— 引擎独立编译用的**替身接口**, 真身在宿主 bcs 依赖里。
 *
 * <h3>引擎对它的唯一要求</h3>
 * 有一个无参 {@code String getStringSequence()}: 一个 Bean 绑一条序列, 调一次取一个号。
 * 宿主工程已有同名接口时**整份删掉本文件**即可(方法签名对上就接得上), 引擎侧那一个注入点
 * (见 {@code IdGenerator.setSequenceBeans} 与 {@code IdKind.beanName()})一个都不用改。
 *
 * <h3>为什么声明成接口</h3>
 * 号源怎么实现(DB 序列 / 号段 / 内存自增)由宿主定, 引擎不挑实现类也不读序列名——引擎的口径是
 * <b>Bean 名</b>(见 {@code IdKind.beanName()}), 所以这里只留取号这一个抽象方法, 不带任何字段与属性。
 * 单测里的内存自增实现见 {@code MiscUnitTest}(从 1 起自增, 不持久化、不保证跨进程与跨重启唯一)。
 */
public interface StringSequenceGenerator {

    /** 取一个序列号。 */
    String getStringSequence();
}
