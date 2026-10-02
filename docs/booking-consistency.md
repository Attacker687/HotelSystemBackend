# 并发预订与幂等设计

> 文档状态：M1 下单幂等、M2 每日库存与 M3 静态详情和展示价格缓存均已实现；下单、改期、释放及助手只读查询接齐，可售结果不缓存。旧库先执行一次 M1 ALTER，再停写建库存表、人工核对冲突、回填和核对后启动当前版本。部署顺序见 [README](../README.md#每日库存建表与回填m2)。

## 1. 问题定义

酒店预订与普通商品库存不同：一次订单会占用同一客房的一段连续日期。如果只检查“房间当前是否可用”，两个请求可能同时通过检查并创建重叠订单，形成超卖。

同时，客户端可能因为网络超时、重复点击或网关重试多次提交相同请求。如果没有幂等控制，同一业务意图可能创建多个订单。

因此预订链路需要同时满足四个不变量：

1. 同一客房、同一入住日期最多被一个有效订单占用；
2. 一次入住区间要么全部日期成功，要么全部失败；
3. 同一个 `requestId` 最多创建一个业务订单；
4. 缓存不能成为库存是否可售的最终判断依据。

## 2. 为什么选择每日库存模型

假设订单入住区间为 `[checkIn, checkOut)`，即退房日不占库存。将其拆为每天的库存记录：

```text
2026-08-07 -> room 1208
2026-08-08 -> room 1208
2026-08-09 -> room 1208
```

数据库通过 `(room_id, stay_date)` 唯一键表达最小冲突单元。行存在即表示被该订单占用，无行表示可订；唯一约束裁决并发结果，房态不参与判定。

### 已提供的库存表结构

```sql
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
```

新库 [schema.sql](../src/main/resources/db/schema.sql) 和旧库 [m2-room-inventory.sql](../src/main/resources/db/migration/m2-room-inventory.sql) 使用相同 DDL，无外键。M1 的请求幂等结构见 `schema.sql` 中的 `booking_request`，普通下单与助手动作共用全局请求号唯一键。

### 历史订单回填与核对

手工脚本 [m2-room-inventory-backfill.sql](../src/main/resources/db/migration/m2-room-inventory-backfill.sql) 先用递归 CTE 生成冲突清单：同房同晚超过一单时输出房间、日期、数量和升序订单号。**步骤 1 必须人工核对，无输出才单独执行步骤 2，不能直接运行整份文件。**有冲突必须停止并处理历史订单，再重跑步骤 1。

步骤 2 是单条 `INSERT…WITH RECURSIVE`，只展开 status=0、未软删除、有房间且 `checkout_time > NOW()` 的订单，从入住日到离店日前一天；已入住单也包含过去晚。旧订单可能没有夜价，因此回填不依赖 `room_order_night`。同房同日冲突触发唯一键错误，整条 INSERT 原子失败，空库存仍为空。

回填后运行以下 SQL，两条都应无输出：

```sql
-- I1：离店时间在未来的有效订单，占用数量应等于完整夜集合。
SELECT o.id FROM room_order o
WHERE o.status = 0 AND o.is_deleted = 0 AND o.room_id IS NOT NULL AND o.checkout_time > NOW()
  AND (SELECT COUNT(*) FROM room_inventory i WHERE i.order_id = o.id AND i.room_id = o.room_id
         AND i.stay_date >= DATE(o.checkin_time) AND i.stay_date < DATE(o.checkout_time))
      <> DATEDIFF(DATE(o.checkout_time), DATE(o.checkin_time));
-- I2：不允许孤儿、已删除或已取消订单的占用；已完成的历史夜允许保留。
SELECT i.id FROM room_inventory i LEFT JOIN room_order o ON o.id = i.order_id
WHERE o.id IS NULL OR o.is_deleted = 1 OR o.status = 2;
```

必须停旧版、保持停写，依次建表、人工核对冲突、回填、核对 I1/I2 后，接续启动含 S04 库存维护的当前版本（包括 M3）。脚本只执行一次；重做先 `TRUNCATE room_inventory`。回滚 jar 可保留表，但旧版写入不会维护库存，再次上线库存版本前必须重新停写、清空、回填和核对。M3 使用已有 Redis，无额外 DDL。

## 3. 下单事务

```mermaid
flowchart TD
    Start["收到 requestId 与入住区间"] --> Claim{"抢占 requestId"}
    Claim -->|已存在| Existing["查询并返回原订单 / 当前状态"]
    Claim -->|首次请求| Price["服务端按日期升序计算每晚价格"]
    Price --> Order["复用或创建入住人；写订单取得 order_id"]
    Order --> Occupy["按日期升序逐晚 INSERT room_inventory"]
    Occupy --> All{"全部夜插入成功?"}
    All -->|否| Rollback["抛出库存冲突；订单、入住人和已插入的晚全部回滚"]
    All -->|是| Nights["写 room_order_night 夜价"]
    Nights --> Bind["回写 booking_request.order_id"]
    Bind --> Commit["提交事务并返回订单"]
```

核心原则是让“库存抢占、订单写入、幂等记录”位于同一数据库事务中。

### 逐晚插入

```sql
INSERT INTO room_inventory (room_id, stay_date, order_id)
VALUES (:roomId, :stayDate, :orderId);
```

任一晚唯一键冲突返回 409「房间在该时段已被预订」，整个事务回滚。改期同房只更新新旧夜集合的差集，交集行主键不变；换房删除该订单旧占用后占用新房。取消、超时取消和软删除在订单状态更新的同一事务内按 order_id 释放；提前结束只删除今天及以后的晚，正常退房保留历史夜。支付和营收夜价不改库存。

## 4. 幂等请求的并发处理

仅在业务代码中先查再插并不安全：两个并发请求可能同时查询不到记录，然后各自创建订单。最终兜底必须是数据库唯一约束。

```mermaid
stateDiagram-v2
    [*] --> PROCESSING: 首次插入 requestId
    PROCESSING --> SUCCESS: 订单事务提交
    PROCESSING --> FAILED: 400/404 回滚后单独记录
    SUCCESS --> SUCCESS: 重复请求返回原订单
    FAILED --> FAILED: 原样重放确定性失败
```

推荐处理顺序：

1. 尝试插入 `booking_request(request_id, user_id, PROCESSING)`；
2. 插入成功者成为本次请求的执行者；
3. 命中唯一键冲突时，查询已有记录；
4. `SUCCESS` 返回原订单，`FAILED` 原样重放 400/404；409、锁冲突、500 不记录 FAILED，可再次请求；
5. 订单创建成功后，在同一事务中写入 `order_id` 并更新为 `SUCCESS`。

幂等键还应绑定用户与关键请求摘要，避免客户端误用同一个 `requestId` 提交不同参数。

M1 在事务内抢请求号，重复请求等待首单提交后重读并重放；仅锁等待失败且无已提交记录时返回 409「请求处理中」。PROCESSING 不单独提交。记录保留 7 天；同 key 修改内容返回 422，其他身份或角色复用返回 409。

## 5. 跨日期更新与死锁

两个订单可能占用部分重叠日期：

```text
请求 A：8月7日、8月8日、8月9日
请求 B：8月8日、8月9日、8月10日
```

如果两个事务以不同顺序插入库存行，容易形成循环等待。所有请求应按日期升序逐晚插入，例如：

```text
ORDER BY room_id ASC, stay_date ASC
```

同时应：

- 控制事务范围，只包含库存与订单写入；
- 插入库存时死锁或锁等待转 409「该时段预订繁忙，请稍后重试」，其他数据库锁冲突转 409「系统繁忙，请稍后重试」，不自动重试；
- 不在事务中调用支付、短信或其他远程服务；
- 记录 `requestId`、房间和日期范围，便于排查冲突。

## 6. 缓存边界

M3 为客房静态详情和展示价格提供 Cache Aside。`room:detail:{id}` 只包含房号、房型、楼层、容量、描述和图片；命中后实时 SELECT status 并检查未删，保持默认图片和原响应语义。不存在的详情使用 `NULL`，再次命中不查库。

列表与价格日历共用 `price:{roomType}:{yyyy-MM-dd}`，值为原 `PriceCalendar` JSON，未设价日用 `NULL`。列表保留房间过滤、排序、分页及计数，最多三个房型一次 MGET，缺项按房型查当天价并使用原默认价；日历按日期一次 MGET，全命中零 SQL，任一缺失则用完整区间一次 SQL 补齐缺键，仍按日期输出原字段及 null 项。NULL 房型存量行保留原 null 房型和价格，不生成 NULL 房型价格键。

下单、改期、报价与助手搜房的计价直接读价格表，可售直接只读数据库库存且不加锁。房态、订单、库存或可售结果不进入这些缓存；真正的库存抢占以数据库唯一键插入结果为准。

```mermaid
sequenceDiagram
    participant C as Client
    participant S as BookingService
    participant R as Redis
    participant DB as MySQL

    C->>S: 查询客房详情
    S->>R: GET room:detail:{id}
    alt cache hit
        R-->>S: 静态信息
        S->>DB: SELECT status，检查未删
        DB-->>S: 当前房态与存在性
        S-->>C: 静态信息 + 当前房态
    else cache miss
        S->>DB: 查询客房信息
        DB-->>S: room detail
        S->>R: SET + random TTL
        S-->>C: 返回展示数据
    end

    C->>S: 创建订单
    S->>DB: 逐晚 INSERT 每日库存
    DB-->>S: 以唯一键判定成功或冲突
```

有值 TTL 随机 1800～2400 秒，空值 TTL 300 秒，不加互斥重建。房间静态修改/删除与批量价格更新在提交后删键；回滚不删，无外层事务时在 SQL 自动提交后立即删，价格区间一次 DEL。房态更新不删静态详情。`HOTEL_CACHE_ENABLED=false` 跳过缓存读写但仍执行写失效。

Redis 命令超时 1 秒，也作用于登录态读取。缓存读取或 JSON 解码失败按 miss 读库，写入/删除失败忽略，WARN 仅含键前缀和异常类，不含键值或入住人信息；数据库 loader 的业务异常照常返回。直接改库须手工删键。读旧值、写提交、删键、旧读者再写缓存的竞争或 DEL 失败，可能让旧展示值留到 TTL；随机过期只分散重建请求，订单计价与库存裁决始终以数据库为准。

## 7. 失败场景与预期结果

| 场景 | 预期行为 |
| --- | --- |
| 两个不同请求抢占同一客房同一日期 | 一个事务成功，另一个返回库存冲突 |
| 三晚中最后一晚已被占用 | 前两晚的插入随事务全部回滚 |
| 相同 `requestId` 顺序重复提交 | 返回第一次创建的订单，不新增记录 |
| 相同 `requestId` 并发提交 | 唯一索引选出唯一执行者，其余读取已有状态 |
| 订单写入失败 | 库存和幂等记录一并回滚或进入明确失败态 |
| 查询可售后另一个请求已抢先订房 | 可售查询只读库存且不缓存；下单以数据库唯一键为准，返回 409 库存冲突 |
| 数据库发生死锁 | 当前事务回滚，返回 409，不自动重试 |

## 8. 并发测试方案

### 目标场景

使用固定线程池和 `CountDownLatch` 让 50 个请求同时抢占同一客房、同一入住日期，库存表初始为空。

### 验收标准

```text
成功订单数       = 1
有效库存占用数   = 1
库存冲突数       = 49
重复有效订单数   = 0
重复房晚占用数   = 0
事务异常残留记录 = 0
```

### 测试层次

1. **Mapper 集成测试**：验证插入和唯一索引；
2. **Service 并发测试**：验证事务回滚、重复请求和最终数据不变量；
3. **接口压测**：验证 HTTP 层错误码、响应时间与数据库连接池表现；
4. **故障注入**：在库存成功后模拟订单写入失败，确认完整回滚。

压测结果应同时保存测试参数、数据库隔离级别、机器配置和验证 SQL，避免只展示一个无法复现的数字。

## 9. 索引与查询验证

建议重点检查：

```sql
EXPLAIN SELECT id, order_id
FROM room_inventory
WHERE room_id = ?
  AND stay_date >= ? AND stay_date < ?;

EXPLAIN SELECT id, order_id, status
FROM booking_request
WHERE request_id = ?;
```

预期分别命中 `uk_room_inventory_room_date` 与 `uk_booking_request_request_id`。对于范围查询，需要结合真实数据量观察 `type`、`key`、`rows` 和 `Extra`，不能仅以“出现索引名”判定优化完成。

## 10. 方案边界

该方案解决单酒店、单房间粒度下的并发预订与重复提交问题。继续演进时还应考虑：

- 房型库存而非指定房间库存；
- 支付回调幂等和退款状态；
- 定时任务失败重试与补偿；
- 跨服务事务、消息最终一致性和补偿；
- 库存校准任务与异常订单修复工具。
