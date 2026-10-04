# HotelSystemBackend

[![CI](https://github.com/Attacker687/HotelSystemBackend/actions/workflows/ci.yml/badge.svg)](https://github.com/Attacker687/HotelSystemBackend/actions/workflows/ci.yml)

酒店预订与管理系统，包含 Spring Boot 后端和同源的原生 HTML/CSS/JavaScript 页面。住客、经理、前台和餐厅使用同一套服务，完成预订、订单、房态、餐饮和经营统计流程；住客还可以通过基于大模型工具调用的预订助手查房、下单和支付。

## 项目亮点

- **防超卖**：每间房每晚一行 `room_inventory`，由 `(room_id, stay_date)` 唯一键裁决并发；日期升序插入避免死锁，任一晚冲突整单回滚并返回 409。见 [`OrderServiceImpl`](src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java)。
- **下单幂等**：`Idempotency-Key` 写入 `booking_request`，与订单同一事务提交；并发重试在唯一键上等待首单提交，返回同一订单号。请求内容做 SHA-256 指纹，同一请求号改内容返回 422。见 [`OrderRequestService`](src/main/java/com/winniethepooh/hotelsystembackend/service/OrderRequestService.java)。
- **Redis 缓存**：房间静态信息和按「房型 + 日期」切分的价格走旁路缓存；空值缓存防穿透，TTL 随机抖动防雪崩，事务提交后删键。下单计价与可售判断始终读库。见 [`HotCache`](src/main/java/com/winniethepooh/hotelsystembackend/service/HotCache.java)。
- **登录与权限**：JWT + Redis 可撤销会话；Filter 校验登录，`@RoleRequired` + AOP 校验角色；BCrypt 存储密码（旧 MD5 登录时自动迁移），登录失败限流。
- **AI 预订助手**：OpenAI Responses API 工具调用，SSE 流式输出。模型只能生成确认卡片，写操作必须由住客点击确认；确认复用下单事务与幂等表，并重新核对金额。真实模型 24 条评测 24/24 通过，越权、未确认写入、重复下单均为 0。见 [`agent/`](src/main/java/com/winniethepooh/hotelsystembackend/agent/)。
- **测试与 CI**：JUnit/Mockito 单元测试、Testcontainers（MySQL 8 + Redis 7）API 集成测试、Playwright 浏览器端到端测试；GitHub Actions 对每个 PR 和 master 推送运行全部三层测试（`mvn clean verify`）。

[快速演示](#快速演示windows) · [手动启动](#手动启动) · [配置](#配置与-profile) · [运行测试](#运行测试) · [系统设计](docs/architecture.md) · [并发与幂等设计](docs/booking-consistency.md) · [API 文档](docs/api-overview.md)

## 当前功能

| 角色 | 页面与主要操作 |
| --- | --- |
| 住客 `USER` | 注册与登录、按日期查询房间、预订、支付和取消、我的订单、点餐、取消新餐饮订单、评价 |
| 经理 `MANAGER` | 价格日历、经营分析、员工创建与启停、客房查询、客房订单总览 |
| 前台 `FRONT` | 线下开单、订单改期与换房、房态墙、确认清洁完成 |
| 餐厅 `RESTAURANT` | 实时餐饮订单、菜品明细、订单状态推进 |

后端还提供客房、菜品和分类维护，以及经理图片上传接口；接口权限和参数入口见 [API 与权限概览](docs/api-overview.md)。

关键业务规则：

- **预订冲突**：订单事务内按 `[入住日, 离店日)` 升序逐晚写 `room_inventory`，同房同日唯一键裁决并发；任一晚冲突返回 HTTP 409 并回滚整单，房态不参与可售判断。
- **下单重试**：`POST /order` 可带 8～64 位 `Idempotency-Key`；同身份、角色和内容在 7 天内重试返回同一订单，前台响应 data 仍为空。400/404 业务失败原样回放；修改内容须换新请求号。不带头保持原行为。
- **客房计价**：服务端按每晚价格计算金额，优先取房型价格日历，缺省价为单人间 199、双人间 299、套房 499 元；金额写入订单，并逐晚写入 `room_order_night`。
- **餐饮计价**：单价取数据库中的菜品价格，忽略客户端金额；不存在或已下架的菜品不能下单。
- **支付与取消**：通过条件更新约束订单状态和归属。已支付的客房订单在入住前取消时，支付状态置为“已退款”。当前支付、退款只记录本地业务状态，未连接真实支付渠道。
- **房态推进**：三个任务每分钟运行。住客未支付订单满 15 分钟自动取消，前台未收款单不参与超时取消；到店订单推进房态，退房后房间进入清洁中，再由前台确认空闲。
- **经营统计**：客房营收按订单间夜汇总，取消或删除的订单不计入；提供营收、入住率、平均房价、房型收入、楼层热力图与菜品 Top 10 等查询。

## 技术与环境

| 用途 | 技术或要求 |
| --- | --- |
| 应用 | Java 17+、Spring Boot 3.3.4、Spring MVC |
| 数据访问 | MyBatis 3.0.3、MySQL 8.0 |
| 登录态与权限 | Redis、JWT、Servlet Filter、角色注解与 AOP、BCrypt |
| 前端 | Spring Boot 静态资源，原生 HTML/CSS/JavaScript |
| 构建 | Maven；验证版本为 3.9.9 |
| 测试 | JUnit 5、Mockito、Testcontainers 1.20.3、Playwright 1.63.0 / Chromium |
| 图片上传 | 阿里云 OSS，仅上传功能需要配置 |

验证环境使用 JDK 21、Maven 3.9.9、Docker Desktop、MySQL 8.0、Redis 7-alpine、Node 24 和 npm 11。`pom.xml` 的 Java 编译目标为 17。

运行应用不需要 Node 或前端构建。**完整浏览器测试**另需 Node 20+、npm 和 Chromium；Docker 演示及集成测试需要 Docker Desktop 正常运行。

## 快速演示（Windows）

准备 JDK、Maven 和 Docker Desktop，然后在仓库根目录的 PowerShell 中执行。Windows 建议使用较短的目录路径，避免依赖和测试产物触发路径长度限制。

```powershell
$hotelMaven = 'mvn'
# Maven 未加入 PATH 时，改为 mvn.cmd 的实际路径，例如：
# $hotelMaven = 'C:\tools\apache-maven-3.9.9\bin\mvn.cmd'

& $hotelMaven -B -DskipTests package
powershell -NoProfile -File scripts/demo.ps1 -Port 8080
```

看到 `Started HotelSystemBackendApplication` 后打开 [http://localhost:8080/](http://localhost:8080/)。端口被占用时，将 `-Port 8080` 改为其他空闲端口，例如 `8081`。

演示脚本会创建独立的临时 MySQL 8 和 Redis 7 容器，自动生成数据库密码与 JWT 密钥，以 `dev` 启动并加载演示数据。按 `Ctrl+C` 停止后清理本次临时容器；再次启动得到新的演示数据。

| 登录入口 | 角色 | 账号 | 密码 |
| --- | --- | --- | --- |
| 员工登录 | 经理 | `admin` | `Admin@123` |
| 员工登录 | 前台 | `front` | `Front@123` |
| 员工登录 | 餐厅 | `kitchen` | `Kitchen@123` |
| 住客登录 | 住客 | `13900000000` | `User@1234` |

这些账号来自 [demo-data.sql](src/main/resources/db/demo-data.sql)，只用于本地演示。也可以在页面注册新的住客。演示数据与自动化测试的 Fixtures 数据分别维护。

建议按下面的顺序体验：

1. 用住客登录，预订明天的一间单人房，在“我的订单”支付后取消，查看“已取消 / 已退款”。
2. 在住客页面点餐，再用餐厅账号查看菜品明细，将新订单推进为“待完成”和“已完成”。
3. 用前台账号开单、查看房态墙，确认清洁完成。
4. 用经理账号查看经营分析，维护价格日历和员工状态。

`-DskipTests package` 只用于构建演示 jar，不能代替完整测试。

## 住客预订助手：演示与独立评测

需要 JDK 17+、Maven、运行中的 Docker、MySQL 8.0 / Redis 7；自动浏览器演示另需 Node 20+、npm 和 Chromium。首次在仓库根目录执行：

```powershell
npm ci
npx playwright install chromium
$hotelMaven = 'mvn'
# Maven 不在 PATH 时改用 mvn.cmd 的实际路径，例如：
# $hotelMaven = 'C:\tools\apache-maven-3.9.9\bin\mvn.cmd'
& $hotelMaven -B -DskipTests package
```

先跑 fake 演示。终端 A 启动现有 `demo.ps1` 管理的独立 dev 环境，数据库密码与 JWT 密钥由它随机生成，MySQL/Redis 使用随机宿主端口：

```powershell
powershell -NoProfile -File scripts/booking-agent.ps1 -Mode demo -Provider fake -Port 8080
```

确认 `target/agent-demo-source.json` 中的 `status` 为 `RUNNING` 后，在同一 checkout 的终端 B 执行（可用 `(Get-Content target/agent-demo-source.json -Raw | ConvertFrom-Json).status` 查询）：

```powershell
node scripts/booking-agent-demo.mjs --base-url http://127.0.0.1:8080 --provider fake
```

脚本用真实 Chromium 点击页面：按上海日期现算未来两晚双人间逐晚报价 → 订既有 302 的确认卡片（明确 2 人）→ 同卡两次确认同号、一张主单 → 支付后取消并核对已退款 → 拒绝他人手机号。每步核对隔离 MySQL 的订单、间夜与幂等记录；失败退出 1。结果、五张截图和去除临时 JWT 的 trace 在 `target/agent-demo-fake/`。启动包装器生成 `target/agent-demo-source.json`，关联本次 URL、容器、启动时间、Java 进程、jar 哈希与实际 SDK 条目；`--provider` 必须匹配这个运行来源，缺证或错标会失败。`--base-url` 的端口也必须与当前 `target/demo-info.json` 一致。

演示结束后，在终端 B 核对本次 Java 身份再停止它，让终端 A 的原脚本正常清理本次容器：

```powershell
$hotelRun = Get-Content target/agent-demo-source.json -Raw | ConvertFrom-Json
$hotelJava = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $hotelRun.javaPid)
if (-not $hotelJava -or $hotelJava.Name -ne 'java.exe' -or $hotelJava.ParentProcessId -ne $hotelRun.launcherPid) { throw '本次 Java 身份不匹配。' }
Stop-Process -Id $hotelJava.ProcessId
```

等待来源记录变为 `STOPPED`、本次容器移除后，再运行真实模型演示；每次启动均为新数据。终端 A 此时退出 1 表示 Java 被人工停止，不计五步失败。端口占用时，启动命令和浏览器命令一起改为同一个空闲端口。

在启动终端将 key 放入进程环境（不写源码、命令参数或报告），然后分别执行终端 A / B 的命令：

```powershell
$env:OPENAI_API_KEY = [Net.NetworkCredential]::new('', (Read-Host 'OPENAI_API_KEY' -AsSecureString)).Password
powershell -NoProfile -File scripts/booking-agent.ps1 -Mode demo -Provider openai -Port 8080
```

```powershell
node scripts/booking-agent-demo.mjs --base-url http://127.0.0.1:8080 --provider openai
```

真实结果写入 `target/agent-demo-openai/`。包装器只保存本次 `agent.llm user=... model=gpt-6-luna ... result=SUCCESS` 的安全字段，浏览器报告还会自动核对五次 chat 对应的实际 SDK 成功发送证据；缺证即失败。启动器只在子进程范围设置 provider/model，退出后恢复进程环境；复用 `scripts/demo.ps1`，不改演示房号或业务请求。trace 先导出到临时 ZIP，脱敏并扫描成功后才保留 `trace.zip`；失败或超时会删除临时和最终 ZIP、保存安全失败报告。

真实评测独立执行，停止演示后在已配置 key 的终端运行：

```powershell
powershell -NoProfile -File scripts/booking-agent.ps1 -Mode eval -Maven $hotelMaven
```

`Invoke-AgentEval` 实际执行 `mvn -B test-compile failsafe:integration-test@default failsafe:verify@default -Dit.test=AgentEval#evaluateRealModel`。Failsafe 3.2.5 的 [it.test / 方法选择文档](https://maven.apache.org/surefire-archives/surefire-3.2.5/maven-failsafe-plugin/examples/single-test.html) 对应这个命名 execution；不关闭 `failIfNoSpecifiedTests`。`AgentEval` 不匹配常规 Test/Tests/IT 命名，`mvn test` / `clean verify` 不发现它。

评测通过生产 HTTP sessions/chat/actions 和实际 `OpenAiLlmClient` / `com.openai:openai-java:4.73.0` 调用 `gpt-6-luna`，固定 Responses `store=false` / `include=reasoning.encrypted_content`。每例重置独立 Testcontainers MySQL/Redis，加载 `src/test/resources/agent/eval-cases.json` 的 24 条场景。工具、卡片、合理追问规则、服务端金额与订单状态自动判定；本人两类订单、他人固定入住人记录、未确认零写、首次主单增量及重复同号单独核对。成功的他人订单结果或卡片也硬计越权。确认异常仍读取后置数据；无法核对状态时安全项为 UNKNOWN / null、整体失败。没有 key 时入口明确报前提错误、退出 1；第一次模型不可用、超时、传输或解析失败后保留部分结果、标明剩余未尝试项，避免重复网络调用。模型预算仍为 60 秒；客户端从发送至完整 SSE/DONE 有独立 75 秒绝对截止，半行停滞也会关闭本次 body 并取消读取。

`target/agent-eval/report.json` 保留每次 chat 的回复和追问原文、首个**非空文本 delta** 的实际到达时间、逐条 3 秒达标情况及 P50/P90（nearest rank）；思考 status 不计首字，无文本和超时单列。五轮工具/NOTE 场景逐项核对模型 raw、后续 SDK 输入和 Redis 顺序、call/output 配对，加密项只报告实际数量与 SHA-256，不导出内容。追问原文供阅读复核，不新增人工测试用例。整体要求完成率 ≥90%、三项安全指标均 0、所有多轮成功回放；每次 chat 的 3 秒目标也决定总体结果，不因失败而删除样本。

无 key 可先执行维护入口的离线契约检查；它只验证数据、判定负例、实际 HTTP/DB 前提和 SDK 转换，不产生真实模型指标：

```powershell
powershell -NoProfile -File scripts/booking-agent.ps1 -Mode check -Maven $hotelMaven
```

离线维护检查包含隔离本地 HTTP 的 EOF、坏 JSON、半行停滞、慢速流与初始化故障，以及原生 ZIP 的合成 JWT 脱敏/失败删除。可单独执行故障探针以复核**预期退出 1**的报告路径（不发模型）：

```powershell
powershell -NoProfile -File scripts/booking-agent.ps1 -Mode check -FailureProbe stream -Maven $hotelMaven
# 预期退出 1；target/agent-eval-contract-stream/report.json 保留部分文本、TTFT 与 TIMEOUT 样本
powershell -NoProfile -File scripts/booking-agent.ps1 -Mode check -FailureProbe init -Maven $hotelMaven
# 预期退出 1；target/agent-eval-contract-init/report.json 保留失败初始化和后续未尝试项
```

这些产物注明 OFFLINE_CONTRACT，不计真实完成率、首字性能或模型回放结果。浏览器自检的内存故障替身只验证报告/自身资源收尾，实际五步仍按上面的独立 Chromium 命令执行。

常规 `test` / `e2e` 仍使用 fake；TC-038、TC-048、TC-055、TC-056 是确定性接入回归，不能作为真实效果或性能结果。

### 实测结果（2026-10-01）

| 项目 | 实际结果 |
| --- | --- |
| 真实配置 | OpenAI 官方 API，`gpt-6-luna` / SDK 4.73.0；Responses API、`store=false`、`reasoning.effort=low`；key 来自 `OPENAI_API_KEY` 用户环境变量 |
| 24 条真实评测 | 23:45（上海时间）`-Mode eval` 退出 0：**24/24（100%）**；越权 / 未确认写入 / 重复订单 **0 / 0 / 0**；29 次 chat 首字 **P50 1.14 秒、P90 1.79 秒**，超过 3 秒 0 次；请求失败与超时 0 |
| 真实多轮 / 加密项 | 多轮场景 1/1 通过；实际返回带 `encrypted_content` 的推理项 20 个，并按序完整回放 |
| 调优过程 | 默认推理强度下首字 P50 3.0 秒、14/29 次超过 3 秒；改为 `reasoning.effort=low` 并在提示词中要求「问齐日期/人数/房型」「调工具前先回一句」「逐晚日期写成 2026-10-02」后达标 |
| fake 浏览器五步（r2） | 2026-10-01 21:07 上海时间，5/5、退出 0，**6.841 秒**，本次来源 `BOUND_TO_RUNNING_LAUNCH`；查询/提议零写，重复确认同号且一单，支付后取消 status=2 / pay_status=2，越权请求不改数据；本次 Java/启动器/容器/浏览器清理后均为 0，环境恢复 |
| openai 浏览器五步 | 23:50（上海时间）**5/5、退出 0**，共 22.3 秒（各步 5.1 / 3.9 / 0.8 / 7.1 / 1.9 秒）；来源核验 `ACTUAL_SDK_SUCCESS_VERIFIED`；结束后 Java 与容器均已清理 |
| 离线维护检查 | Windows PowerShell 5.1 → 实际 Failsafe 只选 `AgentEval#offlineCheck`：1/0/0/0；24 数据、判定/回放/DB/HTTP 前提、11 个 Node 故障阶段与 3 个原生 ZIP 路径通过，退出 0；真实 trace 的 23 个文本条目无 JWT |
| 离线故障探针 | stream / init 两入口均按预期退出 1；保留 EOF/坏 JSON/部分文本/约 400ms 绝对截止 TIMEOUT 以及失败初始化后的 NOT_RUN，安全 UNKNOWN/null；不计模型效果 |
| 完整回归 | 提交 `771ae25`（助手功能完成时）的 `mvn -B clean verify` **640/0/0/0**（198 unit / 432 API / 10 browser），BUILD SUCCESS。此后加入的幂等、每日库存与缓存由 CI 在 master 上验证通过 |

完整验证会清空 `target/`，需要保留的演示和评测产物请先备份。

## 手动启动

已有 MySQL 和 Redis 时，可以直接连接自己的开发环境。

先在 MySQL 中创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS HotelSystem DEFAULT CHARACTER SET utf8mb4;
```

在 PowerShell 中配置连接和随机密钥：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:DB_URL = 'jdbc:mysql://localhost:3306/HotelSystem?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '替换为数据库密码'
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6379'
$env:REDIS_PASSWORD = ''

$hotelSecretBytes = New-Object byte[] 32
$hotelRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
$hotelRandom.GetBytes($hotelSecretBytes)
$hotelRandom.Dispose()
$env:JWT_SECRET = [BitConverter]::ToString($hotelSecretBytes).Replace('-', '')

$hotelMaven = 'mvn'
# Maven 未加入 PATH 时，使用实际的 mvn.cmd 路径。
& $hotelMaven -B -DskipTests package
java '-Duser.timezone=Asia/Shanghai' -jar target/HotelSystemBackend-0.0.1-SNAPSHOT.jar
```

`dev` 启动时会自动执行 [schema.sql](src/main/resources/db/schema.sql) 和 [demo-data.sql](src/main/resources/db/demo-data.sql)。脚本使用 `CREATE TABLE IF NOT EXISTS` 和固定主键的 `INSERT IGNORE`，可以重复执行；已有数据不会因此被重置。

JVM、JDBC 和 MySQL 时区应保持一致：项目约定 `Asia/Shanghai`，MySQL 时区为 `+08:00`。演示脚本和自动化测试已设置相应参数，手动启动时也需检查数据库时区。

### 使用默认配置部署

默认不激活任何 profile，不加载演示数据，也不自动初始化 MySQL。部署时先在目标数据库执行 `schema.sql`，配置连接和 `JWT_SECRET`，清除开发 profile 后启动：

```powershell
Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
java '-Duser.timezone=Asia/Shanghai' -jar target/HotelSystemBackend-0.0.1-SNAPSHOT.jar
```

`schema.sql` 是建表脚本，不是已有数据库的版本迁移或历史订单回填脚本；使用旧库时需要另外处理表结构变化和订单间夜数据。

尚无 `booking_request` 表的旧库先手工建表（默认 profile 不自动建表），再部署新 jar：

```sql
CREATE TABLE IF NOT EXISTS booking_request
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    user_id INT NOT NULL,
    requester_role TINYINT NOT NULL DEFAULT 0,
    action_type VARCHAR(16) NOT NULL,
    order_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    request_hash CHAR(64) NULL,
    fail_status INT NULL,
    fail_message VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_request_request_id (request_id),
    KEY idx_booking_request_user (user_id),
    KEY idx_booking_request_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='确认动作幂等记录';
```

已有 `booking_request` 表的旧库在部署前手工执行一次 [m1-booking-request.sql](src/main/resources/db/migration/m1-booking-request.sql)，增加四列及清理索引。ALTER 不可重复执行；新库执行 `schema.sql` 或上面的完整建表 DDL 后不再执行 ALTER。M1 回滚可直接回滚 jar，新列的默认值及可空值兼容旧代码；ORDER 记录可保留或手工清理。

### 每日库存建表与回填（M2）

S03 提供 `room_inventory` 建表和手工回填工具，S04 已接入库存业务读写。新库的 `schema.sql` 已含库存表，旧库使用 [m2-room-inventory.sql](src/main/resources/db/migration/m2-room-inventory.sql)。两份 DDL 字段、键一致：同房同日唯一，按 `order_id` 索引，无外键。默认 profile 不自动执行迁移。

旧库必须在同一个停写窗口完成以下步骤，再接续启动含 S04 库存维护的当前版本（包括 M3）：

1. 停止旧版本及其他订单写入，确认 JVM/JDBC 为 `Asia/Shanghai`、MySQL 会话为 `+08:00`。
2. 在目标库手工执行 `m2-room-inventory.sql`，确认 `room_inventory` 为空。
3. 打开 [m2-room-inventory-backfill.sql](src/main/resources/db/migration/m2-room-inventory-backfill.sql)，**只执行步骤 1，人工核对冲突清单**。有任何输出就停止，按业务核实并处理历史冲突，再重跑步骤 1，直到无输出；不要直接执行整份文件。
4. 单独执行步骤 2 的一条 `INSERT…WITH RECURSIVE`。只填进行中、未删、有房间且离店时间在未来的订单，夜集合为 `[入住日, 离店日)`，已入住订单包含过去晚；唯一键冲突会使整条语句失败，空库存仍为空。
5. 执行[核对 SQL I1/I2](docs/booking-consistency.md#历史订单回填与核对)，两条均无输出才可继续。
6. 保持停写，部署并启动当前版本；冒烟下单、同房同晚重复下单返回 409、取消后重新预订成功。不能在回填后恢复旧版写入，再沿用先前的库存结果。

回填脚本**只执行一次**；需要重做时，在停写窗口先 `TRUNCATE room_inventory`，再从步骤 3 开始。dev 演示数据没有订单，无需历史回填。

M2 回滚可回滚 jar 并保留库存表，旧版本仍按订单区间判断冲突。回滚期间旧版本不会维护库存；**再次上线库存版本前必须重新停写、清空 `room_inventory`、重新回填并核对 I1/I2**。旧库的 M1 ALTER 先按上节执行一次，再完成本节库存迁移；M3 缓存无需额外 DDL。

### 助手配置与回退

配置 key 后启动前确认 `HOTEL_AGENT_PROVIDER=openai`、`HOTEL_AGENT_MODEL=gpt-6-luna`。回退可以删除 key 并重启（只有助手 chat 返回 503，普通功能继续可用），或切换 fake 后重启：

```powershell
Remove-Item Env:OPENAI_API_KEY -ErrorAction SilentlyContinue
$env:HOTEL_AGENT_PROVIDER = 'openai'
java '-Duser.timezone=Asia/Shanghai' -jar target/HotelSystemBackend-0.0.1-SNAPSHOT.jar
# fake 回退：停止上面的进程，修改 provider 后重启
$env:HOTEL_AGENT_PROVIDER = 'fake'
java '-Duser.timezone=Asia/Shanghai' -jar target/HotelSystemBackend-0.0.1-SNAPSHOT.jar
```

沿用上文数据库、Redis 与 JWT 连接配置，幂等表保留。provider/key 的环境变化只影响新启动的进程。

## 配置与 profile

应用配置见 [application.yml](src/main/resources/application.yml)。

| 环境变量 | 默认或要求 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `DB_URL` | 本机 MySQL 的 `HotelSystem` 库 | JDBC URL，显式配置 `serverTimezone=Asia/Shanghai` |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / 空 | 数据库账号与密码 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 连接 |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `HOTEL_CACHE_ENABLED` | `true` | 房间静态详情、列表价格和价格日历缓存；设为 `false` 直接读库，写入口仍删除缓存 |
| `JWT_SECRET` | 必填，无默认值 | 至少 32 字节的随机签名密钥；缺失或过短时拒绝启动 |
| `HOTEL_AGENT_PROVIDER` | `openai` | `test` / `e2e` 固定 fake；演示可用 fake 回退 |
| `HOTEL_AGENT_MODEL` | `gpt-6-luna` | 本次批准的模型；独立评测固定此模型 |
| `OPENAI_API_KEY` | openai 模式需要 | 只从进程环境传入；未配时助手 chat 503 |
| `SPRING_PROFILES_ACTIVE` | 不激活任何 profile | 开发设为 `dev` |
| `HOTEL_SCHEDULER_ENABLED` | `true` | 设为 `false` 关闭定时任务的 cron 触发 |
| `CORS_ALLOWED_ORIGINS` | 空 | 允许跨域的来源列表，逗号分隔；同源静态页无需配置 |
| `ALIYUN_OSS_ENDPOINT` | 成都地域 OSS 地址 | 上传服务地址 |
| `ALIYUN_ACCESS_KEY_ID` / `ALIYUN_SECRET_KEY` | 空 | OSS 上传凭证 |
| `ALIYUN_BUCKET_NAME` | `hotelsystem` | OSS bucket |

| Profile | 用途 | SQL 初始化 | 定时任务 |
| --- | --- | --- | --- |
| 默认 | 部署配置 | 不自动初始化 | 开启 |
| `dev` | 本地演示、开发和 Swagger | 自动建表并加载演示数据 | 开启 |
| `test` | API / 数据库集成测试 | 测试基类通过容器建库与恢复 Fixtures | 关闭 cron，由用例调用任务 |
| `e2e` | 浏览器端到端测试 | JUnit 管理独立容器及数据 | 使用真实每分钟 cron |

`test`、`e2e` 的配置位于测试资源目录，由测试运行器管理。`dev` 会输出 MyBatis SQL 参数日志；默认配置不输出这些参数。

## 登录与接口约定

前端与 REST API 同源，页面自动携带登录态。直接调用受保护接口时，在请求头传入：

```http
token: <登录接口返回的 JWT>
```

- 登录态由 JWT 和 Redis 共同校验，住客与员工 token 有效期均为 3 小时。
- 员工退出、停用或删除，住客改密码，以及同一账号再次登录会撤销相关旧 token。
- 员工退出接口为 `POST /staff/logout`。住客页面退出清除当前浏览器会话，当前没有住客服务端退出接口。
- 类和方法上的 `@RoleRequired` 都会校验角色；方法注解优先。每次请求结束会清理线程上下文。
- 住客注册、住客/员工登录及指定静态资源允许匿名访问；`/staff/register` 需要经理身份。
- 默认登录失败阈值为同一账号或 IP 在 15 分钟内失败 5 次，随后限制 15 分钟。

业务成功返回 `code=0`，失败返回 `code=1` 与具体提示；参数、认证、权限、不存在和冲突分别使用 HTTP 400、401、403、404、409。接口清单、响应结构和状态编码见 [API 与权限概览](docs/api-overview.md)。

### Swagger

`dev` 环境可匿名访问 [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) 和 `/v3/api-docs`；其他 profile 需要有效 token。Swagger 的页面、资源和 OpenAPI 在 TC-136 中验证。

### 图片上传

`POST /upload/image` 仅允许经理调用。允许扩展名与文件头匹配的 PNG、JPEG、GIF、WebP 图片，单文件最大 5 MB；超限返回 413。使用上传功能前配置 OSS；其他功能启动不依赖 OSS 凭证。

## 运行测试

单元测试文件为 `*Test` / `*Tests`，由 Surefire 执行；集成测试文件为 `*IT`，由 Failsafe 执行。API 与浏览器测试使用独立的 MySQL 8.0、Redis 7-alpine 容器。

先准备浏览器测试依赖：

```bash
npm ci
npx playwright install chromium
```

在 Docker Desktop 运行的情况下执行完整验证：

```bash
mvn -B clean verify
```

按层定位问题时可以分别运行：

```bash
mvn test                              # 单元测试，不需要 Docker
mvn -B verify -DskipBrowserTests=true   # 单元 + API，不跑浏览器
mvn -B verify -DskipApiTests=true       # 单元 + 浏览器，不跑 API 集成
```

这些选择性命令不等同于完整验收。浏览器测试由 `BrowserE2EIT` 启动应用、准备 Fixtures 并向 Playwright 注入地址和测试桥参数，完整执行应通过 Maven 入口。

测试 Docker API 默认固定为 `1.44`。如果 Docker Engine 不支持该版本，可先用 `docker version` 确认服务端支持的 API，再通过 `-Ddocker.api.version=兼容版本` 覆盖，例如兼容 API 1.43 的环境：

```bash
mvn -B clean verify -Ddocker.api.version=1.43
```

测试方法名包含用例编号，如 `tc012_...`；测试数据由 Fixtures 别名取得，如 `base.user("A")`、`base.room("R1")`。浏览器用例 TC-132 至 TC-137 覆盖预订与入住退房、前台开单、餐饮、员工停用、dev 启动与 Swagger、取消退款及再次预订。涉及定时任务的流程等待真实 cron，TC-133 对前台未收款订单观察不少于 70 秒。

### 测试结果与证据

以下是四角色前端完成时的**历史**完整测试记录（2026-09-30，源码基线 `79e476e`）；之后的回归结果见[实测结果](#实测结果2026-10-01)：

| 执行层 | 次数 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| Surefire 单元 | 27 | 0 | 0 | 0 |
| Failsafe API / 数据库集成 | 252 | 0 | 0 | 0 |
| Failsafe 浏览器端到端 | 6 | 0 | 0 | 0 |
| 合计 | **285** | **0** | **0** | **0** |

285 包含参数化和补充回归，对应定稿方案的 **137 条用例**。六份原生 Playwright 报告与 Java 浏览器测试包装器对应，不能再次加总计数。

| 产物 | 位置 |
| --- | --- |
| 本轮单元报告 | `target/surefire-reports/` |
| 本轮 API 集成报告 | `target/failsafe-reports/` |
| 本轮浏览器包装器报告 | `target/failsafe-e2e-reports/` |
| Playwright XML、日志、截图和 trace | `target/e2e/` |
| 已留存的末轮统计与证据 | [docs/nova/review-fixes/evidence/](docs/nova/review-fixes/evidence/) |

查看浏览器 trace：`npx playwright show-trace 路径/trace.zip`。

- [测试报告](docs/nova/review-fixes/test-report.md)：范围、结论、执行结果与遗留。
- [逐用例结果](docs/nova/review-fixes/results.json) / [缺陷清单](docs/nova/review-fixes/defects.json)。
- [测试方案](docs/nova/review-fixes/test-plan.md) / [测试方案 JSON](docs/nova/review-fixes/testplan.json)。
- [飞书测试报告](https://mcn7m001m9qm.feishu.cn/wiki/KykSw5vXIi06CNkcDQVcIOVoneh)。

## 已知遗留与演进

2026-09-30 的测试报告结论为“有条件通过”：全部用例通过，但以下 4 项低严重度问题尚未关闭。

| 编号 | 遗留 |
| --- | --- |
| F4 | 部分 DTO 的必填值和数据库长度边界尚未完整补齐 |
| F5 | 多个字段同时非法时首条提示的顺序不固定 |
| F6 | 错误角色携带非法请求体时，参数校验可能先返回 400 |
| F7 | 定稿方案的 Redis 版本文字仍与实际测试环境有差异；当前实际使用 Redis 7-alpine |

此外，已有数据库迁移与旧订单间夜回填尚未验收；退款与改期差价未对接支付渠道。

当前并发预订使用**每日库存唯一键与订单事务**；同房改期只更新夜集合差集并保留共同晚的库存 ID，取消、超时取消和软删除同事务释放全部占用，提前结束只释放今天及以后的晚，正常退房保留历史夜，支付不动库存。助手报价与搜房只读库存且不加锁。`POST /order` 可带 `Idempotency-Key` 安全重试，助手确认通过同一表的全局唯一键幂等及确认/取消互斥。请求号记录保留 7 天，每日 03:30 清理（受 `HOTEL_SCHEDULER_ENABLED` 控制）；窗口外再次使用请求号会重新执行。定时任务失败自动重试仍是演进项，见 [并发预订与幂等设计](docs/booking-consistency.md)。

M3 已接入房间详情、列表价格和价格日历缓存：`room:detail:{id}` 只存房号、房型、楼层、容量、描述、图片；命中后仍用一条 SQL 实时读房态并检查未删。列表保留过滤、分页和计数，按本页最多三种房型一次 MGET 读取 `price:{type}:{date}`，未设价仍用 199/299/499 默认价。`GET /business/calendar` 按日期一次 MGET 相同价格键，全命中零 SQL；任一天缺失则用原完整区间一次 SQL，回填缺键，按日期返回原 `PriceCalendar` 字段及未设价的 null 项。有值 TTL 为 1800～2400 秒随机，空值 `NULL` 为 300 秒。房间静态修改/删除和批量改价在提交后删键，无外层事务时在 SQL 自动提交后立即删，批量价格键用一次 DEL。

Redis 命令超时为 1 秒，也作用于登录态读取。缓存读取或 JSON 解码故障回退读库，缓存写入/删键故障忽略并记录 WARN，日志只含前缀和异常类；数据库业务异常保留原语义。订单、改期、报价、助手计价与库存可售全部读库，房态和库存不进入缓存。直接改库须手工删键；读旧值与删键的竞争或删键失败可能让展示旧值留到 TTL，订单金额始终以数据库为准。缓存开关关闭时不读写缓存，写入口仍删除相关键。

已接受的助手边界：同一会话多标签页并发消息无会话锁，可能打乱历史，可新建对话；每分钟限流的 INCR 与首次 EXPIRE 非原子。Redis 必须使用 7，并让应用、Redis 与 MySQL 时钟同步（例如 NTP），保持上海 JVM/JDBC 和 MySQL `+08:00`；会话 Lua 用 Redis TIME 判断应用传入的截止时间。服务端已提交但响应在网络中丢失时，客户端提交结果为 UNKNOWN（结果未知），不能视为未执行；同卡重试确认读取同一幂等结果。NOTE 写入是尽力而为，最终订单状态以数据库为准。

## 目录与文档

```text
src/main/java/com/winniethepooh/hotelsystembackend/
  controller/                 REST 接口
  service/                    业务事务与定时任务
  mapper/                     MyBatis 接口
  filter/、aspect/、config/    登录、权限与配置
  dto/、entity/、vo/           请求、实体与响应模型
src/main/resources/
  static/                     原生前端页面
  mapper/                     MyBatis XML
  db/                         建表与 dev 演示数据
src/test/java/                单元、集成与浏览器测试入口
src/test/e2e/                 Playwright 流程
src/test/resources/           test / e2e 配置
scripts/demo.ps1              Windows 隔离演示
docs/                         API、设计、代码地图与测试报告
```

- [系统设计](docs/architecture.md)：模块边界、数据模型与业务时序。
- [API 与权限概览](docs/api-overview.md)：接口、角色与状态编码。
- [代码地图](docs/codemap/hotelsystembackend/README.md)：入口、数据、流程和业务规则索引。
- [并发预订与幂等设计](docs/booking-consistency.md)：当前库存、幂等、缓存边界与手工上线步骤。
