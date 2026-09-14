-- AI 图片生成任务网关 —— 表结构
CREATE DATABASE IF NOT EXISTS painting_gateway
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE painting_gateway;

-- 生成任务主表
CREATE TABLE IF NOT EXISTS t_generation_task
(
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_no             VARCHAR(40)     NOT NULL COMMENT '对外暴露的内部任务号，与供应商任务ID解耦',
    user_id             BIGINT          NOT NULL COMMENT '提交用户',
    task_type           VARCHAR(24)     NOT NULL COMMENT 'TEXT_TO_IMAGE / IMAGE_TO_IMAGE',
    provider            VARCHAR(32)     NOT NULL COMMENT '路由到的供应商标识',
    prompt              VARCHAR(1024)   NOT NULL DEFAULT '',
    status              VARCHAR(20)     NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED',
    total_count         INT             NOT NULL DEFAULT 1 COMMENT '期望出图数量=子请求数量',
    pending_request_ids VARCHAR(2048)   NOT NULL DEFAULT '' COMMENT '未完成的供应商请求ID，逗号分隔',
    retry_count         INT             NOT NULL DEFAULT 0,
    version             INT             NOT NULL DEFAULT 0 COMMENT 'CAS 乐观锁版本号',
    fail_reason         VARCHAR(512)             DEFAULT NULL,
    last_polled_at      DATETIME                 DEFAULT NULL COMMENT '超时回收依据',
    gmt_create          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_no (task_no),
    KEY idx_status_polled (status, last_polled_at) COMMENT '超时回收扫描走此索引，避免行锁退化全表扫描',
    KEY idx_user_create (user_id, gmt_create)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='生成任务';

-- 生成结果图片表
CREATE TABLE IF NOT EXISTS t_generation_image
(
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id             BIGINT UNSIGNED NOT NULL,
    provider_request_id VARCHAR(80)     NOT NULL COMMENT '供应商子请求ID',
    slot_index          INT             NOT NULL DEFAULT 0 COMMENT '图片槽位，保证多图展示顺序稳定',
    image_url           VARCHAR(512)    NOT NULL COMMENT '已转存到自有存储的稳定地址',
    gmt_create          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_request (task_id, provider_request_id) COMMENT '幂等最终防线：并发轮询重复入库直接撞唯一索引',
    KEY idx_task (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='生成结果图片';
