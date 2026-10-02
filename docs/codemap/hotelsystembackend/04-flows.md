# 04 端到端流程

以当前源码描述十条核心流程（F1–F10，F10 为本轮新增的住客助手）；时刻/日期口径、事务和状态边界均有出处。只记录现有行为，测试存在不等于本地图执行过验证。

## F1 请求鉴权与角色限制

触发入口：Spring注册的LoginFilter和Controller角色切面（`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:20-21`、`src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:20-22`）。

1. CORS最高优先级处理配置来源的预检；非预检请求进入Filter（`src/main/java/com/winniethepooh/hotelsystembackend/config/GlobalCorsConfig.java:18-30`）。
2. 精确公共业务路径为/user/login、/user/register、/staff/login；/、/index.html、/app.js、/style.css仅GET/HEAD免token；dev另放行Swagger资源边界。公共请求直接转发，仍进入finally清上下文（`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:26-27`、`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:46-50`、`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:73-84`）。
3. 其他请求读header token，缺失、Redis无键、JWT验签失败/过期→sendError401；Redis值只检查非null，不以它重新读取数据库角色（`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:51-70`）。
4. 验签成功把JWT id/role写ThreadLocal，RoleRequired按方法优先、否则类注解取允许集合；角色缺失401，不在集合403。business类只MANAGER、restaurant类只RESTAURANT（`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:62-66`、`src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java:23-44`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:20`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/RestaurantController.java:11`）。
5. 请求结束或异常均清id/role；Controller业务异常返回对应HTTP+Result，意外500通用提示。过滤器sendError不承诺Result格式（`src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java:72-75`、`src/main/java/com/winniethepooh/hotelsystembackend/exception/GlobalExceptionHandler.java:33-55`）。

```mermaid
flowchart TD
    A[请求] --> C[CORS预检处理]
    C --> F[LoginFilter]
    F --> P{公共业务或静态GET/HEAD或dev Swagger?}
    P -- 是 --> H[Controller]
    P -- 否 --> T[token存在且Redis有键?]
    T -- 否 --> E[HTTP 401]
    T -- 是 --> J{JWT有效?}
    J -- 否 --> E
    J -- 是 --> B[JWT id与role写BaseContext]
    B --> R{RoleRequired允许?}
    R -- 角色缺失 --> E
    R -- 不允许 --> X[HTTP 403]
    R -- 允许或无注解 --> H
    H --> S[Service与Mapper]
    S --> O[Result或统一错误响应]
    O --> Z[finally清BaseContext]
    E --> Z
    X --> Z
```

## F2 登录、会话登记与撤销

触发入口：POST /user/login、POST /staff/login（`src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:45-62`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:41-56`）。

1. Controller把账号及remoteAddr交LoginAttemptService.guard；账号/IP任一锁存在→429；凭据错误记录两种失败计数，成功仅清账号失败计数（`src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:48-49`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:43-44`、`src/main/java/com/winniethepooh/hotelsystembackend/service/LoginAttemptService.java:31-55`）。
2. 住客按手机找user、员工只查启用且未删的account；不存在/密码错统一HTTP400/code1。BCrypt匹配新哈希，旧MD5恒时比较，登录成功升级旧哈希；住客还更新last_login（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:43-51`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:31-38`、`src/main/resources/mapper/StaffMapper.xml:35-39`、`src/main/java/com/winniethepooh/hotelsystembackend/utils/PasswordUtils.java:21-30`、`src/main/java/com/winniethepooh/hotelsystembackend/exception/PasswordIncorrectException.java:5-8`）。
3. Controller用id/role生成HS256 JWT，随机jti、有效期3小时；密钥启动时必须至少32 UTF-8字节（`src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:50-56`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:46-50`、`src/main/java/com/winniethepooh/hotelsystembackend/utils/JwtUtils.java:21-43`）。
4. principal统一ROLE_id，先按session:principal删除旧token+索引，再顺序SET token→principal及session:principal→token，两键均TTL3小时，无SCAN/KEYS。Redis操作未用事务/Lua，不能据此宣称并发登录严格原子（`src/main/java/com/winniethepooh/hotelsystembackend/service/RedisService.java:19-42`）。
5. 返回LoginVO。POST /staff/logout、成功完整改密、停用/删除员工均按相同principal撤销；原/新密码必须成对，缺一400/code1且不改哈希/session；两项都空可只改邮箱（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:56-72`、`src/test/java/com/winniethepooh/hotelsystembackend/PasswordHashingIT.java:72-107`）（`src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java:58-62`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java:52-64`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java:63-72`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java:55-66`）。

```mermaid
sequenceDiagram
    participant C as 客户端
    participant L as 登录Controller
    participant G as LoginAttemptService
    participant S as User或StaffService
    participant DB as MySQL
    participant R as RedisService
    C->>L: POST登录
    L->>G: guard(账号,remoteAddr)
    G->>G: 检查账号与IP锁
    G->>S: 登录校验
    S->>DB: 查询账号及必要的旧哈希升级
    S-->>G: User或Staff
    G->>G: 清账号失败计数
    G-->>L: 身份
    L->>L: JWT(id,role,jti,3小时)
    L->>R: saveSession(ROLE_id,token)
    R->>R: 反向索引撤销旧token
    R->>R: 两键均SET TTL3小时
    L-->>C: LoginVO
    C->>L: POST /staff/logout
    L->>R: revokeSession(当前principal)
```

## F3 住客订房、支付与取消

触发入口：POST /order的USER分支，返回生成id（`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:56-64`）。

1. 公开Service方法开启事务，入住/离店非null、日期差1–30晚、退房晚于入住、新订入住日期不得早于今天；同日过去时刻仍允许（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:101-107`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:197-203`）。
2. 按未删room_number FOR UPDATE锁房间，不存在404；查未删进行中订单checkin<新checkout且checkout>新checkin，重叠409、首尾相接允许。未付进行中订单也占区间，当前清洁/维修房态不直接拒绝预订（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:108-109`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:192-208`、`src/main/resources/mapper/RoomMapper.xml:17-19`、`src/main/resources/mapper/OrderMapper.xml:99-104`）。
3. 一次读房型日历，按[入住日,离店日)逐晚取价，缺日199/299/499；复用或新建姓名/手机/身份证三项匹配入住人（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:110-111`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:211-222`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:257-265`）。
4. user_id为当前用户，主单total_amount=夜价和、pay0/status0，生成id；一条批量INSERT写room_order_night，主单/入住人/夜明细失败均回滚。两条客房INSERT都写total_amount（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:113-122`、`src/main/resources/mapper/OrderMapper.xml:36-60`、`src/main/resources/mapper/OrderMapper.xml:88-93`）。
5. POST /order/pay?id：条件UPDATE要求本人、未删、status0/pay0且created_at≥数据库NOW()-15分钟，成功pay1。0行后查单区分404不存在、403他人、409状态/期限；GET支付已无映射（`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:81-85`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:165-189`、`src/main/resources/mapper/OrderMapper.xml:126-132`）。
6. POST /order/cancel?id：条件UPDATE要求本人、未删、进行中、尚未到入住时刻、pay0/1；status2，已付pay2退款、未付仍0，不直接改房态；0行同样区分404/403/409。退款只记录状态，无支付渠道（`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:88-92`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:173-184`、`src/main/resources/mapper/OrderMapper.xml:134-139`）。
7. 未支付超时取消、已付入住/离店由F5推进；终态不能再支付/取消（同上条件SQL）。
8. 这条链路没有请求级幂等：POST /order 不带 requestId，重复提交会按冲突规则各自处理；住客助手的确认走同一个 `insertRoomOrderByUserService`，外面再包一层 booking_request 幂等，见 F10（`src/main/java/com/winniethepooh/hotelsystembackend/dto/InsertRoomOrderDTO.java:10-18`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:121-127`）。

```mermaid
flowchart TD
    A[POST /order USER] --> B[事务: 校验1到30晚]
    B --> C[锁房间行]
    C --> O{左闭右开区间重叠?}
    O -- 是 --> E[409回滚]
    O -- 否 --> P[一次读日历并逐晚计价]
    P --> I[复用或新建入住人]
    I --> W[主单金额加夜价快照同事务]
    W --> U[返回id: status0 pay0]
    U -- 本人期限内POST pay --> PA[pay1]
    U -- 未来入住POST cancel --> CU[status2 pay0]
    PA -- 未来入住POST cancel --> CP[status2 pay2]
    U -- 超时任务 --> CU
    PA --> T[F5时刻入住与离店]
```

## F4 前台开单与改期换房

触发入口：POST /order的FRONT分支、PUT /order/{id}只FRONT（`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:56-71`）。

1. 前台开单也在事务中复用F3校验、房间行锁、重叠、后端计价和夜明细；user_id为空，paid仅TRUE写pay1，其余pay0，status0（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:95-125`、`src/main/resources/mapper/OrderMapper.xml:36-47`）。
2. 当前JVM时刻已到checkin才尝试房间0→1，清洁/维修不覆盖；未收款前台单也按时刻入住/离店，不进入15分钟住客超时取消，但只pay1计营收；Controller返回data=null（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:123-124`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:63-64`、`src/main/resources/mapper/RoomMapper.xml:23-25`、`src/main/resources/mapper/OrderMapper.xml:141-149`、`src/main/resources/mapper/OrderMapper.xml:286-292`、`src/main/resources/mapper/OrderMapper.xml:325-332`、`src/main/resources/mapper/OrderMapper.xml:218-220`）。
3. 改订单事务先读未删订单，roomId为String形式房间id，非房号，非数字400；null字段沿用原房间/时间。按房间id升序锁来源和目标，再FOR UPDATE读订单；仍须进行中且房间未被并发改变，缺单/房404、状态变化409（`src/main/java/com/winniethepooh/hotelsystembackend/dto/ModifyRoomOrderDTO.java:9-12`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:129-144`）。
4. 改期校验1–30晚但允许保留过去入住日期，查重叠排除自身；重新按当前日历计算整单total_amount，条件UPDATE后物理删除该订单全部night、批量重写；任何失败回滚，不生成差额支付/退款（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:145-154`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:197-203`、`src/main/resources/mapper/OrderMapper.xml:81-97`）。
5. 来源与目标不同且当前处于新入住区间，旧房只1→2、新房只0→1；DTO.status忽略。纯房间信息修改与房态联动差异见F6（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:155-158`、`src/main/resources/mapper/RoomMapper.xml:23-28`）。

```mermaid
flowchart TD
    A[POST /order FRONT] --> B[共享事务: 校验锁房查重计价]
    B --> P{paid为TRUE?}
    P -- 是 --> P1[pay1]
    P -- 否 --> P0[pay0]
    P1 --> W[user_id空 主单加夜价]
    P0 --> W
    W --> T{时刻已到checkin?}
    T -- 是 --> S[房间仅0转1]
    T -- 否 --> F[等入住任务]
    S --> Z[data为null]
    F --> Z
    M[PUT /order/id] --> L[事务: 按id升序锁来源与目标房]
    L --> O[锁读进行中订单]
    O --> C[校验新区间并排除自身查重]
    C --> R[重算金额 删除全部旧夜价并重写]
    R --> N[当前入住期间换房: 旧1转2 新0转1]
```

## F5 定时任务与客房状态机

触发入口：Spring每分钟第0/1/2秒；hotel.scheduler.enabled缺省true开启触发，false时任务Bean仍可直接调用（`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:29-57`、`src/main/java/com/winniethepooh/hotelsystembackend/config/SchedulingConfig.java:7-15`）。

1. 第0秒退房任务@Transactional：INSERT IGNORE确保scheduler_task_lock中releaseExpiredRooms行；条件UPDATE仅last_run早于数据库本分钟起点或null者成功，失败者返回。owner为任务Bean随机UUID；抢占与整批更新同事务，异常回滚数据库写入；当前没有失败重试逻辑（`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:18`、`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:29-40`、`src/main/resources/mapper/OrderMapper.xml:163-171`、`src/main/resources/db/schema.sql:190-197`）。
2. 抢占者取JVM now，查进行中未删、已付或user_id为空且checkout≤now；逐单房间只1→2清洁中，订单status1完成。原已清洁/维修不覆盖（`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:35-40`、`src/main/resources/mapper/OrderMapper.xml:286-292`、`src/main/resources/mapper/RoomMapper.xml:26-28`）。成功本分钟后新增到期单等后续轮次，下一次定时触发与独立失败重试机制不同；大批次整行持锁限制在源码注释注明（`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:32-34`）。
3. 第1秒入住任务@Transactional：查进行中未删、已付或前台、checkin≤JVM now<checkout；房间只0→1，不覆盖清洁/维修；已离店或终态不启用（`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:44-51`、`src/main/resources/mapper/OrderMapper.xml:325-332`、`src/main/resources/mapper/RoomMapper.xml:23-25`）。
4. 第2秒超时任务@Transactional：单UPDATE住客user_id非空、进行中未删、pay0且created_at<数据库NOW()-15分钟→status2/pay0；前台未收款单排除（`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:54-57`、`src/main/resources/mapper/OrderMapper.xml:141-150`）。
5. 调度注解表达期望节奏，不承诺进程暂停、异常后的精确执行延迟。JVM LocalDateTime与SQL NOW分别取时，实际生产会话时区仍待确认Q5；IT定义了上海JVM和+08测试库会话（`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:35-57`、`src/main/resources/application.yml:6`、`src/test/java/com/winniethepooh/hotelsystembackend/support/IntegrationTestBase.java:45-52`、`pom.xml:37`）。

```mermaid
flowchart TD
    U[USER新单: status0 pay0] -- 期限内本人POST pay --> P[status0 pay1]
    U -- 创建超15分钟任务 --> C0[status2 pay0]
    U -- 尚未入住本人POST cancel --> C0
    P -- 尚未入住本人POST cancel --> C2[status2 pay2]
    F[FRONT新单: status0 pay0或1] --> O[入住候选]
    P --> O
    O -- checkin到达且未离店 --> R1[房间仅0转1]
    P -- checkout到达退房任务 --> D[status1 房间仅1转2]
    F -- checkout到达退房任务 --> D
    P -- PUT /rooms占用转空闲 --> D2[status1 房间0]
    F -- PUT /rooms占用转空闲 --> D2
    X[终态或软删] --> STOP[支付取消和入住推进条件不匹配]
```

补充写入边界：DELETE /order/{id}仍不限制状态直接软删；PUT /order/{id}只改进行中订单（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:141-162`、`src/main/resources/mapper/OrderMapper.xml:111-124`）。

## F6 前台改房态与退房联动

触发入口：PUT /rooms允许MANAGER/FRONT；PUT /rooms/{id}房间信息仅MANAGER（`src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java:42-60`）。

1. 改房态@Transactional，先校验status非null且0–3→否则400；按房间id FOR UPDATE锁未删room，不存在404，查询订单不再作为房间存在的前置条件（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:42-44`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:64-68`、`src/main/resources/mapper/RoomMapper.xml:20-22`）。
2. 仅原房态1且目标0时，查该房间当前有效订单：进行中未删、已付或前台、checkin≤now<checkout；有单才status1完成，没有单也能改空闲（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:69-73`、`src/main/resources/mapper/OrderMapper.xml:335-345`）。
3. 最后更新房态0/1/2/3；其他转换不联动订单。入住任务只0→1，因此员工设2/3不会被任务覆盖；0但仍有有效单可能在下一轮入住任务变1（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:69-73`、`src/main/resources/mapper/RoomMapper.xml:44-49`、`src/main/resources/mapper/RoomMapper.xml:23-25`）。
4. 房间信息PUT /rooms/{id}单独验证存在、不同房号查重、房型合法、非null状态0–3，动态更新非null信息；它不会完成订单，与专用改房态入口语义不同（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:91-98`、`src/main/resources/mapper/RoomMapper.xml:51-65`）。
5. 房态墙入住人按覆盖当日最小id取，含离店日且不筛订单支付/状态；checkin/out时刻只当前有效已付/前台且房态1时补。无入住人Service给空Individual；墙查询一条SQL（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java:107-110`、`src/main/resources/mapper/RoomMapper.xml:149-182`）。统计使用[入住日,离店日)，不能用墙的当天展示条件替代（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:95-109`）。

```mermaid
flowchart TD
    A[PUT /rooms] --> V{status非null且0到3?}
    V -- 否 --> E[400]
    V -- 是 --> L[事务锁读room]
    L --> N{未删房间存在?}
    N -- 否 --> M[404]
    N -- 是 --> O{原1且目标0?}
    O -- 否 --> W[写合法房态]
    O -- 是 --> Q{当前有效订单存在?}
    Q -- 是 --> D[订单DONE]
    Q -- 否 --> W
    D --> W
    W --> S[提交]
```

## F7 住客点餐与餐厅接单

触发入口：POST /order/meal-order与PUT /order/meal-order/{id}/cancel只USER；GET /restaurant/live-order与PUT /restaurant/status由类注解限RESTAURANT（`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:95-106`、`src/main/java/com/winniethepooh/hotelsystembackend/controller/RestaurantController.java:11-27`）。

1. 下单入口@Valid逐明细数量非null且≥1；Service事务兜底空列表/空明细/id或数量非法400。空列表消息「订单明细不能为空」，写主单之前拒绝（`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:97`、`src/main/java/com/winniethepooh/hotelsystembackend/dto/InsertMealOrderDTO.java:15-16`、`src/main/java/com/winniethepooh/hotelsystembackend/entity/MealOrderItem.java:14-18`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:226-233`）。
2. 每条查未删菜品，缺失404，status非1下架409；取DB price覆盖客户端unitPrice，totalPrice=price×qty，主单totalAmount为明细总和。客户端金额缺失/不一致均不参与校验（`src/main/resources/mapper/FoodMapper.xml:5-7`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:234-243`）。
3. dto.id清null、user_id取当前用户；生成主单id，order_status0，再逐明细设置mealOrderId写unit_price与total_price；异常整单回滚（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:241-248`、`src/main/resources/mapper/OrderMapper.xml:61-80`）。
4. 住客取消单条条件UPDATE仅本人未删新单0→3；不匹配统一409。餐厅先查单，缺失404、终态2/3或非法跳跃409；仅0→1→2和0→3，再条件UPDATE比较原状态，并发变化409（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:252-254`、`src/main/resources/mapper/OrderMapper.xml:158-161`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:23-31`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:54-59`、`src/main/resources/mapper/OrderMapper.xml:151-156`）。
5. 看板近24小时未删单0/1/2计数，空集0；未删全部状态列表倒序，逐单读未删明细及菜名，仍有明细N+1且JOIN菜名不筛菜品软删；明细响应字段name映射dish.name，mealCard按name × quantity及totalPrice显示，名称缺失/为空才显示「菜品」。TC134在餐厅实时订单卡片断言夹具X的真实菜名 × 2（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java:37-49`、`src/main/resources/mapper/OrderMapper.xml:352-381`、`src/main/java/com/winniethepooh/hotelsystembackend/entity/MealOrderItem.java:18-19`、`src/main/resources/static/app.js:366-371`、`src/test/e2e/hotel.spec.js:186-194`）。
6. 餐饮评价仅本人未删完成单order_status2，星级1–5、文本≤500，其他归属/状态409（`src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java:41-45`、`src/main/java/com/winniethepooh/hotelsystembackend/dto/CommentOrderDTO.java:8-17`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:76-83`、`src/main/resources/mapper/OrderMapper.xml:27-34`）。

```mermaid
flowchart TD
    A[POST /order/meal-order] --> V[事务: 明细和数量校验]
    V --> D[逐条查未删上架菜品]
    D --> P[DB价覆盖客户端金额]
    P --> W[主单0加明细单价和总价]
    W --> S0[新订单0]
    S0 -- 餐厅顺序推进 --> S1[状态1]
    S1 -- 餐厅顺序推进 --> S2[完成2]
    S0 -- 本人或餐厅取消 --> S3[取消3]
    S2 --> E[终态不能再改]
    S3 --> E
    S2 -- 本人POST评价 --> C[评分1到5 文本最多500]
```

## F8 经营统计与价格日历

触发入口：BusinessController八个接口，类RoleRequired只MANAGER（`src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:20-78`）。

1. 每日营收按room_order_night.night SUM(price)，只关联已付、非取消、未删主单；跨月按夜日分摊、不含餐饮。ADR=当晚收入÷售出间夜数，HALF_UP2位，无数据0；入住按[入住日,离店日)去重有效room_id，除以当前未删且当日已建房间数，0分母0（`src/main/resources/mapper/OrderMapper.xml:211-234`、`src/main/resources/mapper/OrderMapper.xml:256-264`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:85-113`）。
2. stats一次读上月初到本月末夜收入，加有效占用订单和房间，共三次查询；内存算今日/昨日、本月/上月、ADR/入住率与环比（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:117-146`）。
3. trend三条、heatmap两条、detail六条批量查询后按日/楼层内存算；日期闭区间最多366天，倒序400；无数据营收/ADR/比例0。热力图用当日楼层房间与占用集合交集；明细customerCount按individual创建累加，复住率按当日入住人中此前有效入住的去重人数计算，HALF_UP1位，PageBean不分页（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:55-58`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:151-165`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:188-212`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:222-259`、`src/main/resources/mapper/OrderMapper.xml:267-284`、`src/main/java/com/winniethepooh/hotelsystembackend/utils/LocalDateUtil.java:12-21`）。
4. 房型营收按room.room_type三条求和，空集0；Top10单条按菜名SUM(quantity)降序，完整结束日、排除取消/主单及菜品删除，未筛明细软删。两者没有checkedDates跨度校验（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:170-183`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:217-218`、`src/main/resources/mapper/OrderMapper.xml:224-252`）。
5. POST价格日历DTO验证日期必填、roomType0–2、price严格>0，checkedDates倒序/超366日400；单条批量UPSERT靠(room_type,date)唯一键覆盖并恢复软删，不逐日SELECT/UPDATE（`src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:68-71`、`src/main/java/com/winniethepooh/hotelsystembackend/dto/DynamicUpdatePriceDTO.java:14-25`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:263-264`、`src/main/resources/mapper/RoomMapper.xml:35-42`、`src/main/resources/db/schema.sql:71-83`）。
6. GET日历一次读后按日期排列，未设日对应null，不补默认价；同样checkedDates，roomType请求未约束0–2（`src/main/java/com/winniethepooh/hotelsystembackend/controller/BusinessController.java:74-78`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java:269-273`）。
7. 新单计价、列表/房态墙读取日历；改日历不回写旧夜价，改订单按新价整单重写。旧订单若无night行，营收SQL不从total_amount自动补夜价（`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:147-154`、`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:211-218`、`src/main/resources/mapper/RoomMapper.xml:122-129`、`src/main/resources/mapper/RoomMapper.xml:164-170`、`src/main/resources/mapper/OrderMapper.xml:211-221`）。

具体公式和隐式历史限制见[05业务规则](05-rules.md#14-经营统计)、[06约定与坑](06-conventions.md#坑与隐式限制)。

```mermaid
sequenceDiagram
    participant C as MANAGER
    participant BC as BusinessController
    participant BS as BusinessServiceImpl
    participant OM as OrderMapper
    participant RM as RoomMapper
    participant UM as UserMapper
    C->>BC: GET /business/detail
    BC->>BS: getBusinessDetail
    BS->>BS: checkedDates最多366日
    BS->>OM: 夜营收、有效占用订单、入住与复住人数 3条
    BS->>RM: 全部未删房间 1条
    BS->>UM: 区间入住人创建时间、起始日前总数 2条
    loop 每日只计算内存数据
        BS->>BS: 营收 ADR 入住率 客数 复住率
    end
    BS-->>BC: PageBean total为日数
    C->>BC: POST /business/calendar
    BC->>BC: DTO约束
    BC->>BS: updateRoomPriceService
    BS->>BS: checkedDates
    BS->>RM: 一条多VALUES UPSERT
```

## F9 浏览器页面与真实cron端到端链路

1. 浏览器GET首页，加载同源style.css/app.js；sessionStorage恢复角色和视图，无会话显示登录。roleViews限制客户端导航；api()先快照请求token再调用生产接口；携token的401仅当当前session token仍相同时，记住原角色并清会话回对应登录入口。后续同令牌401仅报失效，不把员工入口改成住客入口，也不清除更新后的会话。动态文本经textContent，按钮/提交等待时禁用（`src/main/resources/static/index.html:9-26`、`src/main/resources/static/app.js:19-41`、`src/main/resources/static/app.js:52-120`、`src/main/resources/static/app.js:220-237`、`src/main/resources/static/app.js:759-760`）。
2. 客房页面提交默认14:00/12:00时刻；我的订单调用POST pay/cancel、PUT meal cancel和POST评价；前台墙清洁完成PUT /rooms；餐厅顺序推进/新单取消；经理价格、分析和员工创建/启停（`src/main/resources/static/app.js:282-394`、`src/main/resources/static/app.js:413-551`）。退出的员工POST撤销后清浏览器，住客只清本浏览器（`src/main/resources/static/app.js:211-218`）。
3. browser-e2e独立Failsafe调用BrowserE2EIT（十条：TC051–054助手、TC132–137原流程；e2e profile 用 FakeLlmClient，每例先 reset 其脚本队列，`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:81`、`src/test/resources/application-e2e.yml:2-4`），独立MySQL/Redis+e2e应用开启cron，每例reset/seedE2eBase，Node依编号跑一例真实Chromium页面；TC136另以空库正本脚本启动dev jar（`pom.xml:194-211`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:53-87`、`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:170-243`）。
4. 测试JDK桥仅提供data/state/checkin/checkout/expire及三个助手动作（读 Fake 输入、排队延迟回复、删除 Redis 卡片模拟过期，`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:132-146`），推进时间夹具后等待真实分钟调度，不直接调用任务。TC132验证逐晚营收及退房，TC133完整70秒持续不取消、随后入住/退房/清洁；TC135在第二经理真实登录撤销旧令牌后，等待stats/trend/top10三条真实401及networkidle，再断言仍是员工登录和失效提示；TC134断言餐厅卡片的真实菜名明细；其余用例覆盖餐饮、停用、Swagger与退款重订（`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:114-168`、`src/test/e2e/hotel.spec.js:66-164`、`src/test/e2e/hotel.spec.js:166-320`、`src/test/e2e/hotel.spec.js:246-255`）。
5. Java等待Node退出0并核一例JUnit XML无失败/错误/跳过；独立Java报告、Playwright日志/截图/trace保留到target输出路径。这里描述断言逻辑，实际通过情况以执行报告为准（`src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java:170-192`、`pom.xml:202-211`、`playwright.config.js:10-21`）。

```mermaid
sequenceDiagram
    participant J as BrowserE2EIT
    participant B as Playwright浏览器
    participant A as 独立Spring应用
    participant F as 回环夹具桥
    participant DB as 独立MySQL
    J->>A: e2e profile开启真实cron
    J->>B: Node运行指定TC
    B->>A: 登录与页面业务按钮HTTP
    A->>DB: 生产业务写入
    B->>F: 指定订单时间夹具
    F->>DB: 改checkin/checkout或created_at
    A->>DB: 每分钟真实任务条件更新
    B->>F: 轮询state
    F-->>B: 数据库状态
    B->>A: 页面刷新和断言
    B-->>J: 退出码与JUnit XML
```

## F10 住客助手对话、提议与幂等确认

触发入口：静态页助手面板的 POST /agent/sessions、POST /agent/chat、POST /agent/actions/{id}/confirm|cancel；类级只允许 USER（`src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:19-45`、`src/main/resources/static/app.js:575-758`）。

1. 建会话只返回随机 UUID，不写 Redis；面板把 {userId, sessionId} 存 sessionStorage（`src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:33-34`、`src/main/resources/static/app.js:584-587`）。
2. chat：DTO 校验 sessionId 为 UUID、消息≤500；key 未配置 503；`agent:rate:{userId}` INCR，首次设 60 秒过期，超过 10 次 429。这些都发生在写 SSE 头之前（`src/main/java/com/winniethepooh/hotelsystembackend/controller/AgentController.java:28-31`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:62-70`）。
3. 开一个默认 60 秒的请求级截止时间（ThreadLocal），它同时约束模型调用、Redis 命令超时、取 JDBC 连接与语句超时（`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:72`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentDeadline.java:14-30`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentIoConfiguration.java:25-109`）。
4. 从 `agent:session:{userId}:{sessionId}` 读历史（最近 20 轮、工具项必须成对），拼上本轮用户消息，带「今天是…，时区 Asia/Shanghai」的提示词调用模型；文本增量以 delta 事件推给浏览器（`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:75-84`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/SessionStore.java:63-88`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:56-59`）。
5. 模型返回函数调用时逐个执行工具，本轮累计超过 8 次的调用不执行、补一条失败结果并结束；每个工具先校验 BaseContext 是 USER 且等于会话用户，再解析参数，然后在超时为剩余秒数的事务里执行；业务异常和意外异常都折成 `{ok:false,error}` 回给模型（`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:87-113`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:119-175`）。
6. propose_* 工具只读校验（报价、订单归属与状态、菜品上架与价格），然后把 PendingAction JSON 写入 `agent:action:{uuid}`，TTL 10 分钟，并把卡片以 card 事件推给浏览器；这一步不写任何订单（`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentTools.java:264-315`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:50-64`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:102`）。
7. 没有更多函数调用时把整轮条目经 Lua 原子 RPUSH 并刷新 30 分钟 TTL，最后发 done；超时 / 模型不可用发 error 后 done，本轮不保存（`src/main/java/com/winniethepooh/hotelsystembackend/agent/AgentService.java:115-127`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/SessionStore.java:36-57`）。
8. 住客点卡片「确认」：先按 actionId 查 booking_request，有记录直接按记录回答（SUCCESS 返回原订单号，CANCELLED 返回 404 失效）；没有记录再读 Redis 卡片，缺失时再查一次表，仍无则 404「确认卡片已失效」；卡片 userId 必须等于当前用户，否则 403（`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:66-75`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:155-163`）。
9. 事务内 INSERT booking_request(PROCESSING) → 执行动作 → 条件 UPDATE 为 SUCCESS 并写 order_id。动作分别是：BOOKING 调 `insertRoomOrderByUserService`（锁房间行 + 重叠查询 + 夜价）后比对报价；PAYMENT 先 `getRoomOrderByIdForUpdate` 锁订单、比对金额再条件支付；CANCEL 调 `cancelRoomOrderService` 条件取消；MEAL_ORDER 调 `insertMealOrderService` 后比对总价。金额不一致 409「价格已变化…」整体回滚（`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:76-84`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:118-153`）。
10. 唯一键冲突或悲观锁失败时在事务外重读记录返回同一结果；其他业务异常删除卡片、往会话写一条「[系统通知] 确认失败」NOTE 后抛出。成功后删卡片并写「[系统通知] 住客已确认…订单号」NOTE；删卡片和写 NOTE 失败只记 warn，不改变已提交结果（`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:85-95`、`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:189-197`）。
11. 「取消」不开事务：直接 INSERT CANCELLED，唯一键冲突时重读记录；之后删卡片，返回 CANCELLED（`src/main/java/com/winniethepooh/hotelsystembackend/agent/PendingActionService.java:98-116`）。
12. 浏览器端：done 之后卡片按钮才可用；确认成功显示「#订单号 · 消息」并保留确认按钮（再点得到同一结果）；404 或确认 400/409 把卡片标为已失效；倒计时到 0 也失效（`src/main/resources/static/app.js:685`、`src/main/resources/static/app.js:720-739`、`src/main/resources/static/app.js:747-752`）。

```mermaid
sequenceDiagram
    participant B as 助手面板
    participant C as AgentController
    participant S as AgentService
    participant M as LLM
    participant T as AgentTools
    participant P as PendingActionService
    participant R as Redis
    participant DB as MySQL
    B->>C: POST /agent/chat
    C->>S: chat(userId, sessionId, message)
    S->>R: INCR agent:rate:userId
    S->>R: LRANGE agent:session
    loop 至多8次工具调用
        S->>M: respond(提示词, 历史+本轮)
        M-->>S: 文本增量 / 函数调用
        S->>T: execute(工具, 参数)
        T->>DB: 只读查询（带截止时间的事务）
        T->>R: propose时 SET agent:action TTL10分钟
        S-->>B: SSE status / delta / card
    end
    S->>R: Lua RPUSH+PEXPIRE 整轮
    S-->>B: SSE done
    B->>C: POST /agent/actions/id/confirm
    C->>P: confirm(id, userId)
    P->>DB: 查 booking_request
    P->>R: GET agent:action
    P->>DB: 事务: INSERT PROCESSING → 原业务写 → UPDATE SUCCESS
    alt 唯一键冲突
        P->>DB: 事务外重读记录
    end
    P->>R: DEL agent:action, NOTE 写会话
    P-->>B: CONFIRMED + orderId
```
