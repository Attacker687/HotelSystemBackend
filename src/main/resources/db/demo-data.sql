-- 演示数据：只在 dev profile 下自动加载（application-dev.yml），也可以在执行 schema.sql 后手动导入。
-- 固定主键 + INSERT IGNORE，可重复执行。密码字段是旧的 MD5 哈希（登录时兼容，首次登录成功后自动迁移为 BCrypt），明文见注释，仅用于本地演示。

-- 员工：admin / Admin@123（经理），front / Front@123（前台），kitchen / Kitchen@123（餐厅）
INSERT IGNORE INTO staff (id, account, password, role, status)
VALUES (1, 'admin', '0e7517141fb53f21ee439b355b5a1d0a', 1, 1),
       (2, 'front', '16f3756343c54171da808ac541839f41', 2, 1),
       (3, 'kitchen', '68dba7a7ba791a52f6eaa8f66b054e2f', 3, 1);

-- 住客：手机号 13900000000 / User@1234；注册时会同时写一条同名入住人
INSERT IGNORE INTO user (id, name, id_card_number, phone, password, email)
VALUES (1, '演示住客', '110101199001010883', '13900000000', '9eeaf04ead83d91063237f9e99d4caee', 'demo@example.com');
INSERT IGNORE INTO individual (id, name, phone, id_card_number)
VALUES (1, '演示住客', '13900000000', '110101199001010883');

-- 房间：3 层，每层单人间 / 双人间 / 套房各一间，全部空闲；房型默认价 199 / 299 / 499，未设置价格日历
INSERT IGNORE INTO room (id, room_number, room_type, status, floor, capacity, description)
VALUES (101, '101', 0, 0, 1, 1, '单人间，朝南'),
       (102, '102', 1, 0, 1, 2, '双人间，朝南'),
       (103, '103', 2, 0, 1, 3, '套房，带客厅'),
       (201, '201', 0, 0, 2, 1, '单人间'),
       (202, '202', 1, 0, 2, 2, '双人间'),
       (203, '203', 2, 0, 2, 3, '套房'),
       (301, '301', 0, 0, 3, 1, '单人间，高层'),
       (302, '302', 1, 0, 3, 2, '双人间，高层'),
       (303, '303', 2, 0, 3, 3, '套房，高层景观');

-- 菜品分类与菜品（status 1 上架）
INSERT IGNORE INTO category (id, name)
VALUES (1, '中餐'),
       (2, '饮品');
INSERT IGNORE INTO dish (id, name, price, description, category_id, status)
VALUES (1, '宫保鸡丁', 38.00, '经典川菜', 1, 1),
       (2, '番茄炒蛋', 22.00, '家常菜', 1, 1),
       (3, '扬州炒饭', 26.00, '主食', 1, 1),
       (4, '柠檬茶', 12.00, '冷饮', 2, 1),
       (5, '热美式', 18.00, '咖啡', 2, 1);
