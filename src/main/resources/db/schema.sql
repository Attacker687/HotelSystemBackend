-- HotelSystemBackend 建表脚本（MySQL 8）。
-- 表和列以 src/main/resources/mapper/*.xml 的 SQL 与 entity 为准；可重复执行（IF NOT EXISTS），不含 CREATE DATABASE / USE。
-- 用法：先建库 CREATE DATABASE HotelSystem DEFAULT CHARACTER SET utf8mb4; 再 mysql -uroot -p HotelSystem < schema.sql
-- 状态取值见 constant 包；is_deleted：0 正常、1 已删除（软删除）。
-- INSERT 语句不写的 is_deleted 默认 0；订单金额和逐晚价格由服务端写入。

-- 住客账号
CREATE TABLE IF NOT EXISTS user
(
    id             INT          NOT NULL AUTO_INCREMENT,
    name           VARCHAR(32)  NULL,
    id_card_number VARCHAR(32)  NULL,
    phone          VARCHAR(20)  NOT NULL,
    password       VARCHAR(100) NOT NULL,
    email          VARCHAR(128) NULL,
    last_login     DATETIME     NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '住客账号';

-- 入住人（注册住客和散客都会写）
CREATE TABLE IF NOT EXISTS individual
(
    id             INT         NOT NULL AUTO_INCREMENT,
    name           VARCHAR(32) NULL,
    phone          VARCHAR(20) NULL,
    id_card_number VARCHAR(32) NULL,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_individual_identity (phone, name, id_card_number),
    KEY idx_individual_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '入住人';

-- 员工：role 1 经理 / 2 前台 / 3 餐厅；status 1 启用 / 0 停用
CREATE TABLE IF NOT EXISTS staff
(
    id         INT          NOT NULL AUTO_INCREMENT,
    account    VARCHAR(64)  NOT NULL,
    password   VARCHAR(100) NOT NULL,
    role       INT          NULL,
    status     INT          NULL     DEFAULT 1,
    is_deleted TINYINT      NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_staff_account (account)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '员工';

-- 房间：room_type 0 单人间 / 1 双人间 / 2 套房；status 0 空闲 / 1 占用 / 2 清洁中 / 3 维修中
-- 房间号不设唯一约束：软删除后允许重建同号房间（查重在 service 里按未删除判断）
CREATE TABLE IF NOT EXISTS room
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    room_number VARCHAR(20)  NULL,
    room_type   INT          NULL,
    status      INT          NULL     DEFAULT 0,
    floor       INT          NULL,
    capacity    INT          NULL,
    description VARCHAR(500) NULL,
    image       VARCHAR(500) NULL,
    is_deleted  TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_room_number (room_number),
    KEY idx_room_floor (floor)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '房间';

-- 价格日历：按 (room_type, date) 定位，没有记录的日期用房型默认价
CREATE TABLE IF NOT EXISTS price_calendar
(
    id         INT            NOT NULL AUTO_INCREMENT,
    room_type  INT            NOT NULL,
    date       DATE           NOT NULL,
    price      DECIMAL(10, 2) NULL,
    is_deleted TINYINT        NOT NULL DEFAULT 0,
    created_at DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_price_calendar_type_date (room_type, date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '价格日历';

-- 客房订单：status 0 进行中 / 1 已完成 / 2 已取消；pay_status 0 未支付 / 1 已支付 / 2 已退款
-- user_id 为空表示前台开单
CREATE TABLE IF NOT EXISTS room_order
(
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    user_id       INT            NULL,
    individual_id INT            NULL,
    room_id       BIGINT         NULL,
    checkin_time  DATETIME       NULL,
    checkout_time DATETIME       NULL,
    total_amount  DECIMAL(10, 2) NULL,
    pay_status    INT            NOT NULL DEFAULT 0,
    status        INT            NOT NULL DEFAULT 0,
    comment       VARCHAR(1000)  NULL,
    comment_star  INT            NULL,
    is_deleted    TINYINT        NOT NULL DEFAULT 0,
    created_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_room_order_room_time (room_id, checkin_time, checkout_time),
    KEY idx_room_order_user (user_id),
    KEY idx_room_order_individual (individual_id),
    KEY idx_room_order_checkin (checkin_time),
    KEY idx_room_order_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客房订单';

-- 客房每晚占用：同房同日唯一；S04 接入业务写入，历史订单先在停写窗口回填。
CREATE TABLE IF NOT EXISTS room_inventory
(
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    room_id    BIGINT   NOT NULL,
    stay_date  DATE     NOT NULL,
    order_id   BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_inventory_room_date (room_id, stay_date),
    KEY idx_room_inventory_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客房每晚占用';

-- 客房订单按晚明细：每晚一行，night 为该晚的日期（入住日 … 离店日前一天），price 为下单时该晚的房价，
-- 各晚 price 之和等于 room_order.total_amount。下单时写入；营收、平均房价（ADR）按 night 汇总，
-- 是否计入看所属订单（已支付、未取消、未删除）。改期重算时整单删除后重写。
CREATE TABLE IF NOT EXISTS room_order_night
(
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    room_order_id BIGINT         NOT NULL,
    night         DATE           NOT NULL,
    price         DECIMAL(10, 2) NOT NULL,
    created_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_order_night (room_order_id, night),
    KEY idx_room_order_night_night (night)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客房订单按晚明细';

-- 餐饮主单：order_status 0 新订单 NEW_ORDER / 1 PENDING / 2 已完成 DONE / 3 已取消 CANCELLED
CREATE TABLE IF NOT EXISTS meal_order
(
    id           INT            NOT NULL AUTO_INCREMENT,
    user_id      INT            NULL,
    address      VARCHAR(255)   NULL,
    remarks      VARCHAR(500)   NULL,
    total_amount DECIMAL(10, 2) NULL,
    order_status INT            NOT NULL DEFAULT 0,
    comment      VARCHAR(1000)  NULL,
    comment_star INT            NULL,
    is_deleted   TINYINT        NOT NULL DEFAULT 0,
    created_at   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_meal_order_user (user_id),
    KEY idx_meal_order_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '餐饮主单';

-- 餐饮明细
CREATE TABLE IF NOT EXISTS meal_order_item
(
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    meal_order_id INT            NULL,
    dish_id       BIGINT         NULL,
    quantity      INT            NULL,
    unit_price    DECIMAL(10, 2) NULL,
    total_price   DECIMAL(10, 2) NULL,
    is_deleted    TINYINT        NOT NULL DEFAULT 0,
    created_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_meal_order_item_order (meal_order_id),
    KEY idx_meal_order_item_dish (dish_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '餐饮明细';

-- 菜品：status 1 上架 / 0 下架
CREATE TABLE IF NOT EXISTS dish
(
    id          INT            NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)    NULL,
    price       DECIMAL(10, 2) NULL,
    description VARCHAR(500)   NULL,
    image       VARCHAR(500)   NULL,
    category_id INT            NULL,
    status      INT            NULL     DEFAULT 1,
    is_deleted  TINYINT        NOT NULL DEFAULT 0,
    created_at  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dish_category (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '菜品';

-- 菜品分类
CREATE TABLE IF NOT EXISTS category
(
    id         INT         NOT NULL AUTO_INCREMENT,
    name       VARCHAR(64) NULL,
    is_deleted TINYINT     NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '菜品分类';

-- 退房任务在事务内抢占这一行；同一分钟只允许一个实例执行，失败回滚后允许重试。
CREATE TABLE IF NOT EXISTS scheduler_task_lock
(
    task_name VARCHAR(64) NOT NULL,
    last_run DATETIME NULL,
    owner VARCHAR(36) NULL,
    PRIMARY KEY (task_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '定时任务互斥';

-- 下单 / 助手动作幂等：request_id 全局唯一，归属校验 user_id + requester_role。
-- PROCESSING只存在于未提交事务；网页下单的 400/404 回滚后单独记录 FAILED。
CREATE TABLE IF NOT EXISTS booking_request
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    request_id  VARCHAR(64) NOT NULL,
    user_id     INT         NOT NULL,
    requester_role TINYINT  NOT NULL DEFAULT 0 COMMENT '请求方角色，助手记录默认住客 0',
    action_type VARCHAR(16) NOT NULL,
    order_id    BIGINT      NULL,
    status      VARCHAR(16) NOT NULL,
    request_hash CHAR(64)   NULL COMMENT '请求摘要 SHA-256，助手记录为空',
    fail_status INT         NULL COMMENT 'FAILED 时的 HTTP 状态',
    fail_message VARCHAR(255) NULL COMMENT 'FAILED 时的失败原因',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_request_request_id (request_id),
    KEY idx_booking_request_user (user_id),
    KEY idx_booking_request_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '下单与确认动作幂等记录';
