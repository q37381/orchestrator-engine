package com.tesla.ities.process.mapper;

import com.tesla.ities.process.core.model.DefinitionStatus;
import com.tesla.ities.process.domain.ProcessDefinitionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * process_definition 数据访问(一表多版本, 定义不可变)。
 * SQL 见 resources/mapper/orchestrator/ProcessDefinitionMapper.xml。
 *
 * 精简说明: 原 find / findLatestPublished 只差"按版本定位还是取最新已发布", 合并为一个 {@link #find}。
 *
 * 发布态以枚举 {@link DefinitionStatus} 入参(MyBatis 默认 EnumTypeHandler 按 name() 大写绑定,
 * 与库里 DRAFT/PUBLISHED/ARCHIVED 口径一致), 调用方不再传裸字符串。
 */
@Mapper
public interface ProcessDefinitionMapper {

    /**
     * 取定义: version 非 null 按 (defId, version) 精确定位(唯一键至多一行, status 不参与收窄);
     * version=null 按 status 取最新版本, status 也传 null 即"最新已发布版"(取代原 findLatestPublished)。
     */
    ProcessDefinitionEntity find(@Param("defId") String defId, @Param("version") Integer version,
                                 @Param("status") DefinitionStatus status);

    int insert(ProcessDefinitionEntity v);

    /** 取最新已发布版(version/status 都传 null 即可)。 */
    default ProcessDefinitionEntity findLatestPublished(String defId) {
        return find(defId, null, DefinitionStatus.PUBLISHED);
    }
}
