# 01 模块

全仓为一个 Maven 模块（`pom.xml:11-15`），仍按包分层。HTTP 调用主干由 Filter → 角色 AOP → Controller → Service → Mapper/XML → MySQL 组成；例如 `src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:51-74`、`src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:23-36`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:58-64`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:80-96`。同源原生页面从app.js调用HTTP（`src/main/resources/static/index.html:9-10`、`src/main/resources/static/app.js:52-70`）；定时任务直接调 Mapper（`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:33-57`）。

## M1 启动与全局配置

- **职责 / 关键文件**：`src/main/java/com/winniethepooh/hotelsystembackend/HotelSystemBackendApplication.java:6-10` 启动；`src/main/java/com/winniethepooh/hotelsystembackend/config/SchedulingConfig.java:11-14` 按配置启用调度；`src/main/java/com/winniethepooh/hotelsystembackend/config/GlobalCorsConfig.java:18-30` 配置跨域并设置最先执行；`src/main/java/com/winniethepooh/hotelsystembackend/config/AliOSSConfig.java:17-20` 提供延迟初始化的 OSS 单例；`src/main/java/com/winniethepooh/hotelsystembackend/service/RedisConfig.java:12-21` 保留字符串序列化的 RedisTemplate Bean；`src/main/resources/application.yml:1-54` 和 `src/main/resources/application-dev.yml:4-19` 区分默认 / dev 行为。
- **依赖 / 被依赖**：MySQL、Redis、OSS；全部组件读取配置，例如 `src/main/java/com/winniethepooh/hotelsystembackend/utils/JwtUtils.java:26`、`src/main/java/com/winniethepooh/hotelsystembackend/service/LoginAttemptService.java:23-28`。

- **dev静态Swagger**：精确ResourceHandler给/swagger-ui.html，避免Springdoc重定向；仅dev配置（`src/main/java/com/winniethepooh/hotelsystembackend/config/SwaggerStaticPageConfig.java:8-15`、`src/main/resources/application-dev.yml:11-14`）。

## M2 鉴权与权限

- **职责**：非白名单请求先确认 Redis token，再验 JWT 并写 ThreadLocal，finally 清理（`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:51-75`）；方法注解优先于类注解，缺身份 401、无权限 403（`src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:20-44`）。
- **关键文件**：`src/main/java/com/winniethepooh/hotelsystembackend/annotation/RoleRequired.java:5-10`；`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:25-84` 精确公共接口、四个GET/HEAD静态路径与dev文档路径；`src/main/java/com/winniethepooh/hotelsystembackend/context/BaseContext.java:9-28` 线程上下文；`src/main/java/com/winniethepooh/hotelsystembackend/utils/JwtUtils.java:21-55` 外部密钥、3 小时 JWT 和随机 jti；`src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:20-44` 角色拦截。
- **依赖 / 被依赖**：Redis、RoleConstant、BusinessException；Controller 与 Service 从 BaseContext 取用户 id，例如 `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:37`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:77`。

## M3 接口层 controller

- **职责**：8 个 REST Controller 共 46 入口，接收参数并调用 Service；入口出处逐项见 [02](02-entrypoints.md)。
- **关键文件**：`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:33-106` 客房 / 餐饮 / 评价；`src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:39-77` 住客登录与资料；`src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:41-96` 员工；`src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:26-74` 房间；`src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:18-78` MANAGER 统计与价格；`src/main/java/com/winniethepooh/hotelsystembackend/controller/RestaurantController.java:11-27` RESTAURANT 看板；`src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:23-74` 菜品分类；`src/main/java/com/winniethepooh/hotelsystembackend/controller/UploadController.java:28-53` MANAGER 图片上传。
- **依赖 / 被依赖**：业务接口、DTO / VO / Result、BaseContext；登录用 LoginAttemptService、JwtUtils、RedisService（`src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:48-56`）；上传用 AliOSSUtil（`src/main/java/com/winniethepooh/hotelsystembackend/controller/UploadController.java:24-33`）；被 HTTP 客户端调用。

## M4 业务层 service

- **职责**：仍有 7 个业务接口及实现；业务层处理锁、状态、归属、计价和统计，例如 `src/main/java/com/winniethepooh/hotelsystembackend/service/OrderService.java:15-36`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:68-228`。
- **关键文件**：`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:68-228` 事务下单、房间锁、逐晚金额、条件支付取消、后端菜价；`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:37-126` 房型 / 房态校验和 JOIN 查询；`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:51-273` 批量读区间、内存汇总与价格 UPSERT；`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:28-81` 注册、哈希迁移、改密成对guard与脱敏；`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:30-86` 员工哈希与撤销 session；`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:23-59` 餐饮条件推进 / 取消；`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/FoodServiceImpl.java:21-63` 分类查重与事务软删除。
- **依赖 / 被依赖**：Mapper、BaseContext、RedisService、常量、异常、PasswordUtils / LocalDateUtil；由 Controller 调用（如 `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:36-39`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:26-38`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:58-64`）。

## M5 定时任务与 Redis 工具

- **职责 / 关键文件**：`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:29-57` 三个事务任务：退房、入住、超时；只有退房批次通过scheduler_task_lock抢占数据库分钟锁，另外两项依赖条件UPDATE；未实现失败重试逻辑。`src/main/java/com/winniethepooh/hotelsystembackend/service/RedisService.java:19-42` token / session 双键与 3 小时 TTL，按反向索引撤销。`src/main/java/com/winniethepooh/hotelsystembackend/service/LoginAttemptService.java:31-55` 账号 / IP 失败限制。
- **依赖 / 被依赖**：OrderMapper、RoomMapper、Redis；调度由 SchedulingConfig 开启（`src/main/java/com/winniethepooh/hotelsystembackend/config/SchedulingConfig.java:11-14`）；登录、改密、员工停用 / 删除调用 session 工具（`src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:48-56`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:43-64`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:72`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:55-65`）。

## M6 持久层 mapper

- **职责**：5 个 `@Mapper` 接口，同名 XML namespace 绑定，SQL 仍集中在 XML；多参数使用形参名，如 `src/main/java/com/winniethepooh/hotelsystembackend/mapper/OrderMapper.java:41-43` 对应 `src/main/resources/mapper/OrderMapper.xml:86-91`。
- **关键文件**：`src/main/java/com/winniethepooh/hotelsystembackend/mapper/OrderMapper.java:22-103` + `src/main/resources/mapper/OrderMapper.xml:5-375`；`src/main/java/com/winniethepooh/hotelsystembackend/mapper/RoomMapper.java:16-56` + `src/main/resources/mapper/RoomMapper.xml:5-190`；`src/main/java/com/winniethepooh/hotelsystembackend/mapper/UserMapper.java:14-41` + `src/main/resources/mapper/UserMapper.xml:6-88`；`src/main/java/com/winniethepooh/hotelsystembackend/mapper/StaffMapper.java:11-30` + `src/main/resources/mapper/StaffMapper.xml:6-69`；`src/main/java/com/winniethepooh/hotelsystembackend/mapper/FoodMapper.java:11-32` + `src/main/resources/mapper/FoodMapper.xml:5-82`。
- **依赖 / 被依赖**：MySQL / MyBatis 配置（`src/main/resources/application.yml:5-22`）；Service 与任务使用，SQL 清单见 [03](03-data.md#5-sql-清单)。

## M7 数据模型 entity / dto / vo

- **职责**：实体、请求与结果载体仍为 Lombok；部分请求有 Bean Validation，新增按日统计 DTO，逐晚明细与任务锁以 Mapper 参数直接写表（`src/main/java/com/winniethepooh/hotelsystembackend/dto/RegisterDTO.java:12-27`、`src/main/java/com/winniethepooh/hotelsystembackend/dto/DailyRevenueDTO.java:8-13`、`src/main/resources/mapper/OrderMapper.xml:75-83`、`src/main/resources/mapper/OrderMapper.xml:150-157`）。
- **关键文件**：`src/main/java/com/winniethepooh/hotelsystembackend/entity/Result.java:6-18`；`src/main/java/com/winniethepooh/hotelsystembackend/entity/RoomOrder.java:8-24`；`src/main/java/com/winniethepooh/hotelsystembackend/entity/MealOrder.java:10-23` / `src/main/java/com/winniethepooh/hotelsystembackend/entity/MealOrderItem.java:10-19`；`src/main/java/com/winniethepooh/hotelsystembackend/dto/InsertRoomOrderDTO.java:9-18`（房间号与 paid）；`src/main/java/com/winniethepooh/hotelsystembackend/dto/InsertMealOrderDTO.java:11-18`（客户端金额仍接收，后端覆盖）；`src/main/java/com/winniethepooh/hotelsystembackend/vo/PageBean.java:8-11`；`src/main/resources/db/schema.sql:8-197` 定义 12 张表。字段见 [03](03-data.md)。
- **依赖 / 被依赖**：Result 使用返回码（`src/main/java/com/winniethepooh/hotelsystembackend/entity/Result.java:16-18`）；Controller、Service、Mapper 使用这些载体（`src/main/java/com/winniethepooh/hotelsystembackend/mapper/OrderMapper.java:25-35`）。

## M8 常量 constant

- **职责 / 关键文件**：角色、状态、房型默认价和返回码未改，SQL 中仍写数值：`src/main/java/com/winniethepooh/hotelsystembackend/constant/RoleConstant.java:3-17`、`src/main/java/com/winniethepooh/hotelsystembackend/constant/RoomTypeConstant.java:5-17`、`src/main/java/com/winniethepooh/hotelsystembackend/constant/RoomStatusConstant.java:3-8`、`src/main/java/com/winniethepooh/hotelsystembackend/constant/RoomOrderStatusConstant.java:4-8`、`src/main/java/com/winniethepooh/hotelsystembackend/constant/RoomOrderPayStatusConstant.java:3-7`、`src/main/java/com/winniethepooh/hotelsystembackend/constant/MealOrderStatusConstant.java:3-8`、`src/main/java/com/winniethepooh/hotelsystembackend/constant/StaffStatusConstant.java:3-6`。`StatisticsUnit` 仍只有定义（`src/main/java/com/winniethepooh/hotelsystembackend/constant/StatisticsUnit.java:3-8`）。
- **依赖 / 被依赖**：无外部系统；角色切面、业务与 Result 引用，例如 `src/main/java/com/winniethepooh/hotelsystembackend/entity/Result.java:16-18`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:93`。

## M9 异常 exception

- **职责 / 关键文件**：业务异常统一继承带 HttpStatus 的 `BusinessException`（`src/main/java/com/winniethepooh/hotelsystembackend/exception/BusinessException.java:10-19`）；原注册格式异常移至 DTO 校验。`src/main/java/com/winniethepooh/hotelsystembackend/exception/GlobalExceptionHandler.java:33-55`：业务保留状态和消息，MVC 错误保留 Spring 状态，意外异常为 500 + 通用消息。
- **依赖 / 被依赖**：Result / Spring HTTP；切面与业务层抛出，例如 `src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:26-36`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:171-182`。

## M10 工具 utils

- **职责 / 关键文件**：`src/main/java/com/winniethepooh/hotelsystembackend/utils/LocalDateUtil.java:12-21` 闭区间日期与逆序 400；`src/main/java/com/winniethepooh/hotelsystembackend/utils/PasswordUtils.java:17-30` BCrypt / MD5 兼容；`src/main/java/com/winniethepooh/hotelsystembackend/utils/AliOSSUtil.java:18-41` 复用 OSS 并关闭流；`src/main/java/com/winniethepooh/hotelsystembackend/utils/AliOSSProperties.java:8-16` 配置绑定。JWT 工具归 M2。
- **依赖 / 被依赖**：OSS、hutool / crypto / codec（`pom.xml:97-108`、`pom.xml:128-132`）；用户、员工、订单、统计、上传分别调用（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:33-50`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:34-46`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:190-191`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:58`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/UploadController.java:33`）。

## M11 测试

- **职责 / 关键文件**：`src/test/java/com/winniethepooh/hotelsystembackend/support/IntegrationTestBase.java:39-114` RANDOM_PORT HTTP + 静态 MySQL / Redis 容器，重置数据；`src/test/java/com/winniethepooh/hotelsystembackend/support/Fixtures.java:29-43` 别名与标准请求；`src/test/java/com/winniethepooh/hotelsystembackend/support/SqlCounter.java:13-29` 捕获 Tomcat 线程实际 SQL；`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:118-508` 订单边界 / 并发 / 回滚；`src/test/java/com/winniethepooh/hotelsystembackend/BusinessStatsIT.java:83-330` 统计契约；`src/test/java/com/winniethepooh/hotelsystembackend/TokenSessionIT.java:35-150` session；`src/test/java/com/winniethepooh/hotelsystembackend/RequestValidationIT.java:33-144` 请求校验；其余测试按领域命名，例如 `src/test/java/com/winniethepooh/hotelsystembackend/UploadImageIT.java:35-109`。
- **依赖 / 被依赖**：JUnit、Spring Test、MyBatis Test、Testcontainers（`pom.xml:63-88`）；Surefire / Failsafe 分阶段使用（`pom.xml:185-216`）。测试方法的期望不是运行结果记录。

- **浏览器链路**：`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:50-83`独立容器、e2e profile和每例seedE2eBase；`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:110-173`JDK HttpServer有限夹具桥+Node子进程；`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:180-219`六条JUnit入口，其中TC136另启动dev jar；`src/test/e2e/hotel.spec.js:79-320`页面按钮与库状态断言，TC133完整70秒观察不取消。`pom.xml:190-207` API/browser-e2e分执行和报告，`playwright.config.js:3-21` Chromium单worker、上海时区、无重试、截图/trace。

## M12 浏览器界面

- **职责 / 文件**：Spring同源静态页面，无前端构建服务；`src/main/resources/static/index.html:9-26`挂载导航、内容、提示与错误；`src/main/resources/static/app.js:14-29`四角色视图集合与会话状态；`src/main/resources/static/style.css:7-59`网格、表单、移动布局；`src/main/resources/static/swagger-ui.html:8-16`dev API文档资源。
- **交互**：注册/登录（`src/main/resources/static/app.js:154-196`）；房间/预订/前台开单（`src/main/resources/static/app.js:239-305`）；我的订单、支付/退款/评价/点餐（`src/main/resources/static/app.js:307-386`）；订单总览、房态与清洁、餐厅推进（`src/main/resources/static/app.js:388-441`）；价格、营收/趋势/Top10、员工创建/启停（`src/main/resources/static/app.js:442-541`）。共享mealCard读取明细name、quantity与totalPrice，名称缺失/为空时显示「菜品」（`src/main/resources/static/app.js:356-361`、`src/main/java/com/winniethepooh/hotelsystembackend/entity/MealOrderItem.java:18-19`）。这些页面覆盖现有入口中的常用流程，不表示每个管理API均有表单。
- **依赖 / 被依赖**：sessionStorage保存hotel-session，api()快照原请求token并同源fetch；携token的401仅在当前session仍匹配时清会话并选择原角色入口，后续同令牌401不把员工入口改成住客入口；roleViews只控制导航，后端仍独立鉴权（`src/main/resources/static/app.js:25-70`、`src/main/resources/static/app.js:197-227`、`src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:20-44`）。BrowserE2EIT/Playwright点击真实页面验证，生产包不含夹具桥（桥仅test/java，`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:110-149`、`src/test/e2e/hotel.spec.js:4-16`）。

## 业务域 → 文件索引

| 业务域 | Controller / Service | Mapper / 主要表（定义与调用出处） |
|---|---|---|
| 住客 | `src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:39-77` / `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:28-81` | `src/main/resources/mapper/UserMapper.xml:6-88`：user、individual |
| 员工 | `src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:41-96` / `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:30-86` | `src/main/resources/mapper/StaffMapper.xml:6-69`：staff |
| 房间 / 客房订单 | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:26-74` / `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:56-92` | `src/main/resources/mapper/RoomMapper.xml:5-190`、`src/main/resources/mapper/OrderMapper.xml:23-126`：room、price_calendar、room_order、room_order_night、individual |
| 餐饮 / 菜品 | `src/main/java/com/winniethepooh/hotelsystembackend/controller/RestaurantController.java:18-27` / `src/main/java/com/winniethepooh/hotelsystembackend/controller/FoodController.java:23-74` | `src/main/resources/mapper/OrderMapper.xml:48-67`、`src/main/resources/mapper/FoodMapper.xml:5-82`：meal_order、meal_order_item、dish、category |
| 统计 / 价格 | `src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:25-78` / `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:117-273` | `src/main/resources/mapper/OrderMapper.xml:198-270`、`src/main/resources/mapper/RoomMapper.xml:23-29`：room_order_night、room_order、room、individual、meal_order、price_calendar |
| 自动推进 / 图片 | `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:29-57` / `src/main/java/com/winniethepooh/hotelsystembackend/controller/UploadController.java:29-33` | room_order、room、scheduler_task_lock（`src/main/resources/mapper/OrderMapper.xml:128-157`）；OSS（`src/main/java/com/winniethepooh/hotelsystembackend/utils/AliOSSUtil.java:34-39`） |
