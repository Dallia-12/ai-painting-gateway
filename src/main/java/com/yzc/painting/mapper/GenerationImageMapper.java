package com.yzc.painting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yzc.painting.domain.entity.GenerationImage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface GenerationImageMapper extends BaseMapper<GenerationImage> {

    @Select("SELECT * FROM t_generation_image WHERE task_id = #{taskId} ORDER BY slot_index")
    List<GenerationImage> selectByTaskId(@Param("taskId") Long taskId);

    /** Redis 状态丢失时，以数据库真实成功数量重算任务状态的依据 */
    @Select("SELECT COUNT(*) FROM t_generation_image WHERE task_id = #{taskId}")
    int countByTaskId(@Param("taskId") Long taskId);
}
