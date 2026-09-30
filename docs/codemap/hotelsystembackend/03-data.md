# 03 数据

## 1. 表与实体

`src/main/resources/db/schema.sql:1-197` 已定义12张 MySQL 8 / InnoDB / utf8mb4 表；`CREATE TABLE IF NOT EXISTS` 可重复建表，但不迁移已存在表的列 / 索引（例 `src/main/resources/db/schema.sql:87-109`）。dev自动执行schema与demo-data，默认profile不启用这份SQL init（`src/main/resources/application-dev.yml:4-9`、`src/main/resources/application.yml:1`）。下文描述脚本定义，不推断外部已有库实际结构。

| 表 | 关键字段 / 默认值 / 索引 | 实体 / 映射与关系 |
|---|---|---|
| user | INT自增id；phone/password非空，phone唯一；name/id_card_number/email/last_login可空，时间戳默认当前（`src/main/resources/db/schema.sql:8-21`） | `src/main/java/com/winniethepooh/hotelsystembackend/entity/User.java:8-18`；密码列容纳BCrypt（`src/main/java/com/winniethepooh/hotelsystembackend/utils/PasswordUtils.java:17-25`） |
| individual | INT自增id；name/phone/id_card_number可空，created_at默认当前；身份三项是普通组合索引（`src/main/resources/db/schema.sql:24-34`） | `src/main/java/com/winniethepooh/hotelsystembackend/entity/Individual.java:6-11`不含createdAt；注册新建、下单三项匹配复用（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:35-39`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:231-239`） |
| staff | account/password非空，account唯一；role可空、status默认1、is_deleted默认0（`src/main/resources/db/schema.sql:37-49`） | `src/main/java/com/winniethepooh/hotelsystembackend/entity/Staff.java:8-16`；登录只查启用未删（`src/main/resources/mapper/StaffMapper.xml:35-43`） |
| room | BIGINT自增id；String room_number普通索引；room_type/status/floor/capacity可空，status默认0、软删默认0（`src/main/resources/db/schema.sql:51-69`） | `src/main/java/com/winniethepooh/hotelsystembackend/entity/Room.java:9-21`；price不是表列，由价格日历JOIN提供（`src/main/resources/mapper/RoomMapper.xml:110-117`） |
| price_calendar | room_type/date非空且组合唯一；price DECIMAL(10,2)可空、软删默认0（`src/main/resources/db/schema.sql:71-83`） | `src/main/java/com/winniethepooh/hotelsystembackend/entity/PriceCalendar.java:9-14`；按房型+日期共享，UPSERT恢复软删行（`src/main/resources/mapper/RoomMapper.xml:23-29`） |
| room_order | BIGINT自增id；user_id/individual_id/room_id/入住离店/total_amount可空；pay_status/status/is_deleted默认0；房间时段、用户、入住人、入住/下单时刻索引（`src/main/resources/db/schema.sql:85-109`） | `src/main/java/com/winniethepooh/hotelsystembackend/entity/RoomOrder.java:8-24`；user_id空为前台单；reservationId没有DDL列（对照 `src/main/java/com/winniethepooh/hotelsystembackend/entity/RoomOrder.java:13`、`src/main/resources/db/schema.sql:89-108`） |
| room_order_night | BIGINT自增id；room_order_id/night/price非空，(order_id,night)唯一、night索引；每晚价格快照，无is_deleted（`src/main/resources/db/schema.sql:111-124`） | 无专用entity，用Map日期→价格批量插入；改期按order_id物理删除后重写（`src/main/java/com/winniethepooh/hotelsystembackend/mapper/OrderMapper.java:43-45`、`src/main/resources/mapper/OrderMapper.xml:75-83`） |
| meal_order | INT自增id；user_id/address/remarks/total_amount可空；order_status/is_deleted默认0，用户与下单时刻索引（`src/main/resources/db/schema.sql:126-143`） | `src/main/java/com/winniethepooh/hotelsystembackend/entity/MealOrder.java:10-23`；itemList是集合不是列（`src/main/resources/mapper/OrderMapper.xml:356-367`） |
| meal_order_item | BIGINT自增id；meal_order_id/dish_id/quantity/unit_price/total_price可空、软删默认0，订单 / 菜品索引（`src/main/resources/db/schema.sql:145-160`） | `src/main/java/com/winniethepooh/hotelsystembackend/entity/MealOrderItem.java:10-19`；明细响应name来自dish JOIN，后端写unit_price/total_price；页面读取name显示菜名、quantity数量与totalPrice金额（`src/main/resources/mapper/OrderMapper.xml:58-66`、`src/main/resources/mapper/OrderMapper.xml:358-365`、`src/main/resources/static/app.js:356-361`） |
| dish | INT自增id；name/price/category_id/status可空，status默认1、软删默认0、category索引（`src/main/resources/db/schema.sql:162-177`） | `src/main/java/com/winniethepooh/hotelsystembackend/entity/Dish.java:8-16`；category_id关联分类（`src/main/resources/mapper/FoodMapper.xml:60-67`） |
| category | INT自增id；name可空、无名称唯一键，软删默认0（`src/main/resources/db/schema.sql:179-188`） | 查询映射FoodCategoryVO（`src/main/resources/mapper/FoodMapper.xml:54-57`） |
| scheduler_task_lock | task_name VARCHAR64主键；last_run/owner可空，无TTL字段（`src/main/resources/db/schema.sql:190-197`） | owner为实例UUID；本分钟抢占与整批退房同事务（`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:18`、`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:29-40`、`src/main/resources/mapper/OrderMapper.xml:150-157`） |

**金额与默认值**：两条客房INSERT均写total_amount并回填id；前台支付状态取paid，住客强制0（`src/main/resources/mapper/OrderMapper.xml:23-46`）。total_price不是生成列，后端显式赋值（`src/main/resources/db/schema.sql:153`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:211-217`）。staff/category/dish/订单INSERT省略is_deleted，依赖DDL默认0（`src/main/resources/db/schema.sql:44`、`src/main/resources/db/schema.sql:100`、`src/main/resources/db/schema.sql:137`、`src/main/resources/db/schema.sql:154`、`src/main/resources/db/schema.sql:172-184`）。

**逻辑关系，脚本未声明外键**（完整定义 `src/main/resources/db/schema.sql:8-197`）：room_order→room/individual/可空user（`src/main/resources/mapper/OrderMapper.xml:186-191`）；room_order_night→room_order（`src/main/resources/mapper/OrderMapper.xml:198-203`）；meal_order→user、meal_order_item→meal_order/dish（`src/main/resources/mapper/OrderMapper.xml:48-64`）；dish→category（`src/main/resources/mapper/FoodMapper.xml:60-67`）。user与individual没有关联id列，注册和下单复用逻辑不同（`src/main/resources/db/schema.sql:8-34`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:35-39`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:231-239`）。

## 2. 状态字段取值

| 字段 | 取值 | 出处 |
|---|---|---|
| staff.role / JWT role | 0 USER、1 MANAGER、2 FRONT、3 RESTAURANT | `src/main/java/com/winniethepooh/hotelsystembackend/constant/RoleConstant.java:4-7` |
| staff.status | 1启用、0停用 | `src/main/java/com/winniethepooh/hotelsystembackend/constant/StaffStatusConstant.java:4-5` |
| room.room_type / price_calendar.room_type | 0单人间、1双人间、2套房；默认199/299/499 | `src/main/java/com/winniethepooh/hotelsystembackend/constant/RoomTypeConstant.java:6-14` |
| room.status | 0空闲、1占用、2清洁中、3维修中；写入口校验0～3 | `src/main/java/com/winniethepooh/hotelsystembackend/constant/RoomStatusConstant.java:4-7`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:42-44` |
| room_order.status | 0进行中、1完成、2取消 | `src/main/java/com/winniethepooh/hotelsystembackend/constant/RoomOrderStatusConstant.java:5-7` |
| room_order.pay_status | 0未付、1已付、2退款；未来已付订单取消写2 | `src/main/java/com/winniethepooh/hotelsystembackend/constant/RoomOrderPayStatusConstant.java:4-6`、`src/main/resources/mapper/OrderMapper.xml:121-125` |
| meal_order.order_status | 0新单、1 PENDING、2完成、3取消；0→1→2、0→3，USER可取消本人0单 | `src/main/java/com/winniethepooh/hotelsystembackend/constant/MealOrderStatusConstant.java:4-7`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:23-31`、`src/main/resources/mapper/OrderMapper.xml:145-147` |
| dish.status | 1上架、0下架；下单拒绝所有非1值，菜单查询仍显示上下架 | `src/main/resources/db/schema.sql:162-171`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:208-210`、`src/main/resources/mapper/FoodMapper.xml:77-82` |
| Result.code | 0成功、1失败 | `src/main/java/com/winniethepooh/hotelsystembackend/constant/ResultCodeConstant.java:4-5` |

餐饮状态1的展示名仍有常量/注释分歧，见README Q7；Room/RoomOrder状态注释未覆盖全部编码，以常量/SQL为准（`src/main/java/com/winniethepooh/hotelsystembackend/entity/MealOrder.java:16`、`src/main/java/com/winniethepooh/hotelsystembackend/entity/Room.java:14`、`src/main/java/com/winniethepooh/hotelsystembackend/entity/RoomOrder.java:19`）。

## 3. 主要 DTO

| DTO | 定义 / 字段 / 用途 |
|---|---|
| RegisterDTO | name非空≤16、身份证@IdCard、phone/email模式、password必填@Password；POST /user/register（`src/main/java/com/winniethepooh/hotelsystembackend/dto/RegisterDTO.java:12-27`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:39-41`） |
| UserLoginDTO / StaffLoginDTO | phone或account、password，未加@Valid（`src/main/java/com/winniethepooh/hotelsystembackend/dto/UserLoginDTO.java:6-9`、`src/main/java/com/winniethepooh/hotelsystembackend/dto/StaffLoginDTO.java:6-9`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:45-49`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:41-44`） |
| UserInfoChangeDTO | phone/originPassword、可选emailToChange模式与passwordToChange约束（`src/main/java/com/winniethepooh/hotelsystembackend/dto/UserInfoChangeDTO.java:9-15`） |
| StaffRegisterDTO / ModifyStatusDTO | account/password/role/status无约束；id/status房态和员工共用，房态业务校验，员工没有同等值域校验（`src/main/java/com/winniethepooh/hotelsystembackend/dto/StaffRegisterDTO.java:6-11`、`src/main/java/com/winniethepooh/hotelsystembackend/dto/ModifyStatusDTO.java:6-9`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:65-68`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:51-57`） |
| InsertRoomDTO | String房间号、房型、楼层、状态、capacity/description/image（`src/main/java/com/winniethepooh/hotelsystembackend/dto/InsertRoomDTO.java:7-15`） |
| InsertRoomOrderDTO | 入住人name/phone/idCard、roomNumber、必填LocalDateTime checkInTime/checkOutTime、Boolean paid；paid只影响前台（`src/main/java/com/winniethepooh/hotelsystembackend/dto/InsertRoomOrderDTO.java:9-18`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:70-77`） |
| ModifyRoomOrderDTO | String roomId按房间id解释；时刻可空沿用原值，status不参与更新（`src/main/java/com/winniethepooh/hotelsystembackend/dto/ModifyRoomOrderDTO.java:8-13`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:104-128`） |
| CommentOrderDTO | type非空、id必填、comment≤500、star必填1～5（`src/main/java/com/winniethepooh/hotelsystembackend/dto/CommentOrderDTO.java:8-17`） |
| InsertMealOrderDTO | id/userId/address/itemList/remarks/totalAmount；@Valid级联数量；id/userId/单价/总价后端覆盖（`src/main/java/com/winniethepooh/hotelsystembackend/dto/InsertMealOrderDTO.java:11-18`、`src/main/java/com/winniethepooh/hotelsystembackend/entity/MealOrderItem.java:13-18`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:201-221`） |
| CreateFoodCategoryDTO / CreateDishDTO | 分类名 / 菜品字段；改菜品直接Dish实体（`src/main/java/com/winniethepooh/hotelsystembackend/dto/CreateFoodCategoryDTO.java:6-8`、`src/main/java/com/winniethepooh/hotelsystembackend/dto/CreateDishDTO.java:8-15`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:59`） |
| DynamicUpdatePriceDTO | 日期必填、roomType必填0～2、price必填>0（`src/main/java/com/winniethepooh/hotelsystembackend/dto/DynamicUpdatePriceDTO.java:14-25`） |
| DailyRevenueDTO / DailyGuestDTO | Mapper中间结果：date/revenue/nightCount；date/guestCount/repeatGuestCount（`src/main/java/com/winniethepooh/hotelsystembackend/dto/DailyRevenueDTO.java:10-13`、`src/main/java/com/winniethepooh/hotelsystembackend/dto/DailyGuestDTO.java:9-12`） |
| TimeCheckDTO / MealOrderStatusCountDTO | 入住离店 / 餐厅三种状态计数（`src/main/java/com/winniethepooh/hotelsystembackend/dto/TimeCheckDTO.java:8-11`、`src/main/java/com/winniethepooh/hotelsystembackend/dto/MealOrderStatusCountDTO.java:6-10`） |
| ModifyRoomStatusDTO | status字段；生产房态实际使用ModifyStatusDTO（`src/main/java/com/winniethepooh/hotelsystembackend/dto/ModifyRoomStatusDTO.java:6-8`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:44`） |

## 4. 主要 VO

| VO | 字段 / 产出位置 |
|---|---|
| LoginVO | token/id/role（`src/main/java/com/winniethepooh/hotelsystembackend/vo/LoginVO.java:6-10`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:58-62`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:52-56`） |
| PageBean | total/list（`src/main/java/com/winniethepooh/hotelsystembackend/vo/PageBean.java:8-11`）；订单/房间/员工分页，经营明细包装全部日期（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:256-258`） |
| QueryUserVO / StaffVO | 不含密码；住客身份证脱敏（`src/main/java/com/winniethepooh/hotelsystembackend/vo/QueryUserVO.java:8-17`、`src/main/java/com/winniethepooh/hotelsystembackend/vo/StaffVO.java:8-15`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:76-80`） |
| QueryRoomsVO / RoomStatusWallVO | roomNumber统一String；价格由JOIN；墙含individual与当前入住离店（`src/main/java/com/winniethepooh/hotelsystembackend/vo/QueryRoomsVO.java:9-18`、`src/main/java/com/winniethepooh/hotelsystembackend/vo/RoomStatusWallVO.java:10-18`、`src/main/resources/mapper/RoomMapper.xml:110-169`） |
| GetAllRoomOrderVO | 账号/入住人身份、房间号、时刻、状态、评价，无金额/支付状态，JOIN映射（`src/main/java/com/winniethepooh/hotelsystembackend/vo/GetAllRoomOrderVO.java:8-18`、`src/main/resources/mapper/OrderMapper.xml:186-194`） |
| OrderQueryVO / LiveMealOrderVO | 本人两类订单实体列表 / 餐饮计数与带itemList主单（`src/main/java/com/winniethepooh/hotelsystembackend/vo/OrderQueryVO.java:10-13`、`src/main/java/com/winniethepooh/hotelsystembackend/vo/LiveMealOrderVO.java:9-14`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:37-49`） |
| DishVO / FoodCategoryVO | 菜品categoryName；分类id/name（`src/main/java/com/winniethepooh/hotelsystembackend/vo/DishVO.java:8-17`、`src/main/java/com/winniethepooh/hotelsystembackend/vo/FoodCategoryVO.java:6-9`、`src/main/resources/mapper/FoodMapper.xml:54-82`） |
| RevenueStatsVO / RevenueTrendVO | 日/月营收、ADR、入住率及环比 / 日期、营收、入住率数组（`src/main/java/com/winniethepooh/hotelsystembackend/vo/RevenueStatsVO.java:8-17`、`src/main/java/com/winniethepooh/hotelsystembackend/vo/RevenueTrendVO.java:10-14`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:138-165`） |
| RevenueRoomTypeVO / OccupancyHeatmapVO | 房型name/value / dates、floors、[日期下标,楼层下标,入住率]（`src/main/java/com/winniethepooh/hotelsystembackend/vo/RevenueRoomTypeVO.java:8-11`、`src/main/java/com/winniethepooh/hotelsystembackend/vo/OccupancyHeatmapVO.java:9-13`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:174-208`） |
| DishTop10VO / BusinessDetailVO | name/value / date、revenue、roomCount、occupiedCount、occupancyRate、avgPrice、customerCount、newCustomerCount、repeatCustomerRate（`src/main/java/com/winniethepooh/hotelsystembackend/vo/DishTop10VO.java:6-9`、`src/main/java/com/winniethepooh/hotelsystembackend/vo/BusinessDetailVO.java:9-19`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:241-253`） |

## 5. SQL 清单

五份XML仍绑定同名Mapper，Java接口名单见 `src/main/java/com/winniethepooh/hotelsystembackend/mapper/OrderMapper.java:22-103`、`src/main/java/com/winniethepooh/hotelsystembackend/mapper/RoomMapper.java:16-56`、`src/main/java/com/winniethepooh/hotelsystembackend/mapper/UserMapper.java:14-41`、`src/main/java/com/winniethepooh/hotelsystembackend/mapper/StaffMapper.java:11-30`、`src/main/java/com/winniethepooh/hotelsystembackend/mapper/FoodMapper.java:11-32`。删除的旧SQL方法不在本清单中；下表逐项从当前XML与直接调用处生成。

### OrderMapper

| 方法 | 条件 / 写入 | XML出处 | 直接调用方 |
|---|---|---|---|
| insertRoomComment | 仅本人未删已完成客房订单UPDATE评价 | `src/main/resources/mapper/OrderMapper.xml:5-12` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:53` |
| insertMealComment | 仅本人未删已完成餐饮订单UPDATE评价 | `src/main/resources/mapper/OrderMapper.xml:14-21` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:55` |
| insertRoomOrderV1 | 前台：写金额、支付状态参数、status0，回填id，user_id省略 | `src/main/resources/mapper/OrderMapper.xml:23-34` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:94` |
| insertRoomOrderV2 | 住客：写user_id与金额、pay0/status0，回填id | `src/main/resources/mapper/OrderMapper.xml:35-47` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:95` |
| insertMealOrder | 主单user/地址/备注/金额、状态0，回填id | `src/main/resources/mapper/OrderMapper.xml:48-57` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:218` |
| insertMealOrderItem | 写主单id、菜品、数量、单价与total_price | `src/main/resources/mapper/OrderMapper.xml:58-67` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:221` |
| modifyRoomOrder | 未删进行中：整单更新房间、入住离店、总额 | `src/main/resources/mapper/OrderMapper.xml:68-73` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:126` |
| insertRoomOrderNights | foreach日期→价格，一条批量INSERT | `src/main/resources/mapper/OrderMapper.xml:75-80` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:96`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:128` |
| deleteRoomOrderNights | 按order_id物理删除全部夜价 | `src/main/resources/mapper/OrderMapper.xml:82-84` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:127` |
| findOverlappingOrder | 未删进行中，checkin<新checkout且checkout>新checkin；可排除自身；LIMIT1 FOR UPDATE | `src/main/resources/mapper/OrderMapper.xml:86-92` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:181` |
| getRoomOrderByIdForUpdate | id、未删，FOR UPDATE | `src/main/resources/mapper/OrderMapper.xml:94-96` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:114` |
| deleteRoomOrder | 按id软删，不筛原状态 | `src/main/resources/mapper/OrderMapper.xml:98-103` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:136` |
| modifyRoomOrderStatus | 未删id改status和updated_at | `src/main/resources/mapper/OrderMapper.xml:105-111` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:40`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:71` |
| payRoomOrder | 本人/未删/status0/pay0/创建未超15分钟→pay1，单条条件UPDATE | `src/main/resources/mapper/OrderMapper.xml:113-119` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:140` |
| cancelRoomOrder | 本人/未删/status0/pay0或1/checkin>now→status2，已付则pay2 | `src/main/resources/mapper/OrderMapper.xml:121-126` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:148` |
| flushExpiredRoomOrders | 未删进行中/未付/user_id非空/创建超15分钟→取消 | `src/main/resources/mapper/OrderMapper.xml:128-137` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:57` |
| modifyMealOrderStatus | 未删id且原状态相符才改目标状态 | `src/main/resources/mapper/OrderMapper.xml:138-143` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:58` |
| cancelMealOrder | 本人未删状态0→取消3 | `src/main/resources/mapper/OrderMapper.xml:145-148` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:227` |
| ensureTaskLock | 按task_name INSERT IGNORE | `src/main/resources/mapper/OrderMapper.xml:150-152` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:33` |
| claimTaskLock | last_run早于当前分钟起点才写NOW与owner | `src/main/resources/mapper/OrderMapper.xml:154-158` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:34` |
| getMealOrdersByDate | 未删、created_at≥start且<end+1天，可筛user_id | `src/main/resources/mapper/OrderMapper.xml:160-171` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:44` |
| getRoomOrdersByDate | 未删、created_at≥start且<end+1天，可筛user_id | `src/main/resources/mapper/OrderMapper.xml:173-184` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:45` |
| getAllRoomOrderList | 订单/账号/入住人/房间JOIN，未删订单，id排序分页映射VO | `src/main/resources/mapper/OrderMapper.xml:186-195` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:63` |
| getNightRevenueByDate | room_order_night按night分组SUM夜价/COUNT间夜；pay1/status≠2/未删 | `src/main/resources/mapper/OrderMapper.xml:198-209` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:81` |
| getThisTypeRoomRevenueDuringTheTime | 同有效逐晚收入，JOIN room按room_type筛选，无数据0 | `src/main/resources/mapper/OrderMapper.xml:211-221` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:177`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:178`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:179` |
| getTop10Dishes | 含结束日整天，排除取消/删单/删菜；按菜名SUM数量降序LIMIT10，未筛明细软删 | `src/main/resources/mapper/OrderMapper.xml:223-239` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:218` |
| getOccupyingRoomOrders | 有效订单与日期区间交集，一次读取后内存按[入住日,离店日)汇总 | `src/main/resources/mapper/OrderMapper.xml:243-251` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:133`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:154`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:191`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:225` |
| getCheckinGuestCountByDate | 当日有效入住人去重，此前有效入住者作为复住客 | `src/main/resources/mapper/OrderMapper.xml:254-271` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:227` |
| findRoomOrdersToRelease | 未删进行中/(pay1或前台)/checkout≤now | `src/main/resources/mapper/OrderMapper.xml:273-280` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:36` |
| getIndividualByRoomIdAndDate | 覆盖当日含离店日的未删订单JOIN入住人；不筛支付/状态，列表 | `src/main/resources/mapper/OrderMapper.xml:282-291` | 源码检索未见直接调用；声明见对应Mapper |
| getAllRoomOrderCount | 未删除客房单count | `src/main/resources/mapper/OrderMapper.xml:293-297` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:64` |
| getCheckTimeByRoomIdAndTime | 未删进行中当前有效房单，pay1或前台；取入住离店 | `src/main/resources/mapper/OrderMapper.xml:299-310` | 源码检索未见直接调用；声明见对应Mapper |
| findRoomOrdersToEnable | 未删进行中/(pay1或前台)/checkin≤now<checkout | `src/main/resources/mapper/OrderMapper.xml:312-320` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:48` |
| getRoomOrderByRoomIdAndTime | 未删进行中当前有效房单，pay1或前台；单个订单 | `src/main/resources/mapper/OrderMapper.xml:322-332` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:70` |
| getRoomOrderById | id且未删 | `src/main/resources/mapper/OrderMapper.xml:333-338` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:105`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:155` |
| getLiveMealOrderStatusCount | 近24小时未删，状态0/1/2计数，COALESCE0 | `src/main/resources/mapper/OrderMapper.xml:339-347` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:39` |
| getLiveMealOrderList | 近24小时未删所有状态，created_at倒序 | `src/main/resources/mapper/OrderMapper.xml:348-354` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:44` |
| getMealOrderItemsByOrderId | 主单id且明细未删，JOIN dish.name以name字段返回，不筛菜品软删 | `src/main/resources/mapper/OrderMapper.xml:356-368` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:46` |
| getMealOrderByOrderId | id且主单未删 | `src/main/resources/mapper/OrderMapper.xml:370-375` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:55` |

### RoomMapper

| 方法 | 条件 / 写入 | XML出处 | 直接调用方 |
|---|---|---|---|
| lockRoomByNumber | 未删房间号，FOR UPDATE | `src/main/resources/mapper/RoomMapper.xml:5-7` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:82` |
| lockRoomById | 未删房间id，FOR UPDATE | `src/main/resources/mapper/RoomMapper.xml:8-10` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:111`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:112`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:67` |
| enableAvailableRoom | 未删status0→1、updated_at | `src/main/resources/mapper/RoomMapper.xml:11-13` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:50`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:98`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:131` |
| releaseOccupiedRoom | 未删status1→2清洁中、updated_at | `src/main/resources/mapper/RoomMapper.xml:14-16` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:39`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:130` |
| insertRoom | 写DTO房间字段、软删0，不写price | `src/main/resources/mapper/RoomMapper.xml:17-21` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:87` |
| upsertPriceCalendar | foreach批量VALUES，组合唯一键冲突更新价格、恢复软删 | `src/main/resources/mapper/RoomMapper.xml:23-30` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:264` |
| modifyRoomStatus | 未删id改状态与updated_at | `src/main/resources/mapper/RoomMapper.xml:32-37` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:73` |
| modifyRoomInfo | 未删id，动态SET非空DTO字段 | `src/main/resources/mapper/RoomMapper.xml:39-53` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:98` |
| deleteRoom | 未删id软删 | `src/main/resources/mapper/RoomMapper.xml:55-60` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:103` |
| getRoomIdByRoomNumber | 未删房间号→Long id | `src/main/resources/mapper/RoomMapper.xml:65-70` | 源码检索未见直接调用；声明见对应Mapper |
| queryRoomById | id；containDeleted=false时排除软删 | `src/main/resources/mapper/RoomMapper.xml:72-81` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:78`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:92` |
| getExistFloors | 未删房间distinct floor | `src/main/resources/mapper/RoomMapper.xml:83-87` | 源码检索未见直接调用；声明见对应Mapper |
| existByRoomNumber | 未删房间号是否存在 | `src/main/resources/mapper/RoomMapper.xml:89-94` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:84`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:95` |
| getPriceCalendars | 房型+闭日期区间，未删 | `src/main/resources/mapper/RoomMapper.xml:96-101` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:271`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:187` |
| getRoomPriceByTypeAndDate | 房型+单日，未删 | `src/main/resources/mapper/RoomMapper.xml:103-109` | 源码检索未见直接调用；声明见对应Mapper |
| queryRooms | 未删房间，精确筛号/房型/状态，可选日价JOIN、id排序、可选分页 | `src/main/resources/mapper/RoomMapper.xml:110-135` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:113`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:118` |
| getRoomStatusWall | 一条JOIN：日价、当日最小订单id入住人、当前占用时刻，未删房间 | `src/main/resources/mapper/RoomMapper.xml:152-170` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:108` |
| queryRoomsCount | 与列表同精确筛选及未删 | `src/main/resources/mapper/RoomMapper.xml:171-185` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:119` |
| getRoomByRoomNumber | 未删房间号→Room | `src/main/resources/mapper/RoomMapper.xml:186-191` | 源码检索未见直接调用；声明见对应Mapper |

### UserMapper

| 方法 | 条件 / 写入 | XML出处 | 直接调用方 |
|---|---|---|---|
| createUser | 写注册姓名/身份证/手机/哈希/邮箱 | `src/main/resources/mapper/UserMapper.xml:6-15` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:34` |
| updateLastLoginTime | 按id写last_login NOW | `src/main/resources/mapper/UserMapper.xml:17-21` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:51` |
| updatePassword | 按id写新哈希与updated_at | `src/main/resources/mapper/UserMapper.xml:23-27` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:50` |
| modifyUserInfo | 按phone更新非空password/email | `src/main/resources/mapper/UserMapper.xml:29-41` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:71` |
| createIndividual | 写入住人三项与创建时刻，回填id | `src/main/resources/mapper/UserMapper.xml:43-46` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:238`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:39` |
| findUserByPhone | phone→账号，包括哈希，密码不在SQL判断 | `src/main/resources/mapper/UserMapper.xml:48-52` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:31`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:45` |
| findUserById | id→账号 | `src/main/resources/mapper/UserMapper.xml:54-58` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:60` |
| findIndividual | name+phone+身份证三项相等 | `src/main/resources/mapper/UserMapper.xml:60-66` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:232` |
| findIndividualById | id→入住人 | `src/main/resources/mapper/UserMapper.xml:68-72` | 源码检索未见直接调用；声明见对应Mapper |
| findUserByIdV2 | id→QueryUserVO | `src/main/resources/mapper/UserMapper.xml:74-77` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:77` |
| getCustomerCountBefore | individual创建时刻<start累积数 | `src/main/resources/mapper/UserMapper.xml:79-82` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:231` |
| getIndividualCreatedTimes | individual创建时刻≥start且<end+1天 | `src/main/resources/mapper/UserMapper.xml:84-88` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:229` |

### StaffMapper

| 方法 | 条件 / 写入 | XML出处 | 直接调用方 |
|---|---|---|---|
| createStaff | 写account/哈希/role/status | `src/main/resources/mapper/StaffMapper.xml:6-9` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:47` |
| modifyStatus | 按员工id改status，不筛软删 | `src/main/resources/mapper/StaffMapper.xml:11-15` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:52` |
| updatePassword | 按id写新哈希与updated_at | `src/main/resources/mapper/StaffMapper.xml:17-21` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:38` |
| deleteStaff | 按员工id软删 | `src/main/resources/mapper/StaffMapper.xml:23-27` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:66` |
| existStaffByAccount | account查重，含软删 | `src/main/resources/mapper/StaffMapper.xml:29-33` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:44` |
| getActiveStaffByAccount | account且启用未删，不在SQL判断密码 | `src/main/resources/mapper/StaffMapper.xml:35-39` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:33` |
| getStaffById | 未删id | `src/main/resources/mapper/StaffMapper.xml:40-44` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:57`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:65` |
| getStaffList | 未删、可选role和account LIKE、创建倒序分页 | `src/main/resources/mapper/StaffMapper.xml:45-57` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:76` |
| getStaffListTotal | 列表同筛选count | `src/main/resources/mapper/StaffMapper.xml:59-69` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:77` |

### FoodMapper

| 方法 | 条件 / 写入 | XML出处 | 直接调用方 |
|---|---|---|---|
| getDishById | id且未删；Service再判status1 | `src/main/resources/mapper/FoodMapper.xml:5-7` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:208` |
| createFoodCategory | 写分类name | `src/main/resources/mapper/FoodMapper.xml:8-13` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:29` |
| createDish | 写全部菜品DTO字段 | `src/main/resources/mapper/FoodMapper.xml:15-25` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:40` |
| modifyDish | id动态改非空字段，不筛软删 | `src/main/resources/mapper/FoodMapper.xml:27-39` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:45` |
| deleteDish | 按id软删 | `src/main/resources/mapper/FoodMapper.xml:41-46` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:50`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:62` |
| deleteCategory | 按id软删分类 | `src/main/resources/mapper/FoodMapper.xml:48-52` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:63` |
| getFoodCategory | 未删id/name | `src/main/resources/mapper/FoodMapper.xml:54-58` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:23` |
| getDishesByCategory | 分类id、菜品和分类未删，不筛dish.status | `src/main/resources/mapper/FoodMapper.xml:60-68` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:35`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:61` |
| existCategoryName | 未删分类name查重 | `src/main/resources/mapper/FoodMapper.xml:70-75` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:28` |
| getAllDishes | 菜品和分类未删，不筛dish.status | `src/main/resources/mapper/FoodMapper.xml:77-83` | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:55` |

## 6. 外部存储和中间件

| 组件 | 配置 / 使用 |
|---|---|
| MySQL / MyBatis | 默认本机HotelSystem，JDBC serverTimezone Asia/Shanghai；XML扫描与驼峰映射（`src/main/resources/application.yml:5-22`）；FOR UPDATE、INSERT SET、ON DUPLICATE KEY、INTERVAL等MySQL语法（`src/main/resources/mapper/RoomMapper.xml:5-29`、`src/main/resources/mapper/OrderMapper.xml:113-157`） |
| Redis | StringRedisTemplate：token→ROLE_id、session:ROLE_id→token均3小时；login:fail / login:lock按账号/IP（`src/main/java/com/winniethepooh/hotelsystembackend/service/RedisService.java:19-42`、`src/main/java/com/winniethepooh/hotelsystembackend/service/LoginAttemptService.java:31-55`）；原RedisTemplate Bean保留（`src/main/java/com/winniethepooh/hotelsystembackend/service/RedisConfig.java:12-21`） |
| OSS | aliyun绑定、延迟单例、销毁shutdown、上传流关闭（`src/main/resources/application.yml:50-54`、`src/main/java/com/winniethepooh/hotelsystembackend/utils/AliOSSProperties.java:10-15`、`src/main/java/com/winniethepooh/hotelsystembackend/config/AliOSSConfig.java:17-20`、`src/main/java/com/winniethepooh/hotelsystembackend/utils/AliOSSUtil.java:34-39`） |
| JWT | hotel.jwt.secret HS256≥32字节；3小时、UUID jti（`src/main/java/com/winniethepooh/hotelsystembackend/utils/JwtUtils.java:21-43`） |

## 7. 关键配置项

| 键 | 环境变量 / 默认值 | 定义 / 读取方 |
|---|---|---|
| 激活profile | SPRING_PROFILES_ACTIVE；默认不激活 | `src/main/resources/application.yml:1`；dev SQL init `src/main/resources/application-dev.yml:4-9` |
| spring.datasource.url/username/password | DB_URL/本机HotelSystem；DB_USERNAME/root；DB_PASSWORD/空 | `src/main/resources/application.yml:5-9` |
| spring.data.redis.host/port/password | REDIS_HOST/localhost；REDIS_PORT/6379；REDIS_PASSWORD/空 | `src/main/resources/application.yml:10-14` |
| mybatis.mapper-locations / map-underscore-to-camel-case | classpath:mapper/*.xml / true | `src/main/resources/application.yml:19-22` |
| server.port / tomcat.max-swallow-size | SERVER_PORT/8080；10MB | `src/main/resources/application.yml:24-29` |
| spring.servlet.multipart.max-file-size | 5MB | `src/main/resources/application.yml:15-17`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/UploadController.java:28-33` |
| springdoc.swagger-ui.path | 默认/swagger-ui.html；dev改原Springdoc入口为/swagger-ui-entry.html，精确静态/swagger-ui.html由配置单独提供 | `src/main/resources/application.yml:31-33`、`src/main/resources/application-dev.yml:11-14`、`src/main/java/com/winniethepooh/hotelsystembackend/config/SwaggerStaticPageConfig.java:8-15` |
| hotel.scheduler.enabled | HOTEL_SCHEDULER_ENABLED/true | `src/main/resources/application.yml:35-37`、`src/main/java/com/winniethepooh/hotelsystembackend/config/SchedulingConfig.java:13` |
| hotel.jwt.secret | JWT_SECRET/空，实际必填 | `src/main/resources/application.yml:38-40`、`src/main/java/com/winniethepooh/hotelsystembackend/utils/JwtUtils.java:26-31` |
| hotel.cors.allowed-origins | CORS_ALLOWED_ORIGINS/空、逗号分隔 | `src/main/resources/application.yml:41-43`、`src/main/java/com/winniethepooh/hotelsystembackend/config/GlobalCorsConfig.java:18-25` |
| hotel.login.max-failures/window-minutes/lock-minutes | 5/15/15 | `src/main/resources/application.yml:44-48`、`src/main/java/com/winniethepooh/hotelsystembackend/service/LoginAttemptService.java:23-28` |
| aliyun.endpoint/access-key-id/access-key-secret/bucket-name | ALIYUN_OSS_ENDPOINT/成都；ALIYUN_ACCESS_KEY_ID与ALIYUN_SECRET_KEY/空；ALIYUN_BUCKET_NAME/hotelsystem | `src/main/resources/application.yml:50-54`、`src/main/java/com/winniethepooh/hotelsystembackend/utils/AliOSSProperties.java:10-15` |
| dev日志 | Mapper DEBUG，只在dev配置 | `src/main/resources/application-dev.yml:16-19`；默认无SQL日志实现显式覆盖（`src/main/resources/application.yml:19-22`） |
| test profile | cron=false、max-failures1000、测试跨域来源；DB/Redis/JWT动态注入 | `src/test/resources/application-test.yml:1-10`、`src/test/java/com/winniethepooh/hotelsystembackend/support/IntegrationTestBase.java:44-66` |
| e2e profile | cron=true；BrowserE2EIT用独立容器连接、随机JWT密钥启动e2e，TC136另用dev jar | `src/test/resources/application-e2e.yml:1-4`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:50-71`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:187-219` |
| Maven测试开关 | skipApiTests/skipBrowserTests均false；两Failsafe执行分开reportsDirectory/summaryFile | `pom.xml:34-35`、`pom.xml:190-207` |
| Playwright执行 | 单worker、retries0、Chromium headless、locale zh-CN、timezone Asia/Shanghai；E2E_BASE_URL及输出报告变量由JUnit注入 | `playwright.config.js:3-21`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:151-168` |

仍为代码 / SQL常量：JWT/session3小时（`src/main/java/com/winniethepooh/hotelsystembackend/utils/JwtUtils.java:22`、`src/main/java/com/winniethepooh/hotelsystembackend/service/RedisService.java:19`）；住客未付15分钟（`src/main/resources/mapper/OrderMapper.xml:118-136`）；入住最多30晚（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:171-177`）；部分区间上限366天（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:51-58`）；餐厅24小时（`src/main/resources/mapper/OrderMapper.xml:339-353`）；默认价199/299/499在Java与SQL两处（`src/main/java/com/winniethepooh/hotelsystembackend/constant/RoomTypeConstant.java:11-13`、`src/main/resources/mapper/RoomMapper.xml:113-117`）；分页范围见 `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:50-51`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:27-28`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:91-92`。

## 8. 浏览器状态与端到端产物

| 数据 | 结构 / 边界 | 出处 |
|---|---|---|
| sessionStorage.hotel-session | 登录结果token/id/role加name、view；住客另存脱敏profile，刷新恢复视图；api快照请求token，401只清token仍匹配的当前会话，按清除前角色选入口，并发旧401不再改入口或清新会话 | `src/main/resources/static/app.js:25-41`、`src/main/resources/static/app.js:52-70`、`src/main/resources/static/app.js:179-187`、`src/main/resources/static/app.js:210-227`、`src/main/resources/static/app.js:543-546` |
| 预订页面请求 | 日期转checkInTime的14:00与checkOutTime的12:00，房号字符串、入住人name/phone/idCard；前台收款转boolean paid；这些是页面默认时刻，后端仍接收其他合法时刻 | `src/main/resources/static/app.js:265-305`、`src/main/java/com/winniethepooh/hotelsystembackend/dto/InsertRoomOrderDTO.java:10-18`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:171-177` |
| 餐饮页面请求 | 仅选上架菜品、数量>0生成dishId/quantity明细；金额取后端；页面地址必填、备注可选 | `src/main/resources/static/app.js:363-384`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:208-217` |
| 改密成对约束 | UserInfoChangeDTO仍按字段校验，Service额外拒绝原/新密码仅一项；两项均空允许email-only，不更新密码或撤销session | `src/main/java/com/winniethepooh/hotelsystembackend/dto/UserInfoChangeDTO.java:10-15`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:56-72`、`src/test/java/com/winniethepooh/hotelsystembackend/PasswordHashingIT.java:72-107` |
| E2E基础夹具 | seedE2eBase只预置经理/前台/餐厅、房间与菜品，无住客；registrations E/F/H/K、新员工G由页面创建；每例reset并写fixtures.json | `src/test/java/com/winniethepooh/hotelsystembackend/support/Fixtures.java:169-202`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:76-99` |
| 有限夹具桥 | data与state返回夹具/数据库当前状态；checkin/checkout推进指定已有订单时刻，expire用SQL NOW()-16分钟；随机键+回环随机端口；调度仍真实执行 | `src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:110-149`、`src/test/e2e/hotel.spec.js:4-14`、`src/test/e2e/hotel.spec.js:66-76` |
| JUnit与浏览器结果 | target/surefire-reports、failsafe-reports、failsafe-e2e-reports分开；target/e2e/TC-*.log/.xml，artifacts/TC-*截图和trace；Node子进程退出0且一例报告0失败/错误/跳过才通过Java断言 | `pom.xml:198-207`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:151-173`、`playwright.config.js:10-21`、`src/test/e2e/hotel.spec.js:15-16`、`README.md:313` |

测试桥的数据快照和时间修改在独立测试库；浏览器页面执行的预订、支付、取消、收款、评价等业务仍通过生产HTTP入口（`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:65-82`、`src/test/e2e/hotel.spec.js:79-320`）。12张表与89条Mapper SQL未因UI新增数据库模型。
