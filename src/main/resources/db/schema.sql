-- HotelSystemBackend 建表脚本（MySQL 8）。
-- 表和列以 src/main/resources/mapper/*.xml 的 SQL 与 entity 为准；可重复执行（IF NOT EXISTS），不含 CREATE DATABASE / USE。
-- 用法：先建库 CREATE DATABASE HotelSystem DEFAULT CHARACTER SET utf8mb4; 再 mysql -uroot -p HotelSystem < schema.sql
-- 状态取值见 constant 包；is_deleted：0 正常、1 已删除（软删除）。
-- INSERT 语句不写、依赖默认值的列：is_deleted（默认 0）、room_order.total_amount（默认 NULL）、meal_order_item.total_price（默认 NULL）。

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
