package com.tesla.ities.process.unit;

import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessDefinitionEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.domain.RollbackRecordEntity;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 持久化实体 POJO 契约: 每个字段都能经 setX/getX 往返(含时间/布尔/整型/字符串/对象/transient)。
 * 用反射逐字段遍历, 一处覆盖所有 getter/setter, 不随字段增减而漏测。
 *
 * 实体没有协作者(纯 POJO), 因此本类只用统一的 {@code MockitoJUnitRunner} 口径跑, 不涉及 @Mock/@InjectMocks。
 */
@RunWith(MockitoJUnitRunner.class)
public class EntityTest {

    private static final List<Class<?>> ENTITIES = Arrays.asList(
            ActionInstEntity.class, LaneEntity.class, NodeInstEntity.class,
            ProcessDefinitionEntity.class, ProcessInstEntity.class, RollbackRecordEntity.class);

    private static Object sampleValue(Class<?> type) {
        if (type == String.class) {
            return "v";
        }
        if (type == Long.class || type == long.class) {
            return 7L;
        }
        if (type == Integer.class || type == int.class) {
            return 3;
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.TRUE;
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.now();
        }
        return null;   // 其余引用类型(枚举/接口/List)以 null 往返即可覆盖访问器
    }

    @Test
    public void everyFieldRoundTripsThroughAccessors() throws Exception {
        for (Class<?> cls : ENTITIES) {
            Object bean = cls.getDeclaredConstructor().newInstance();
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                String cap = Character.toUpperCase(f.getName().charAt(0)) + f.getName().substring(1);
                Method setter = cls.getMethod("set" + cap, f.getType());
                Method getter = cls.getMethod("get" + cap);
                Object value = sampleValue(f.getType());
                setter.invoke(bean, value);
                Assert.assertEquals(cls.getSimpleName() + "." + f.getName(), value, getter.invoke(bean));
            }
        }
    }
}
