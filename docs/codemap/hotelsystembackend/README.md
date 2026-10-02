# HotelSystemBackend 代码地图

> 核实代码根：`C:/t/hsb/.nova/worktrees/booking-consistency`（分支 nova/booking-consistency，内容同 nova/booking-agent 的 `48c56aa83f160eaa37fda1c5ba35ca0d2142e1f0`）。出处路径相对代码根。上一版地图基于 `79e476e`；本轮按其后 68 个变动文件增量更新，主要新增住客预订助手（agent 包、AgentController、booking_request 表、Redis 会话/确认卡片、静态页聊天面板），旧出处已按 git 差分重新对齐行号。

## 一句话

酒店管理系统单体后端与同源原生浏览器界面，面向住客USER、经理MANAGER、前台FRONT、餐厅RESTAURANT；覆盖账号、客房预订与房态、餐饮、员工、统计和价格日历，另有只服务住客本人的对话式预订助手（查房询价、提议后由住客点卡片确认预订/支付/取消/点餐）。含50个Controller HTTP入口（其中4个在/agent）、四个精确匿名静态路径和3个分钟任务（`src/main/java/com/winniethepooh/hotelsystembackend/constant/RoleConstant.java:4-7`、`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:26-27`、`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:78-84`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:19-46`；入口出处见[02](02-entrypoints.md)）。

## 技术栈

| 项 | 取值 | 出处 |
|---|---|---|
| 语言 / 框架 | Java 17；Spring Boot 3.3.4，web、validation、data-redis、aop | `pom.xml:5-8`、`pom.xml:30`、`pom.xml:45-56`、`pom.xml:149-152` |
| 持久层 | MyBatis 3.0.3 + MySQL Connector/J；MySQL 8 建表脚本（13 张表） | `pom.xml:57-67`、`src/main/resources/db/schema.sql:1-5`、`src/main/resources/db/schema.sql:199-214` |
| 缓存 / 登录态 / 助手状态 | Redis（StringRedisTemplate）：token、session 反向索引、登录失败计数和限制标记；助手限流计数、待确认卡片、会话历史列表（Lua 原子追加）。没有业务数据缓存，全部 key 见[03 §6](03-data.md#6-外部存储和中间件) | `src/main/java/com/winniethepooh/hotelsystembackend/service/RedisService.java:12-42`、`src/main/java/com/winniethepooh/hotelsystembackend/service/LoginAttemptService.java:14-17`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:63-65`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:61`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/SessionStore.java:90` |
| 大模型 | OpenAI Java SDK 4.73.0，Responses API 流式、`store=false`、推理强度 low；`provider=fake` 时换成脚本化 FakeLlmClient | `pom.xml:40-44`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/OpenAiLlmClient.java:35-45`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/OpenAiLlmClient.java:89-96`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/FakeLlmClient.java:25-27` |
| 鉴权 / 密码 | jjwt 0.9.1 HS256，外部配置密钥；BCrypt 新哈希，兼容并迁移旧 MD5 | `pom.xml:102-120`、`src/main/java/com/winniethepooh/hotelsystembackend/utils/JwtUtils.java:26-43`、`src/main/java/com/winniethepooh/hotelsystembackend/utils/PasswordUtils.java:17-30` |
| 接口文档 | springdoc 2.2.0；dev精确静态`/swagger-ui.html`直接200，原Springdoc重定向入口改为`/swagger-ui-entry.html`；其他profile匿名401 | `pom.xml:95-99`、`src/main/java/com/winniethepooh/hotelsystembackend/config/SwaggerStaticPageConfig.java:8-15`、`src/main/resources/application-dev.yml:11-14`、`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:82-84`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:210-245`、`src/test/e2e/hotel.spec.js:259-281` |
| 对象存储 / 工具 | OSS SDK 3.18.1、hutool 5.8.24、Lombok | `pom.xml:133-137`、`pom.xml:160-170` |
| 测试 | JUnit / Spring Test / MyBatis Test；Testcontainers 1.20.3，MySQL 8.0、Redis 7；Playwright 1.63.0 Chromium | `pom.xml:32-37`、`pom.xml:68-93`、`src/test/java/com/winniethepooh/hotelsystembackend/support/IntegrationTestBase.java:39-56` |
| 浏览器界面 | Spring静态HTML/CSS/JS，同源fetch与sessionStorage；请求token快照及并发401保留原角色登录入口；住客登录后挂载助手面板，SSE 读取 /agent/chat；无需前端构建服务 | `src/main/resources/static/index.html:9-10`、`src/main/resources/static/app.js:25-79`、`src/main/resources/static/app.js:556-647`、`src/main/resources/static/style.css:1-59` |
| 浏览器测试依赖 | @playwright/test固定1.63.0，锁文件含playwright/core同版、Node>=20；npm用于npm ci | `package.json:4-9`、`package-lock.json:12-55`、`README.md:326-331` |
| CI | GitHub Actions：PR 与 master 推送在 ubuntu 上 JDK 21 + Node 24 跑 `mvn -B clean verify`，只用假模型 | `.github/workflows/ci.yml:1-43` |

## 怎么跑

| 做什么 | 命令 / 做法 | 从哪看来 |
|---|---|---|
| 准备依赖服务 | MySQL `HotelSystem` 和 Redis；用 `DB_URL/DB_USERNAME/DB_PASSWORD`、`REDIS_HOST/REDIS_PORT/REDIS_PASSWORD` 覆盖 | `src/main/resources/application.yml:5-14` |
| 配置 JWT | 提供 `JWT_SECRET`，UTF-8 至少 32 字节；缺失或过短拒绝启动 | `src/main/resources/application.yml:43-45`、`src/main/java/com/winniethepooh/hotelsystembackend/utils/JwtUtils.java:26-31` |
| 配置助手 | `HOTEL_AGENT_PROVIDER` 默认 openai（可设 fake）、`HOTEL_AGENT_MODEL` 默认 gpt-6-luna、`OPENAI_API_KEY` 默认空；key 为空时只有 /agent/chat 返回 503 | `src/main/resources/application.yml:36-40`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/OpenAiLlmClient.java:52-56`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:62`、`README.md:253-264` |
| 开发建表 / 启动 | 设 `SPRING_PROFILES_ACTIVE=dev` 等环境变量，`mvn -B -DskipTests package` 后 `java '-Duser.timezone=Asia/Shanghai' -jar target/HotelSystemBackend-0.0.1-SNAPSHOT.jar`；dev 自动执行 schema 和 demo-data | `README.md:196-219`、`src/main/resources/application-dev.yml:4-9` |
| 默认 / 生产建表 | 默认无激活 profile，不自动建表或写演示数据；先在目标库执行 `schema.sql`，再 `java -jar`；旧库上线助手前需手工执行 booking_request DDL | `src/main/resources/application.yml:1`、`README.md:223-251` |
| 构建 | `mvn package`（Maven package 阶段；Boot 插件配置用于可执行 jar） | `pom.xml:175-188` |
| 单元测试 | `mvn test`，Surefire的`*Test/*Tests`，不需要Docker | `pom.xml:189-190`、`README.md:348` |
| 完整验证 | `mvn -B clean verify`：单元、关闭cron的API IT、独立开启cron的十条浏览器E2E；test/e2e profile 固定 fake 模型；Docker、Node20+、npm与Chromium需就绪；API/E2E可分别用skipApiTests/skipBrowserTests过滤 | `pom.xml:34-37`、`pom.xml:189-220`、`README.md:322-353`、`src/test/resources/application-test.yml:3-8`、`src/test/resources/application-e2e.yml:2-6` |
| 浏览器依赖与演示 | `npm ci`、`npx playwright install chromium`；本地隔离演示先`mvn -B -DskipTests package`，再`powershell -NoProfile -File scripts/demo.ps1 -Port 8080` | `README.md:49-59`、`README.md:326-331`、`scripts/demo.ps1:4-49` |
| 助手演示 / 评测 | `scripts/booking-agent.ps1 -Mode demo -Provider fake|openai` 启动隔离环境，`node scripts/booking-agent-demo.mjs` 用 Chromium 跑五步；`-Mode eval` 只跑 `AgentEval#evaluateRealModel`（需要 key），`-Mode check` 跑离线检查；AgentEval 不被 `mvn test/verify` 自动发现 | `README.md:97-109`、`README.md:135-161`、`scripts/booking-agent.ps1:1-19`、`scripts/booking-agent-demo.mjs:10` |
| 图片上传 | 配 `ALIYUN_ACCESS_KEY_ID/ALIYUN_SECRET_KEY/ALIYUN_BUCKET_NAME`；OSS 客户端延迟创建 | `src/main/resources/application.yml:55-59`、`src/main/java/com/winniethepooh/hotelsystembackend/config/AliOSSConfig.java:17-20` |
| 开关 / 端口 / 跨域 | `HOTEL_SCHEDULER_ENABLED` 默认 true；`SERVER_PORT` 默认 8080；`CORS_ALLOWED_ORIGINS` 为空不允许跨域 | `src/main/resources/application.yml:24-48` |

## 目录结构

| 目录 / 文件 | 用途与例子 |
|---|---|
| `pom.xml` | 单Maven模块、依赖与API/browser-e2e独立Failsafe执行（`pom.xml:11-15`、`pom.xml:175-220`） |
| `.github/workflows/ci.yml` | PR / master 推送跑完整 `mvn -B clean verify`，失败上传各报告目录（`.github/workflows/ci.yml:6-56`） |
| `docs/` | 设计文档与地图；并发预订文档仍标为演进设计，不能据此推断实现（`docs/booking-consistency.md:3`）；`docs/nova/` 为功能过程记录 |
| `src/main/java/…/agent` | 住客预订助手：对话循环、8 个工具、待确认动作与幂等确认、Redis 会话、LLM 客户端、请求级截止时间（`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:61-129`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:119-189`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:66-116`） |
| `src/main/java/…/annotation, aspect, context, filter` | 请求约束、角色切面、线程身份与 token 过滤（`src/main/java/com/winniethepooh/hotelsystembackend/annotation/Password.java:14-20`、`src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:20-44`、`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:40-84`） |
| `src/main/java/…/config` | CORS、调度、OSS及dev精确Swagger静态页（`src/main/java/com/winniethepooh/hotelsystembackend/config/GlobalCorsConfig.java:18-30`、`src/main/java/com/winniethepooh/hotelsystembackend/config/SchedulingConfig.java:11-14`、`src/main/java/com/winniethepooh/hotelsystembackend/config/AliOSSConfig.java:17-20`、`src/main/java/com/winniethepooh/hotelsystembackend/config/SwaggerStaticPageConfig.java:8-15`） |
| `src/main/java/…/controller, service, service/impl` | HTTP → 业务接口 / 实现；账号限流、session 与任务在 service 包（`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:29-31`、`src/main/java/com/winniethepooh/hotelsystembackend/service/OrderService.java:17-42`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:36-41`） |
| `src/main/java/…/mapper, entity, dto, vo, constant, exception, utils` | MyBatis、数据载体、常量、错误与工具（模块逐项出处见 [01](01-modules.md)） |
| `src/main/resources/` | 默认 / dev 配置，5 个 Mapper XML，db/schema.sql 与 demo-data.sql；`agent/` 下系统提示词与会话追加 Lua（`src/main/resources/application.yml:19-22`、`src/main/resources/application-dev.yml:7-9`、`src/main/resources/agent/system-prompt.txt:1-10`、`src/main/resources/agent/append-session.lua:1-13`） |
| `src/main/resources/static/` | 原生角色页面、同源API调用、住客助手面板与dev Swagger静态页（`src/main/resources/static/index.html:1-28`、`src/main/resources/static/app.js:14-24`、`src/main/resources/static/app.js:556-758`、`src/main/resources/static/swagger-ui.html:8-16`） |
| `package.json`、`package-lock.json`、`playwright.config.js` | Node浏览器测试依赖及执行配置（`package.json:4-9`、`package-lock.json:12-55`、`playwright.config.js:3-21`） |
| `scripts/demo.ps1`、`scripts/booking-agent.ps1`、`scripts/booking-agent-demo.mjs` | 临时独立MySQL/Redis、dev jar、随机密钥、退出清理；助手演示 / 评测包装与 Chromium 五步演示（`scripts/demo.ps1:12-50`、`scripts/booking-agent.ps1:10-19`、`scripts/booking-agent-demo.mjs:10-15`） |
| `src/test/e2e/`、`BrowserE2EIT.java` | 十条页面业务流程（TC051–054 助手、TC132–137 原流程）、测试限定JDK夹具桥、真实cron与产物（`src/test/e2e/hotel.spec.js:79-759`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:114-245`） |
| `src/test/java/…/support`、`src/test/java/…/Agent*`、`src/test/java/…/agent/` | HTTP 集成基类、夹具和 SQL 条数计数器；助手 API 集成、单元与真实模型评测（`src/test/java/com/winniethepooh/hotelsystembackend/support/IntegrationTestBase.java:83-114`、`src/test/java/com/winniethepooh/hotelsystembackend/support/Fixtures.java:29-43`、`src/test/java/com/winniethepooh/hotelsystembackend/support/SqlCounter.java:13-29`、`src/test/java/com/winniethepooh/hotelsystembackend/AgentActionIT.java:51`、`src/test/java/com/winniethepooh/hotelsystembackend/AgentEval.java:102`） |
| `src/test/resources/` | test关闭cron，e2e开启cron；两者助手 provider=fake，test 超时 5 秒；测试阈值、跨域来源、评测用例（`src/test/resources/application-test.yml:1-13`、`src/test/resources/application-e2e.yml:1-6`、`src/test/resources/agent/eval-cases.json:1`） |

## 模块一览

| 模块 | 一句话 |
|---|---|
| [M1 启动与全局配置](01-modules.md#m1-启动与全局配置) | 启动、CORS、调度开关、OSS 和 profile |
| [M2 鉴权与权限](01-modules.md#m2-鉴权与权限) | Filter 校验、上下文清理、类 / 方法角色注解 |
| [M3 接口层 controller](01-modules.md#m3-接口层-controller) | 50 个 HTTP 入口与请求约束 |
| [M4 业务层 service](01-modules.md#m4-业务层-service) | 校验、锁、计价、报价/空房查询、状态流转和统计 |
| [M5 定时任务与 Redis 工具](01-modules.md#m5-定时任务与-redis-工具) | 3 个 cron 任务、session 反向索引、失败限流 |
| [M6 持久层 mapper](01-modules.md#m6-持久层-mapper) | 5 个 Mapper 接口 + XML，共 93 条 SQL |
| [M7 数据模型](01-modules.md#m7-数据模型-entity--dto--vo) | entity / dto / vo 与 13 张 DDL 表 |
| [M8 常量](01-modules.md#m8-常量-constant) | 角色、状态、房型默认价 |
| [M9 异常](01-modules.md#m9-异常-exception) | 带 HTTP 状态的业务错误和统一返回 |
| [M10 工具](01-modules.md#m10-工具-utils) | 日期区间、密码哈希、OSS |
| [M11 测试](01-modules.md#m11-测试) | 单元、API集成、独立Playwright/JDK桥浏览器验证、助手评测 |
| [M12 浏览器界面](01-modules.md#m12-浏览器界面) | 四角色导航、订房/点餐/清洁/统计/员工操作、住客助手面板 |
| [M13 住客预订助手](01-modules.md#m13-住客预订助手-agent) | SSE 对话、工具调用、确认卡片与 booking_request 幂等 |

## 建议阅读顺序

1. `src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:40-84`、`src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:20-44`：请求身份与角色。
2. `src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:56-106`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:94-254`：两类订单。
3. `src/main/resources/db/schema.sql:85-124`、`src/main/resources/mapper/OrderMapper.xml:36-170`：金额、按晚明细、条件更新和任务锁。
4. `src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:29-57`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:64-126`：房态推进。
5. `src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:80-109`、`src/test/java/com/winniethepooh/hotelsystembackend/support/IntegrationTestBase.java:39-114`：统计口径与验证方式。
6. 助手：`src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:19-46` → `src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:61-129` → `src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:119-315` → `src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:66-147` 与 `src/main/resources/db/schema.sql:199-214`；流程见 [04 F10](04-flows.md#f10-住客助手对话提议与幂等确认)。
7. 做「下单幂等 + 每日库存 + Redis 缓存」前，先看 [02 客房订单写入口一览](02-entrypoints.md#客房订单写入口与冲突检查一览)、[02 房间与价格读写入口](02-entrypoints.md#房间价格日历读写入口一览)、[03 room_order_night 写入与用途](03-data.md#room_order_night-的写入与用途)、[03 Redis 全部 key](03-data.md#6-外部存储和中间件)。
8. 改功能前看 [05](05-rules.md) 和 [06](06-conventions.md)。

## 原待确认项的核实结论

| 编号 | 当前结论 / 依据 |
|---|---|
| Q1 | 已有可重复执行的 DDL；金额与 total_price 显式写入，手机号、员工账号、价格日历、booking_request.request_id 有唯一键，房间号无唯一键（`src/main/resources/db/schema.sql:8-214`、`src/main/resources/mapper/OrderMapper.xml:36-92`） |
| Q2 | LocalDate 查询仍有未显式标 `@DateTimeFormat` 的入口；ISO 日期请求写法及响应断言见 `src/test/java/com/winniethepooh/hotelsystembackend/PriceCalendarIT.java:98-110`、`src/test/java/com/winniethepooh/hotelsystembackend/BusinessStatsIT.java:112-123`、`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:294-304`；格式作为这些入口的集成测试契约，无新增全局格式配置 |
| Q3 | CorsFilter 设置最高优先级，允许来源由配置读取（`src/main/java/com/winniethepooh/hotelsystembackend/config/GlobalCorsConfig.java:18-30`）；预检断言见 `src/test/java/com/winniethepooh/hotelsystembackend/CorsIT.java:23-38` |
| Q4 | dev精确静态Swagger入口直接200，UI资源与/v3/api-docs放行；其他profile匿名401，四个业务静态GET/HEAD路径独立放行（`src/main/java/com/winniethepooh/hotelsystembackend/config/SwaggerStaticPageConfig.java:8-15`、`src/main/resources/application-dev.yml:11-14`、`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:78-84`、`src/test/e2e/hotel.spec.js:259-281`、`src/test/java/com/winniethepooh/hotelsystembackend/filter/LoginFilterTest.java:74-111`） |
| Q5 | 待确认：实际部署的 JVM 默认时区与 MySQL 会话时区是否对齐。测试明确为 Asia/Shanghai / +08:00，生产 JDBC 只声明 serverTimezone，任务仍混用 JVM 时刻和数据库 NOW；助手另用固定 Asia/Shanghai 时钟算「今天」和卡片过期时刻（`src/test/java/com/winniethepooh/hotelsystembackend/support/IntegrationTestBase.java:44-53`、`pom.xml:37`、`src/main/resources/application.yml:6`、`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:35-57`、`src/main/resources/mapper/OrderMapper.xml:149`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:40`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:58`） |
| Q6 | DDL 明确菜品 1 上架、0 下架，点餐仅接受 1；菜品展示查询仍不按 status 筛选，助手 list_menu 在内存中只留 status=1（`src/main/resources/db/schema.sql:162-171`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:234-236`、`src/main/resources/mapper/FoodMapper.xml:77-82`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:259-262`） |
| Q7 | 待确认：餐饮状态1的业务名称。UI与助手 list_my_orders 都标「待完成」；常量PENDING与实体「已送达」未业务拍板，操作仍0→1→2或0→3（`src/main/resources/static/app.js:13`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:253-254`、`src/main/java/com/winniethepooh/hotelsystembackend/constant/MealOrderStatusConstant.java:4-7`、`src/main/java/com/winniethepooh/hotelsystembackend/entity/MealOrder.java:16`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:23-31`）。 |
| Q8 | 已有单元与 Testcontainers 集成测试组织（`pom.xml:189-220`、`src/test/java/com/winniethepooh/hotelsystembackend/support/IntegrationTestBase.java:39-66`）；待确认：默认 profile 的 contextLoads 在完全没有 MySQL/Redis 的独立环境能否启动，它仅注入 JWT 测试密钥，不注入容器连接；助手的 Hikari 包装把初始化失败超时设为 -1，进一步影响该判断（`src/test/java/com/winniethepooh/hotelsystembackend/HotelSystemBackendApplicationTests.java:9-19`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentIoConfiguration.java:63-70`） |
| Q9 | 待确认：get_price_quote / propose_booking 走 `quoteRoomService`，复用带 `for update` 的重叠查询，且助手工具调用包在带截止时间的事务里，命中区间的订单行在工具事务结束前被锁；搜索空房走无锁的 NOT EXISTS。报价路径加锁是否有意，影响后续库存/缓存方案对读路径的设计（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:52-57`、`src/main/resources/mapper/OrderMapper.xml:99-105`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:148-154`、`src/main/resources/mapper/RoomMapper.xml:5-16`） |
| Q10 | 待确认：booking_request 的保留期限。代码只有插入、条件更新和按 request_id 查询，没有清理 SQL 或任务，记录永久保留（`src/main/resources/mapper/OrderMapper.xml:5-16`、`src/main/java/com/winniethepooh/hotelsystembackend/mapper/OrderMapper.java:85-89`） |

当前真正待确认 5 项：Q5、Q7、Q8、Q9、Q10。本地图描述源码与测试契约，不把未执行的环境验证写成通过。
