# HotelSystemBackend

<div align="center">
  <p><strong>面向酒店预订、前台履约、餐饮服务与经营分析的一体化后端</strong></p>
  <p>
    <img alt="Java 17" src="https://img.shields.io/badge/Java-17-E76F00?logo=openjdk&logoColor=white">
    <img alt="Spring Boot 3.3.4" src="https://img.shields.io/badge/Spring%20Boot-3.3.4-6DB33F?logo=springboot&logoColor=white">
    <img alt="MyBatis 3" src="https://img.shields.io/badge/MyBatis-3-C00000">
    <img alt="MySQL" src="https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql&logoColor=white">
    <img alt="Redis" src="https://img.shields.io/badge/Redis-Session-DC382D?logo=redis&logoColor=white">
    <img alt="V2 Design Ready" src="https://img.shields.io/badge/V2%20Design-Concurrency%20%26%20Idempotency-6F42C1">
  </p>
  <p>
    <a href="#项目定位">项目定位</a> ·
    <a href="#核心能力">核心能力</a> ·
    <a href="#系统架构">系统架构</a> ·
    <a href="#快速开始">快速开始</a> ·
    <a href="#设计文档">设计文档</a>
  </p>
  <p><strong>4 类业务角色 · 7 个领域服务 · 45 个 REST API · 3 个自动调度任务</strong></p>
</div>

## 项目定位

HotelSystemBackend 是一个以酒店真实业务链路为背景的 Spring Boot 后端项目。系统围绕“用户预订 - 支付 - 入住 - 退房”主流程，延伸出员工权限、房态管理、动态价格、餐饮订单和经营看板等模块。

项目重点不是接口数量，而是把分散的 CRUD 组织成一条可解释的业务闭环：

- 用户能够查询房态、提交订单、支付、取消和评价；
- 前台能够管理订单、办理入住并维护实时房态；
- 餐厅能够处理送餐订单及状态流转；
- 管理员能够管理员工、房间、价格日历并查看经营指标；
- 定时任务自动处理未支付订单、入住生效和退房释放。

> 当前公开分支保留了核心业务实现。针对高并发预订、请求幂等和缓存一致性的生产化方案，单独记录在[《并发预订与幂等设计》](docs/booking-consistency.md)中，便于区分“现有实现”和“演进设计”。

## 核心能力

| 能力 | 实现方式 | 业务价值 |
| --- | --- | --- |
| 多角色认证授权 | JWT 表达身份，Redis 保存有效登录态，过滤器统一鉴权，AOP 注解校验角色 | 同时支持用户、管理员、前台和餐厅四类角色 |
| 房间全生命周期 | 房态、客房订单和定时任务协同推进预订、入住、退房及超时取消 | 避免订单状态与实际房态长期脱节 |
| 日期价格日历 | 按房型和日期维护价格；下单时逐日取价，缺省时回退至房型基础价 | 支持周末、节假日和旺季差异化定价 |
| 服务端金额计算 | 根据入住区间逐日累计房价，餐饮订单校验主单与明细金额 | 降低客户端篡改金额带来的风险 |
| 事务一致性 | 使用 Spring 事务管理房态变更、员工写入、价格批量更新和餐饮主从单写入 | 异常时统一回滚，避免只写入部分数据 |
| 经营分析 | 聚合营收、入住率、平均房价、房型收入、楼层热力图和菜品 Top 10 | 将业务数据转化为运营决策指标 |
| 统一异常处理 | 业务异常集中映射为统一响应结构 | 降低 Controller 重复判断，便于前后端联调 |
| 并发防超卖（V2 设计） | 按“客房 + 入住日期”维护每日库存，唯一键约束冲突，事务内逐日条件更新 | 保证跨日订单全部成功或全部回滚 |
| 下单幂等（V2 设计） | `requestId` 唯一索引配合幂等状态记录，重复请求返回原订单 | 防止重复点击、网络重试产生重复订单 |

### V2 并发预订方案

针对基础订单模型在高并发场景下可能出现的区间重叠和重复下单问题，项目已经完成 V2 技术方案设计，代码实现计划在后续版本合并：

- **每日库存建模**：以 `(room_id, stay_date)` 唯一键定义最小库存冲突单元；
- **事务内条件更新**：按固定日期顺序逐日执行 `available = 1 -> 0`，任一日期失败则整体回滚；
- **请求幂等**：使用 `requestId` 唯一索引选出唯一执行者，并记录 `PROCESSING / SUCCESS / FAILED` 状态；
- **服务端计价**：库存抢占成功后按价格日历复算总金额，再写入订单主表和明细；
- **缓存边界**：Cache Aside 只用于客房详情和可售展示，数据库更新后删除缓存，并通过随机 TTL 分散集中失效风险；最终库存仍以数据库更新结果为准；
- **索引验证**：围绕库存与订单查询设计联合索引，并使用 `EXPLAIN` 检查访问类型、命中索引与预估扫描行数；
- **并发验收**：设计 50 个请求同时抢占单份库存的测试，验收目标为 1 个成功订单、49 个库存冲突、0 个重复订单和 0 个负库存。

```mermaid
flowchart LR
    Request["requestId + 入住区间"] --> Idempotency{"抢占幂等键"}
    Idempotency -->|重复请求| Existing["返回原订单 / 当前状态"]
    Idempotency -->|首次请求| Inventory["按日期顺序条件更新每日库存"]
    Inventory --> Check{"全部日期成功?"}
    Check -->|否| Rollback["事务回滚并返回库存冲突"]
    Check -->|是| Price["服务端复算总金额"]
    Price --> Order["创建订单并绑定 requestId"]
    Order --> Commit["提交事务"]
```

完整的数据模型、SQL、死锁规避、缓存策略和测试方法见[《并发预订与幂等设计》](docs/booking-consistency.md)。

## 业务全景

```mermaid
flowchart LR
    Guest["用户"] --> Search["查询房间与日期价格"]
    Search --> Booking["创建客房订单"]
    Booking --> Payment{"限时支付"}
    Payment -->|已支付| CheckIn["到店 / 入住生效"]
    Payment -->|超时| Cancel["订单自动取消"]
    CheckIn --> Stay["住宿与餐饮服务"]
    Stay --> CheckOut["退房并释放房间"]
    CheckOut --> Review["订单评价"]

    Manager["管理员"] --> Price["价格日历"]
    Manager --> Dashboard["经营看板"]
    Front["前台"] --> Booking
    Front --> CheckIn
    Restaurant["餐厅"] --> Stay
```

## 系统架构

```mermaid
flowchart TB
    Client["Web / Mobile Client"]
    Nginx["Nginx / API Gateway"]

    subgraph Application["Spring Boot Application"]
        Filter["LoginFilter\nJWT + Redis Session"]
        AOP["RoleCheckAspect\n角色级权限"]
        Controller["REST Controller"]
        Service["Domain Service\n事务与业务编排"]
        Scheduler["Scheduled Tasks\n订单与房态推进"]
        Mapper["MyBatis Mapper"]
        Handler["Global Exception Handler"]
    end

    MySQL[("MySQL\n业务数据")]
    Redis[("Redis\n登录态")]
    OSS[("Aliyun OSS\n图片资源")]

    Client --> Nginx --> Filter
    Filter <--> Redis
    Filter --> AOP --> Controller --> Service --> Mapper --> MySQL
    Scheduler --> Mapper
    Controller -.异常.-> Handler
    Service --> OSS
```

更完整的模块边界、数据关系和关键时序见[《系统设计》](docs/architecture.md)。

## 角色与权限

| 角色 | 主要权限 |
| --- | --- |
| 用户 `USER` | 注册登录、查询房间、创建/支付/取消订单、餐饮下单、查看历史和评价 |
| 管理员 `MANAGER` | 员工管理、客房管理、价格日历、订单管理、经营分析 |
| 前台 `FRONT` | 线下开单、订单调整、房态墙和入住退房处理 |
| 餐厅 `RESTAURANT` | 查看实时餐饮订单、更新制作及配送状态 |

权限校验分为两层：

1. `LoginFilter` 校验请求头中的 token，并从 Redis 确认登录态是否仍然有效；
2. `@RoleRequired` 与 `RoleCheckAspect` 对具体接口执行角色级授权。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 核心框架 | Java 17、Spring Boot 3.3.4、Spring MVC |
| 数据访问 | MyBatis 3、MySQL |
| 状态与鉴权 | Redis、JWT、Servlet Filter、Spring AOP |
| 工程能力 | Maven、Lombok、Spring Transaction、Scheduled Tasks |
| 接口文档 | SpringDoc OpenAPI / Swagger UI |
| 对象存储 | Aliyun OSS |

## 模块划分

```text
src/main/java/com/winniethepooh/hotelsystembackend
├── annotation       # 角色权限注解
├── aspect           # AOP 权限校验
├── config           # Web 与跨域配置
├── constant         # 角色、房态、订单状态常量
├── context          # 当前请求上下文
├── controller       # REST API 入口
├── dto              # 写请求数据模型
├── entity           # 领域实体
├── exception        # 业务异常与统一异常处理
├── filter           # JWT + Redis 登录态校验
├── mapper           # MyBatis 数据访问接口
├── service          # 业务编排、事务与定时任务
├── utils            # JWT、日期与 OSS 工具
└── vo               # 面向页面的响应模型
```

### 业务模块

| 模块 | 代表能力 |
| --- | --- |
| 用户与员工 | 注册登录、资料维护、员工启停、角色授权、单端登录态替换 |
| 客房 | 条件分页、房态墙、房间维护、按日期动态取价 |
| 客房订单 | 用户/前台双入口、服务端计价、支付、取消、评价、超时关闭 |
| 餐饮 | 分类与菜品维护、主从订单写入、实时订单及状态流转 |
| 经营分析 | 营收同比、月度趋势、入住率、平均房价、楼层热力图、菜品排行 |
| 调度任务 | 未支付订单关闭、到店订单生效、离店房间释放 |

## 关键设计

### 1. 登录态为什么同时使用 JWT 和 Redis

JWT 负责携带用户 ID 与角色信息，减少每次请求的数据库查询；Redis 负责保存“当前仍然有效”的 token，使退出登录、账号停用和重复登录挤下线能够立即生效。相比完全无状态的 JWT，这种组合更适合后台管理系统。

### 2. 为什么采用价格日历

客房价格不是房间的静态属性。系统以“房型 + 日期”查询当天价格，找不到配置时回退到房型默认价；下单时遍历入住日期并由服务端计算总金额，从而支持节假日和旺季定价，并减少客户端金额不可信的问题。

### 3. 如何推进订单与房态

订单状态和房间状态分别建模：订单描述交易生命周期，房态描述酒店当前可运营状态。定时任务负责将时间条件转换为状态变化，包括未支付订单超时关闭、已支付订单到期生效以及退房后释放房间。

### 4. 餐饮主从订单如何保证一致

餐饮订单先写入主单并回填主键，再逐项写入明细，最后校验汇总金额。整个过程位于同一事务中；明细为空、主键回填失败或金额不一致都会抛出异常并整体回滚。

## API 概览

| 路径前缀 | 模块 | 典型接口 |
| --- | --- | --- |
| `/user` | 用户 | 注册、登录、资料查询与修改 |
| `/staff` | 员工 | 登录、退出、注册、启停与列表 |
| `/rooms` | 客房 | 条件查询、维护、房态更新与房态墙 |
| `/order` | 订单 | 查询、创建、支付、取消、评价与餐饮下单 |
| `/food` | 菜品 | 分类与菜品管理 |
| `/restaurant` | 餐厅 | 实时订单与状态更新 |
| `/business` | 经营分析 | 营收、入住率、趋势、热力图、排行与价格日历 |
| `/upload` | 文件 | 图片上传至对象存储 |

接口权限和状态编码详见[《API 与权限概览》](docs/api-overview.md)。

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8+
- Redis 6+

### 启动步骤

```bash
git clone https://github.com/Attacker687/HotelSystemBackend.git
cd HotelSystemBackend
```

默认配置连接本机的 `HotelSystem` 数据库与 Redis。也可以通过环境变量覆盖连接信息：

```bash
export DB_URL='jdbc:mysql://localhost:3306/HotelSystem?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
export DB_USERNAME='root'
export DB_PASSWORD='your-password'
export REDIS_HOST='localhost'
export REDIS_PORT='6379'
```

PowerShell：

```powershell
$env:DB_URL='jdbc:mysql://localhost:3306/HotelSystem?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='your-password'
$env:REDIS_HOST='localhost'
$env:REDIS_PORT='6379'
```

只有使用图片上传功能时才需要设置 `ALIYUN_ACCESS_KEY_ID`、`ALIYUN_SECRET_KEY` 和 `ALIYUN_BUCKET_NAME`。敏感信息不要提交到公开仓库。

```bash
mvn spring-boot:run
```

服务启动后可通过 Swagger UI 查看接口定义。实际路径和端口以当前激活的 Spring Profile 为准。

## 设计文档

- [系统设计：模块边界、关键时序与数据模型](docs/architecture.md)
- [并发预订与幂等：从业务版到生产化订单链路](docs/booking-consistency.md)
- [API 与权限概览：角色矩阵、接口分组与状态编码](docs/api-overview.md)

## 项目边界与演进方向

该项目以业务建模和后端工程实践为主要目标，不直接等同于生产级酒店 PMS。生产化仍需进一步完善：

- [x] 完成“客房 + 入住日期”每日库存、条件更新与事务回滚方案设计；
- [x] 完成 `requestId` 唯一约束、幂等状态机与重复请求返回方案设计；
- [x] 完成 Cache Aside 边界与 50 并发请求验收标准设计；
- [ ] 将并发库存与幂等方案合并至主分支代码；
- 为热点查询增加 Cache Aside，并设计更新后的失效策略；
- 补齐集成测试、并发测试、可观测性与数据库版本迁移；
- 使用 Docker Compose 固化 MySQL、Redis 与应用运行环境。

对应的约束、SQL 思路和验收方法已整理在[并发预订设计文档](docs/booking-consistency.md)中。

## License

本仓库用于学习与技术交流。若需要在其他项目中使用，请先与仓库作者确认授权方式。
