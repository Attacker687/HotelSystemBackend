# 02 入口

## HTTP 接口（50 个）

权限列指当前角色约束。公共业务接口为`/user/login`、`/user/register`、`/staff/login`，精确匹配；另外四个精确静态路径仅GET/HEAD免登录，dev另放行文档路径（`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:25-26`、`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:78-84`）。其余先校验 token；类 / 方法注解同时生效，方法优先，缺身份 401、角色不符 403（`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:51-70`、`src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:20-44`）。成功为 `Result{code:0,msg:"success",data}`；Controller/MVC 错误为对应 HTTP 状态及 Result，过滤器 401 使用 sendError（`src/main/java/com/winniethepooh/hotelsystembackend/entity/Result.java:16-18`、`src/main/java/com/winniethepooh/hotelsystembackend/exception/GlobalExceptionHandler.java:33-55`、`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:54-69`）。

### 住客账号 `/user`

| 方法 | 路径 | 处理函数 | 权限 | 说明 |
|---|---|---|---|---|
| POST | /user/register | `src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:39-42` registerController | 公共 | @Valid RegisterDTO；写 user 和 individual |
| POST | /user/login | `src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:45-62` loginController | 公共 | UserLoginDTO；账号 / IP 限流，JWT + 双键 session，TTL 3 小时 |
| POST | /user/change | `src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:65-69` changeInfoController | USER | @Valid UserInfoChangeDTO；原/新密码必须成对，缺一400不改哈希/session；成对改密撤销，email-only保留（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:56-72`） |
| GET | /user/{id} | `src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:72-77` queryUserByIdController | USER | 路径 id 为兼容保留，实际查询当前身份；身份证脱敏由 Service 完成（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:76-80`） |

### 员工 `/staff`

| 方法 | 路径 | 处理函数 | 权限 | 说明 |
|---|---|---|---|---|
| POST | /staff/login | `src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:41-56` staffLoginController | 公共 | StaffLoginDTO；JWT + 双键 session，TTL 3 小时 |
| POST | /staff/logout | `src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:59-65` staffLogoutController | MANAGER、FRONT、RESTAURANT | 按当前身份撤销session；原GET对有效会话405且不撤销（`src/test/java/com/winniethepooh/hotelsystembackend/TokenSessionIT.java:111-118`） |
| POST | /staff/register | `src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:68-72` staffRegisterController | MANAGER | StaffRegisterDTO；不在公共路径中 |
| POST | /staff/status | `src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:75-79` modifyStatusController | MANAGER | ModifyStatusDTO；停用撤销 session |
| DELETE | /staff | `src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:82-86` deleteStaffController | MANAGER | query id；软删除 |
| GET | /staff/list | `src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:89-96` getStaffListController | MANAGER | 必填 page≥1、pageSize 1～100，可选 role/account；PageBean |

### 房间 `/rooms`

| 方法 | 路径 | 处理函数 | 权限 | 说明 |
|---|---|---|---|---|
| GET | /rooms | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:26-33` queryRoomsController | 登录 | page 默认1、pageSize默认100，范围1～100；String roomNumber，roomType/status/date；date缺省今天，带当日价 |
| GET | /rooms/{id} | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:36-39` queryRoomByIdController | 登录 | QueryRoomsVO；无价格日历查询 |
| PUT | /rooms | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:42-46` modifyRoomStatusController | MANAGER、FRONT | ModifyStatusDTO；校验状态并按房间id锁行；占用→空闲结束当前订单 |
| POST | /rooms | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:49-53` insertRoomController | MANAGER | InsertRoomDTO；新增 |
| PUT | /rooms/{id} | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:56-60` modifyRoomInfoController | MANAGER | InsertRoomDTO；非空字段更新，roomType仍需合法 |
| DELETE | /rooms/{id} | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:63-67` deleteRoomController | MANAGER | 软删除 |
| GET | /rooms/status-wall | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:70-74` getRoomStatusWallController | FRONT | 单次 JOIN 返回今日价、当日入住人、当前有效入住时刻（`src/main/resources/mapper/RoomMapper.xml:164-181`） |

### 订单 `/order`

| 方法 | 路径 | 处理函数 | 权限 | 说明 |
|---|---|---|---|---|
| GET | /order/user/query | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:33-38` queryOrderController | USER | startDate/endDate yyyy-MM-dd；本人下单日期区间订单 |
| POST | /order/user/comment | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:41-45` commentOrderController | USER | @Valid CommentOrderDTO；本人已完成订单，星级1～5、长度≤500 |
| GET | /order/query | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:48-53` getAllRoomOrderController | MANAGER、FRONT | page默认1、limit默认10，page≥1、limit1～100；JOIN列表+count |
| POST | /order | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:56-64` insertRoomOrderController | FRONT、USER | @Valid InsertRoomOrderDTO；USER返回id，FRONT返回空；两者计价和逐晚落库 |
| PUT | /order/{id} | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:67-71` modifyRoomOrderController | FRONT | ModifyRoomOrderDTO；改期 / 换房，重算全部夜价 |
| DELETE | /order/{id} | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:74-78` deleteRoomOrderController | MANAGER | 软删除 |
| POST | /order/pay | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:81-85` payRoomOrderController | USER | query id；条件UPDATE，仅本人未支付进行中且创建未超15分钟 |
| POST | /order/cancel | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:88-92` cancelRoomOrderController | USER | query id；仅本人尚未入住的进行中订单；已支付取消置退款状态2 |
| POST | /order/meal-order | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:95-99` insertMealOrderController | USER | @Valid InsertMealOrderDTO；菜价取库，忽略客户端金额 |
| PUT | /order/meal-order/{id}/cancel | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:102-106` cancelMealOrderController | USER | 仅本人新单0→取消3 |

### 餐厅 `/restaurant`

| 方法 | 路径 | 处理函数 | 权限 | 说明 |
|---|---|---|---|---|
| GET | /restaurant/live-order | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RestaurantController.java:11-21` getLiveMealOrderController | RESTAURANT | 近24小时未删除订单计数 / 列表和逐单明细 |
| PUT | /restaurant/status | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RestaurantController.java:24-27` modifyMealOrderStatusController | RESTAURANT | query id/status；0→1→2 或0→3；基于原状态条件UPDATE |

### 菜品 `/food`

| 方法 | 路径 | 处理函数 | 权限 | 说明 |
|---|---|---|---|---|
| GET | /food/category | `src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:23-27` | 登录 | 分类列表 `List<FoodCategoryVO>` |
| GET | /food/category/{id} | `src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:30-34` | 登录 | 某分类下的菜品；id 声明为 String，`Integer.valueOf` 转换 |
| POST | /food/category | `src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:36-41` | MANAGER | body `CreateFoodCategoryDTO{name}` |
| DELETE | /food/category/{id} | `src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:43-48` | MANAGER | 删分类并连带删其下菜品 |
| POST | /food/dish | `src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:50-55` | MANAGER | body `CreateDishDTO` |
| PUT | /food/dish | `src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:57-62` | MANAGER | body 直接是实体 `Dish`（按 id 改非空字段） |
| DELETE | /food/dish | `src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:64-69` | MANAGER | query `id`；软删除 |
| GET | /food/dish/list | `src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:71-75` | 登录 | 全部菜品 `List<DishVO>` |


### 经营统计与价格日历 `/business`

| 方法 | 路径 | 处理函数 | 权限 | 说明 |
|---|---|---|---|---|
| GET | /business/revenue/stats | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:20-29` getRevenueStatsController | MANAGER | date缺省今天；RevenueStatsVO |
| GET | /business/revenue/trend | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:32-36` getRevenueTrendController | MANAGER | startDate/endDate yyyy-MM-dd，RevenueTrendVO |
| GET | /business/revenue/room-type | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:39-43` getEachRoomTypeRevenueController | MANAGER | 三种房型逐晚营收 |
| GET | /business/occupancy/heatmap | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:46-51` getEachFloorOccupancyController | MANAGER | 日期区间、可选floor，OccupancyHeatmapVO |
| GET | /business/dish/top10 | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:54-58` getTop10DishesController | MANAGER | 日期区间含结束日，排除取消餐饮单 |
| GET | /business/detail | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:61-65` getStatsDetailController | MANAGER | 日期区间；PageBean包装全部每日明细，不分页 |
| POST | /business/calendar | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:68-71` updatePriceCalendarController | MANAGER | @Valid DynamicUpdatePriceDTO；房型0～2、正价；一条批量UPSERT |
| GET | /business/calendar | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:74-78` getPriceCalendarController | MANAGER | startDate/endDate/roomType；每日期一项，未设价为null |

趋势、热力图、明细与价格日历区间最多366天，逆序400；房型营收与Top10不走该限制（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:55-58`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:151-273`）。

### 上传 `/upload`

| 方法 | 路径 | 处理函数 | 权限 | 说明 |
|---|---|---|---|---|
| POST | /upload/image | `src/main/java/com/winniethepooh/hotelsystembackend/controller/UploadController.java:28-53` uploadImage | MANAGER | multipart file；5MB上限，图片扩展名+文件头校验；data为URL（`src/main/resources/application.yml:15-17`） |

### 住客预订助手 `/agent`

类级 `@RoleRequired(USER)`，四个方法都只对住客开放；用户 id 一律取 BaseContext，不从请求体读（`src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:19-22`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:36-45`）。

| 方法 | 路径 | 处理函数 | 权限 | 说明 |
|---|---|---|---|---|
| POST | /agent/sessions | `src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:33-34` sessions | USER | 返回 `{sessionId: 随机UUID}`，不写 Redis；会话在第一轮对话结束时才落 Redis（`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:116`） |
| POST | /agent/chat | `src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:36-39` chat → `src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:61-129` | USER | @Valid ChatRequest：sessionId 必须是 UUID、message 非空且≤500（`src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:28-31`）；key 未配 503、每分钟超限 429，这两种在写 SSE 头之前抛出，走统一 JSON Result；之后响应 `text/event-stream`，事件 status / delta / card / error / done |
| POST | /agent/actions/{id}/confirm | `src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:41-42` confirm → `src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:66-96` | USER | 执行卡片动作至多一次，返回 actionId、type、status=CONFIRMED、orderId、message；重复确认返回同一结果 |
| POST | /agent/actions/{id}/cancel | `src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:44-45` cancel → `src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:98-116` | USER | 写 CANCELLED 记录后返回 actionId、status=CANCELLED；已确认的卡片取消 409 |

### 助手工具（模型可调用，不是 HTTP 入口）

工具在 /agent/chat 的请求线程里执行；先校验当前角色是 USER 且 BaseContext 用户等于会话用户，再解析参数，最后在带截止时间的事务中分派（`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:128-154`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:177-189`）。

| 工具 | 处理 | 读写 | 底层调用 |
|---|---|---|---|
| search_available_rooms | `src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:220-226` | 只读 | 日期固定 14:00 入住、12:00 离店，最多 10 间；`OrderService.searchAvailableRoomsService` → `src/main/resources/mapper/RoomMapper.xml:5-16` + 价格日历 |
| get_price_quote | `src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:228-232` | 只读（重叠查询带 for update，见 README Q9） | `OrderService.quoteRoomService`（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:51-57`） |
| list_my_orders | `src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:239-257` | 只读 | 本人近 90 天两类订单，各按创建时间倒序取 20；房号按 room_id 查（含已删房间） |
| list_menu | `src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:259-262` | 只读 | `FoodService.getAllDishesService` 后内存只留 status=1 |
| propose_booking | `src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:264-276` | 只写 Redis 卡片 | 先 quoteRoomService；入住人固定为当前账号的姓名/手机/身份证 |
| propose_payment / propose_cancel | `src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:278-294` | 只写 Redis 卡片 | getRoomOrderById 预检归属与状态，卡片金额取订单 total_amount |
| propose_meal_order | `src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:296-315` | 只写 Redis 卡片 | 1–20 行、数量 1–20、地址≤255、备注≤500；逐行取库内菜价 |

合计：user4、staff6、rooms7、order10、restaurant2、food8、business8、upload1、agent4，共50，出处见上表。

## 定时任务

`src/main/java/com/winniethepooh/hotelsystembackend/config/SchedulingConfig.java:11-14` 按 hotel.scheduler.enabled（默认true）启用；三个方法均有事务，退房批次另用数据库任务锁（`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:29-57`）。

| 触发方式 | 处理函数 | 说明 |
|---|---|---|
| 每分钟第0秒 `0 * * * * ?` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:29-41` releaseExpiredRooms | 抢占本分钟任务；到离店时刻的已支付 / 前台进行中订单完成，占用房间→清洁中 |
| 每分钟第1秒 `1 * * * * *` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:44-51` flushRoomStatus | 在[入住时刻,离店时刻)的已支付 / 前台订单，仅空闲房间→占用 |
| 每分钟第2秒 `2 * * * * *` | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:54-57` flushExpiredRoomOrders | 创建超15分钟未支付住客单→取消，前台单排除 |

## 命令行 / main

| 入口 | 参数要点 |
|---|---|
| `src/main/java/com/winniethepooh/hotelsystembackend/HotelSystemBackendApplication.java:9-10` main | SpringApplication.run；配置/profile见 `src/main/resources/application.yml:1-59` |

## 请求链路上的其他组件

| 类型 | 处理函数 | 说明 |
|---|---|---|
| Servlet Filter（@Component） | `src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:20-84` | 精确公共路径，其余token校验；finally清理上下文 |
| CorsFilter | `src/main/java/com/winniethepooh/hotelsystembackend/config/GlobalCorsConfig.java:18-30` | HIGHEST_PRECEDENCE，先于登录校验 |
| AOP | `src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:20-44` | 类 / 方法角色约束，方法优先 |
| RestControllerAdvice | `src/main/java/com/winniethepooh/hotelsystembackend/exception/GlobalExceptionHandler.java:33-55` | 业务 / MVC状态及Result，意外异常500 |

## 静态页面与浏览器测试入口

| 入口 | 权限 / 行为 | 出处 |
|---|---|---|
| GET/HEAD /、/index.html、/app.js、/style.css | 四个精确匿名路径，其他method和相邻路径不属于静态放行；生产业务API继续token校验 | `src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:26-27`、`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:78-84`、`src/test/java/com/winniethepooh/hotelsystembackend/filter/LoginFilterTest.java:74-91` |
| dev GET /swagger-ui.html | 精确静态资源直接200，引用/swagger-ui/资源和/v3/api-docs；其他profile匿名401 | `src/main/java/com/winniethepooh/hotelsystembackend/config/SwaggerStaticPageConfig.java:8-15`、`src/main/resources/application-dev.yml:11-14`、`src/main/resources/static/swagger-ui.html:8-16`、`src/test/e2e/hotel.spec.js:259-281` |
| app.js启动/角色导航 | 11个视图，以session.view保存当前视图；住客登录后另挂载助手面板（员工不挂载）；未登录显示注册/登录，越权视图回首页；携token的并发401只清匹配的当前会话并保留原角色登录入口；员工退出POST后清浏览器会话，住客退出只清浏览器状态 | `src/main/resources/static/app.js:14-29`、`src/main/resources/static/app.js:52-79`、`src/main/resources/static/app.js:206-237`、`src/main/resources/static/app.js:759-760` |
| Maven browser-e2e → BrowserE2EIT | 独立Failsafe执行十条JUnit（e2e profile 用 fake 模型）；e2e上下文开启真实cron，Node按TC编号只运行一条Playwright | `pom.xml:202-211`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:68-74`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:170-208` |
| 测试POST /fixture | JDK桥绑定127.0.0.1随机端口，要求X-Fixture-Key随机值；仅data/state/checkin/checkout/expire及agent-inputs/agent-delayed-booking/agent-expire（读 Fake 输入、排队延迟回复、删除 Redis 卡片）有限动作，不是生产Controller | `src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:114-168`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:132-146` |
| 助手面板调用：POST /agent/sessions | 打开面板或新对话时建会话，结果存 sessionStorage.hotel-agent-session | `src/main/resources/static/app.js:575-599`、`src/main/resources/static/app.js:584-587` |
| 助手面板调用：POST /agent/chat | 直接 fetch 并逐块解析 SSE；先复用 checkAuth 处理 401，非 2xx 走 apiResult 显示 JSON 错误 | `src/main/resources/static/app.js:648-704`、`src/main/resources/static/app.js:661-664` |
| 助手面板调用：POST /agent/actions/{id}/confirm、cancel | 卡片按钮；done 事件后才可点，404 或确认时 400/409 把卡片置为已失效，倒计时到 0 也失效 | `src/main/resources/static/app.js:705-758`、`src/main/resources/static/app.js:725`、`src/main/resources/static/app.js:738`、`src/main/resources/static/app.js:751` |

十条端到端入口：TC051助手报价/会话隔离/手机布局、TC052助手重复确认同号一单与卡片边界、TC053助手支付取消退款与点餐卡片、TC054助手拒绝他人信息与流式碎片（`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:199-202`、`src/test/e2e/hotel.spec.js:421-759`）；原有TC132住客预订/营收/真实退房、TC133未收款前台单70秒不取消/入住退房清洁、TC134点餐取消、餐厅真实菜名明细与评价销量、TC135员工停用及经理并发401保持员工登录入口、TC136空库dev jar/Swagger、TC137退款重订（`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:204-243`、`src/test/e2e/hotel.spec.js:79-320`）。这里记录测试定义与入口，实际执行结果应查看对应测试报告。

## 客房订单写入口与冲突检查一览

「占用区间」在两处判定，口径一致：`findOverlappingOrder` 与 `findAvailableRooms` 都只把 status=0、未删除的订单算作占用（不看 pay_status），区间按时刻左闭右开，相接不冲突（`src/main/resources/mapper/OrderMapper.xml:99-105`、`src/main/resources/mapper/RoomMapper.xml:9-13`）。因此取消（status 2）、完成（status 1）、软删除一提交就释放区间，不需要额外写库。目前没有每日库存表，也没有请求级幂等键：普通 POST /order 的 DTO 不含 requestId（`src/main/java/com/winniethepooh/hotelsystembackend/dto/InsertRoomOrderDTO.java:10-18`），只有助手确认走 booking_request。

| 入口 | 处理链 | 事务 | 锁房间行 | 区间重叠查询 | 其他并发 / 幂等控制 | room_order_night |
|---|---|---|---|---|---|---|
| POST /order（USER 下单） | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:58-61` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:100-126` | `@Transactional`（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:101`） | 按房号 `lockRoomByNumber … for update`（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:108`、`src/main/resources/mapper/RoomMapper.xml:17-19`） | `checkOverlap` → `findOverlappingOrder … limit 1 for update`，命中 409（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:109`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:206-209`） | 无请求幂等；同一住客重复提交会生成多张单，只受区间冲突约束 | 批量插入（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:122`） |
| POST /order（FRONT 开单） | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:63` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:94-98` → 同上私有方法 | `@Transactional`（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:95`） | 同上 | 同上 | 无；入住时刻已到时房间 0→1（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:123-124`） | 批量插入 |
| 助手确认 BOOKING | `src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:41-42` → `src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:66-96` → `src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:121-127` → insertRoomOrderByUserService | 外层 TransactionTemplate，内层 `@Transactional` 加入同一事务（`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:78-84`） | 同 USER 下单 | 同 USER 下单 | booking_request.request_id 唯一键（PROCESSING→SUCCESS）；建单后比对卡片报价，不同 409 整体回滚（`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:126`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:149-153`） | 批量插入 |
| PUT /order/{id}（FRONT 改期 / 换房） | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:67-71` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:128-159` | `@Transactional`（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:129`） | 按房间 id 升序锁来源与目标两间（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:136-139`） | 先 `getRoomOrderByIdForUpdate` 锁订单，再 `checkOverlap` 排除自身（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:140-146`） | 订单必须仍进行中且房间未被并发改动，否则 409；`modifyRoomOrder` 带 status=0 条件（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:141-142`、`src/main/resources/mapper/OrderMapper.xml:81-86`） | 物理删除后重插（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:153-154`） |
| POST /order/cancel（USER 取消） | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:88-92` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:172-178` | 无注解，单条 UPDATE 自动提交 | 无 | 无 | 条件 UPDATE：本人、status=0、pay 0/1、入住时刻未到；已付改 pay=2（`src/main/resources/mapper/OrderMapper.xml:134-139`） | 不动（统计按 status≠2 排除） |
| 助手确认 CANCEL | `src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:137-139` → cancelRoomOrderService | TransactionTemplate | 无 | 无 | booking_request 唯一键 + 同上条件 UPDATE | 不动 |
| 超时取消（每分钟第 2 秒） | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:54-57` | `@Transactional`，单条 UPDATE | 无 | 无 | 条件：user_id 非空、status=0、pay=0、created_at 早于 NOW()-15 分钟（`src/main/resources/mapper/OrderMapper.xml:141-150`） | 不动 |
| DELETE /order/{id}（MANAGER 删除） | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:74-78` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:161-162` | 无 | 无 | 无 | 无状态条件，按 id 软删，不改房态（`src/main/resources/mapper/OrderMapper.xml:111-116`） | 不动（统计按 is_deleted=0 排除） |
| POST /order/pay（USER 支付，不改区间） | `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:81-85` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:164-170` | 无 | 无 | 无 | 条件 UPDATE：本人、status=0、pay=0、创建 15 分钟内（`src/main/resources/mapper/OrderMapper.xml:126-132`） | 不动 |
| 助手确认 PAYMENT | `src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:128-136` → payRoomOrderService | TransactionTemplate | 无 | 无；先 `getRoomOrderByIdForUpdate` 锁订单行防改期改价（`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:130-132`） | booking_request + 金额比对 + 同上条件 UPDATE | 不动 |
| 退房任务完成订单（每分钟第 0 秒） | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:29-41` | `@Transactional` + scheduler_task_lock 分钟抢占 | 无（房态只做 1→2 条件更新） | 无 | 本分钟只一个实例执行 | 不动 |
| PUT /rooms 占用→空闲时完成当前订单 | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:42-46` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:63-74` | `@Transactional` | `lockRoomById`（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:67`） | 无 | 只把当前有效订单置 DONE | 不动 |

## 房间、价格日历读写入口一览

目前没有任何房间、价格或订单数据进 Redis；以下读入口每次都直接查 MySQL（Redis 全部用途见 [03 §6](03-data.md#6-外部存储和中间件)）。

| 入口 | 处理 | 读 / 写 | SQL |
|---|---|---|---|
| GET /rooms（房间列表） | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:26-34` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:113-126` | 读 room，按 date（缺省今天）LEFT JOIN 当日 price_calendar，缺价用房型默认价；列表+计数 2 条 SQL | `src/main/resources/mapper/RoomMapper.xml:122-147`、`src/main/resources/mapper/RoomMapper.xml:183-197` |
| GET /rooms/{id}（房间详情） | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:36-39` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:76-80` | 读 room（排除软删），不带价格 | `src/main/resources/mapper/RoomMapper.xml:84-93` |
| GET /rooms/status-wall | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:70-74` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:106-111` | 读 room + 当日价 + room_order + individual，一条 SQL | `src/main/resources/mapper/RoomMapper.xml:164-182` |
| 助手 search_available_rooms | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:59-66` | 读 room + room_order（NOT EXISTS，无锁）+ price_calendar（同房型只读一次） | `src/main/resources/mapper/RoomMapper.xml:5-16`、`src/main/resources/mapper/RoomMapper.xml:108-113` |
| 助手 get_price_quote / propose_booking | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:51-57` | 读 room（按房号、不锁）+ room_order（for update）+ price_calendar | `src/main/resources/mapper/RoomMapper.xml:198-203`、`src/main/resources/mapper/OrderMapper.xml:99-105`、`src/main/resources/mapper/RoomMapper.xml:108-113` |
| 下单 / 改期 / 报价计价 | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:211-219` | 读 price_calendar（[入住日,离店日) 一次批量），缺日用默认价 | `src/main/resources/mapper/RoomMapper.xml:108-113` |
| POST /rooms | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:49-53` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:82-88` | 写 room | `src/main/resources/mapper/RoomMapper.xml:29-33` |
| PUT /rooms/{id} | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:56-60` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:90-99` | 写 room 非空字段（含 room_type、status），不加行锁 | `src/main/resources/mapper/RoomMapper.xml:51-65` |
| DELETE /rooms/{id} | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:63-67` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:101-104` | 软删 room，不检查订单 | `src/main/resources/mapper/RoomMapper.xml:67-72` |
| PUT /rooms（改房态） | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:42-46` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:63-74` | 锁行后写 room.status | `src/main/resources/mapper/RoomMapper.xml:20-22`、`src/main/resources/mapper/RoomMapper.xml:44-49` |
| 下单 / 改期 / 任务推进房态 | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:123-124`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:155-158`、`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:39`、`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:50` | 写 room.status，只做 0→1 或 1→2 条件更新 | `src/main/resources/mapper/RoomMapper.xml:23-28` |
| POST /business/calendar | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:68-71` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:262-265` | 写 price_calendar，一条多 VALUES UPSERT | `src/main/resources/mapper/RoomMapper.xml:35-42` |
| GET /business/calendar | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:74-78` → `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:268-274` | 读 price_calendar | `src/main/resources/mapper/RoomMapper.xml:108-113` |
| 经营统计取房间 | `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:113` | 读全部未删 room（不分页、不 JOIN 价格） | `src/main/resources/mapper/RoomMapper.xml:122-147` |
