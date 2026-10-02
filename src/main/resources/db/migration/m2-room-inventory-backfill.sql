-- M2 历史库存回填（MySQL 8），保持停写，room_inventory 必须为空。
-- 分两段手工执行，不直接执行整份文件：先人工核对步骤 1，无冲突才执行步骤 2。
-- 仅执行一次；重做先 TRUNCATE room_inventory。回填后核对 I1/I2，再启动 S04 版本。

-- 步骤 1：冲突清单。应当无输出；有输出就停止并人工处理，再重跑步骤 1。
WITH RECURSIVE stay AS (
    SELECT id AS order_id, room_id, DATE(checkin_time) AS stay_date, DATE(checkout_time) AS end_date
    FROM room_order
    WHERE status = 0 AND is_deleted = 0 AND room_id IS NOT NULL AND checkout_time > NOW()
      AND DATE(checkin_time) < DATE(checkout_time)
    UNION ALL
    SELECT order_id, room_id, stay_date + INTERVAL 1 DAY, end_date FROM stay
    WHERE stay_date + INTERVAL 1 DAY < end_date
)
SELECT room_id, stay_date, COUNT(*) AS orders, GROUP_CONCAT(order_id ORDER BY order_id) AS order_ids
FROM stay GROUP BY room_id, stay_date HAVING COUNT(*) > 1 ORDER BY room_id, stay_date;

-- 步骤 2：回填。单条语句，原子；存在冲突时因唯一键失败，room_inventory 保持为空。
INSERT INTO room_inventory (room_id, stay_date, order_id)
WITH RECURSIVE stay AS (
    SELECT id AS order_id, room_id, DATE(checkin_time) AS stay_date, DATE(checkout_time) AS end_date
    FROM room_order
    WHERE status = 0 AND is_deleted = 0 AND room_id IS NOT NULL AND checkout_time > NOW()
      AND DATE(checkin_time) < DATE(checkout_time)
    UNION ALL
    SELECT order_id, room_id, stay_date + INTERVAL 1 DAY, end_date FROM stay
    WHERE stay_date + INTERVAL 1 DAY < end_date
)
SELECT room_id, stay_date, order_id FROM stay ORDER BY room_id, stay_date;
