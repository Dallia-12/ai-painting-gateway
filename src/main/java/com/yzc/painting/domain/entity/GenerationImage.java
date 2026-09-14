package com.yzc.painting.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_generation_image")
public class GenerationImage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** 与 taskId 组成唯一索引，是重复入库的最终防线 */
    private String providerRequestId;

    private Integer slotIndex;

    private String imageUrl;

    @TableField(value = "gmt_create", fill = FieldFill.INSERT)
    private LocalDateTime gmtCreate;
}
