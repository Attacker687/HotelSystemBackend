---
feature: booking-consistency
status: approved
---

# 目标：为「下单幂等 + 每日库存 + Redis 缓存」出一份可直接拆 Story 开发的技术方案

## 要达成什么

- G1（核心）：同一次下单意图最多生成一张订单。判定：方案写清 Idempotency-Key 的接收、与请求方绑定、请求摘要校验、PROCESSING/SUCCESS/FAILED 状态与重复请求的返回，以及与预订助手确认动作共用 booking_request 的方式；测试要点覆盖顺序重复、并发重复、他人复用、内容不一致、首次失败后重试。
- G2（核心）：同一间房同一晚最多被一张有效订单占用。判定：方案写清 room_inventory 表、按日期升序逐晚占用、全部入口（住客下单、前台开单、预订助手确认、取消含退款、15 分钟超时取消、经理软删除、前台改期）的占用与释放、区间重叠查询的退役、历史数据回填与冲突处理、死锁与锁等待的转换；测试要点含 50 并发抢同一晚（成功 1、冲突 49、500 为 0）和部分重叠多晚订单并发。
- G3（顺带）：房间静态信息与价格日历走 Redis 缓存且不影响正确性。判定：方案写清缓存对象与不缓存的对象、Cache Aside 读取、提交后删除、随机 TTL、空值缓存、Redis 故障降级；测试要点含命中时 SQL 计数为 0、更新后读到新值、Redis 停止时接口仍正常。
- G4（顺带）：不破坏现有行为。判定：现有 640 次测试、预订助手确认流程、营收统计口径不变，CI 保持绿色；方案列出受影响的现有测试与调整方式。

## 范围

- 包括：POST /order 的请求幂等（含前端 app.js 携带 key）、booking_request 表扩展、room_inventory 表与所有占用/释放入口、迁移回填脚本、预订助手查空房与提议改用库存表、房间静态信息与价格日历缓存、相关测试要点、上线与回滚说明；分 M1（幂等）、M2（库存）、M3（缓存）三批交付。
- 不包括：按房型卖房；Kafka 等消息队列；微服务拆分与分布式事务；支付、取消接口的请求幂等；缓存房态、可售状态、订单；性能压测与性能指标承诺；热点键互斥重建（D4 已定不加）。

## 产出物

一份技术方案 docs/nova/booking-consistency/design.md，供后续写测试方案、拆 Story 和开发使用。

## 约束

- PRD 待决策 D1～D5 已定：D1 Idempotency-Key 接口层可选、网页前端必带；D2 区间重叠查询退役，以库存表唯一约束为唯一依据；D3 房态不参与可订判断；D4 不加互斥锁，靠随机 TTL；D5 幂等记录保留 7 天，定时清理。
- 基线为分支 nova/booking-agent（48c56aa，含住客预订助手与 booking_request 表），本功能分支 nova/booking-consistency 从它拉出。
- 复用现有代码与约定：Spring 事务、MyBatis、Testcontainers 测试基建、Fixtures 别名、SqlCounter、C1 错误码约定（冲突 409、内容不一致 422）。
- 定位为简历与演示项目。

## 素材

- 需求：飞书 PRD https://mcn7m001m9qm.feishu.cn/wiki/IY7bwSCAPiS5GjkLHSncOmHMn6f（本地副本 .nova/booking-consistency/design/inputs/PRD.md）
- 代码地图：docs/codemap/hotelsystembackend/（本次增量更新至 48c56aa）
- 既有设计稿：docs/booking-consistency.md
- 程序根：C:\t\hsb\.nova\worktrees\booking-consistency
