# 02 入口

## HTTP 接口（46 个）

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
| GET | /rooms/status-wall | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:70-74` getRoomStatusWallController | FRONT | 单次 JOIN 返回今日价、当日入住人、当前有效入住时刻（`src/main/resources/mapper/RoomMapper.xml:152-169`） |

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

合计：user4、staff6、rooms7、order10、restaurant2、food8、business8、upload1，共46，出处见上表。

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
| `src/main/java/com/winniethepooh/hotelsystembackend/HotelSystemBackendApplication.java:9-10` main | SpringApplication.run；配置/profile见 `src/main/resources/application.yml:1-54` |

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
| app.js启动/角色导航 | 11个视图，以session.view保存当前视图；未登录显示注册/登录，越权视图回首页；携token的并发401只清匹配的当前会话并保留原角色登录入口；员工退出POST后清浏览器会话，住客退出只清浏览器状态 | `src/main/resources/static/app.js:14-29`、`src/main/resources/static/app.js:52-70`、`src/main/resources/static/app.js:197-227`、`src/main/resources/static/app.js:543-546` |
| Maven browser-e2e → BrowserE2EIT | 独立Failsafe执行六条JUnit；e2e上下文开启真实cron，Node按TC编号只运行一条Playwright | `pom.xml:198-207`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:65-71`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:151-184` |
| 测试POST /fixture | JDK桥绑定127.0.0.1随机端口，要求X-Fixture-Key随机值；仅data/state/checkin/checkout/expire有限动作，不是生产Controller | `src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:110-149` |

六条端到端入口为TC132住客预订/营收/真实退房、TC133未收款前台单70秒不取消/入住退房清洁、TC134点餐取消、餐厅真实菜名明细与评价销量、TC135员工停用及经理并发401保持员工登录入口、TC136空库dev jar/Swagger、TC137退款重订（`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:180-219`、`src/test/e2e/hotel.spec.js:79-320`）。这里记录测试定义与入口，实际执行结果应查看对应测试报告。
