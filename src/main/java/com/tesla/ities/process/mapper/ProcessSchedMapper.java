package com.tesla.ities.process.mapper;

import com.tesla.ities.process.domain.ProcessSchedEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * process_sched 数据访问(调度路由表, 独立维护)。SQL 见 META-INF/mapper/process/ProcessSchedMapper.xml。
 *
 * 语义: 创建流程时若给了路由(unit/zone)才 insert 一行 -> 该流程进"主动调度池"; 无行则调度器不主动认领。
 * 认领闸门/状态仍在 process_inst, 本表只提供路由维度(供 ProcessInstMapper.findClaimableIdsByRoute JOIN 过滤)。
 */
@Mapper
public interface ProcessSchedMapper {

    int insert(ProcessSchedEntity sched);

    ProcessSchedEntity findByProcInstId(@Param("procInstId") String procInstId);

    /** 删除某流程的调度行(路由下线/终态清理用); 无行返回 0。 */
    int deleteByProcInstId(@Param("procInstId") String procInstId);
}
