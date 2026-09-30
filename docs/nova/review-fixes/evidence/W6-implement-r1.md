# W6 实现证据（第 1 轮）

- 工作区：`C:/t/hsb/.nova/worktrees/W6`；分支：`nova/review-fixes-W6`；基线：`e3f88c556b437fccc7e4ba394a403a2d8bd68392`。基线已有 W0/W1/W4/W5/W2/W3，主会话原完整验证 271/271，通过前的基线失败清单为空。
- 范围：原生 Spring Boot 静态前端、六条 Playwright 主用例 TC-132～137、独立演示启动脚本、M1～M7 文档一致性核对。按用户已选轻量流程执行，跳过代码评审，没有另派评审 agent。
- 后续明确纳入本批的既有修复补齐：S14 员工退出仍是 GET 的遗留；S8/W1 单项密码更新可能明文落库的漏项。两项都先保留有效红灯，再修共享生产入口。
- 最终完整命令 `mvn -B clean verify` 实际退出 **0**，用时 **06:35**：单元 **27/27**、API 集成 **252/252**、独立 browser-e2e **6/6**，共 **285/285**；失败、错误、跳过均为 **0**。与基线相比新增 4 次静态资产参数化执行、1 次员工退出执行、3 次密码配对/资料更新执行、6 次浏览器执行，共 14 次。
- 源码提交 `aeee6c68a8ab57df9cc833876fcd1e2b103d8792`；之后仅按主会话事实核对要求补文档提交 `2ece1a816744c40aea50100086f395525b6fa246`。后者仅 `docs/architecture.md` 的两处事实，未改变生产或测试源码，不另跑代码测试。交回时 worktree 干净；未 fetch/pull/push，未自行合入。

本文件中的路径以 W6 根目录为基准。表格为简洁省略固定前缀：生产Java文件省略 `src/main/java/com/winniethepooh/hotelsystembackend/`，测试Java文件省略 `src/test/java/com/winniethepooh/hotelsystembackend/`，`static/*`、`mapper/*.xml`、`application-*.yml` 省略 `src/main/resources/`（application-e2e.yml例外，位于src/test/resources），`hotel.spec.js` 省略 `src/test/e2e/`；其余路径直接相对 W6 根。日志与留存结果相对本文件目录 `C:/t/hsb/.nova/review-fixes/build/W6/`。实现方与主会话的验证分开记录；下文另收录主会话已完成的独立验证及人工演示，集成末轮结果由主会话补记。

## 验收对照

六条主用例由 `src/test/java/com/winniethepooh/hotelsystembackend/BrowserE2EIT.java` 启动对应的 `src/test/e2e/hotel.spec.js`。登录、注册、下单、支付、取消、评价、价格维护、员工维护、清洁完成都经浏览器页面实际操作。数据库仅用于既定夹具及结果核对。

| 主用例 | JUnit 方法 / Playwright 入口 | 实际核对与终态 | 最终结果 |
|---|---|---|---|
| TC-132 住客预订、支付、按晚营收、退房 | `tc132_guestRegistrationBookingPaymentRevenueAndCheckout`；`hotel.spec.js:79` | 经理将 R1 房型 D 的价格设为 350；E 经界面注册、登录并显示姓名；D 至 D+3 为 748.00，支付后 pay_status=1；营收趋势为 350/199/199。夹具推进时间后等待原 cron，房态依次占用、清洁中，订单显示已完成；终态 status=1、pay_status=1、金额748、R1.status=2。 | 浏览器 1/1，失败/错误/跳过 0；73.860s |
| TC-133 未收款前台单与清洁完成 | `tc133_unpaidFrontOrderSurvivesTimeoutAndRoomIsCleaned`；`hotel.spec.js:123` | P0、String 房号 R2、D 至 D+2、未收款；金额398、user_id=null、pay_status=0。created_at 使用数据库 NOW()-16分钟后等满70秒，每5秒查库，保持 status=0（本轮末次为70021ms）；真实入住 cron 后占用，真实退房 cron 后清洁中、订单已完成；前台点清洁完成，终态 R2.status=0、订单 status=1、pay_status=0、金额398。 | 浏览器 1/1，失败/错误/跳过 0；177.330s |
| TC-134 餐饮取消、推进、评价与 Top10 | `tc134_mealCancellationCompletionReviewAndTop10`；`hotel.spec.js:166` | F 经界面注册；两张 X×2 的主单均为76.00，取消第一张后 order_status=3；餐厅将第二张推进至1、再至2；F 在页面评5星、填「好吃」；经理当日 Top10 中 X 销量2。终态两单金额均76，第二单 order_status=2、comment_star=5、comment=好吃。 | 浏览器 1/1，失败/错误/跳过 0；3.696s |
| TC-135 新员工停用立即失效 | `tc135_disabledNewStaffSessionIsImmediatelyRejected`；`hotel.spec.js:213` | 经理在页面新建 G，role=2/status=1；另一个浏览器会话登录 G 并看房间；经理停用后，G 刷新房间触发真实 GET /rooms 401，页面清会话、显示「登录已失效」并回员工登录页；重试登录显示「账号或密码错误」。终态 status=0、is_deleted=0。 | 浏览器 1/1，失败/错误/跳过 0；2.192s |
| TC-136 独立空库、dev 启动与 Swagger | `tc136_emptyDatabaseDevBootDemoLoginAndSwagger`；`hotel.spec.js:248` | 单独新 MySQL/Redis；先断言库没有任何表，再执行正本 schema.sql 与 demo-data.sql；经环境变量 dev/DB/Redis/随机 JWT_SECRET 启动实际 jar，日志出现 Started HotelSystemBackendApplication；页面演示经理登录返回 code=0、token非空并进经理首页；匿名新浏览器显示 Swagger 分组，原 /swagger-ui.html、三个原 /swagger-ui/** 资源及 /v3/api-docs 的浏览器网络记录全200，paths非空；原10张表和演示经理都存在。 | 浏览器 1/1，失败/错误/跳过 0；3.197s（不含独立库/jar准备时间） |
| TC-137 已支付取消退款与重订 | `tc137_paidCancellationRefundAndRoomRebooking`；`hotel.spec.js:280` | H、K 均经页面注册；H 同房一晚199.00，支付后 pay_status=1；取消显示已取消/已退款，status=2、pay_status=2；经理 D 概览营收/入住率都0；K 同区间重新预订成功，status=0、pay_status=0，入住14:00/离店12:00；R1该区间仅K的一张有效单。 | 浏览器 1/1，失败/错误/跳过 0；2.948s |

| 补充验收 | 测试 | 结果 |
|---|---|---|
| 静态资产精确放行，邻近路径与业务API仍鉴权 | `filter/LoginFilterTest.tc132_staticAssetsAreAnonymousWithoutOpeningSiblingApiPaths`，默认/dev/test/e2e 4组，带 contextPath | 4/4；仅 `/`、`/index.html`、`/app.js`、`/style.css` 放行；额外子路径、伪装资产路径、rooms、staff/register 401；旧 Swagger profile/路径边界验证保持通过。 |
| S14 员工退出写方法及撤销 | `TokenSessionIT.tc063_getStaffLogoutCannotRevokeSessionButPostDoes` | GET退出405且原会话仍可访问 rooms；POST退出code0；旧token再访问rooms401。原 tc036 与 tc116 的退出调用同步 POST，撤销和无SCAN断言保持通过。 |
| S8/W1 密码配对遗漏 | `PasswordHashingIT.tc041_incompletePasswordChangeReturns400WithoutMutation`，仅原密码/仅新密码2组 | 2/2；Fixture A登录后单项合法密码提交400/code1/明确密码提示，原哈希与 token、反向session不变，rooms继续可访问。 |
| email-only 资料修改不误伤会话 | `PasswordHashingIT.tc041_emailOnlyChangePreservesPasswordAndSession` | 1/1；email更新成功，原哈希和会话不变；既有两项齐全修改密码写BCrypt、tc034撤销旧会话继续通过。 |

终态报告留存在 `final-verify/e2e/TC-132.xml`～`TC-137.xml`。每份 Playwright XML 都解析确认 tests=1、failures=0、errors=0、skipped=0；不是仅靠 Maven exit code 判断。两份 failsafe-summary.xml 也分别确认 completed=252/6，failures/errors/skipped=0、timeout=false。

## 实现与代码查证

| 查证事项 | 当前代码依据 | 实现选择 |
|---|---|---|
| 静态界面与角色导航 | `static/app.js:19`、`:52`、`:195` | 原生 DOM、label、中文导航和状态，金额保留2位、日期明确；同源 token Header；401移除sessionStorage并回正确登录页。未加框架或前端服务器。 |
| 动态文本安全输出 | `static/app.js:32`、`:127` | 姓名、手机号、评价、房号等都通过 textContent；没有把用户输入拼为HTML。 |
| 当前写方法与房号契约 | `controller/OrderController.java:82`、`:89`、`:103`，`controller/StaffController.java:60`，`dto/InsertRoomOrderDTO.java:13` | 支付/客房取消/员工退出POST，餐饮取消PUT，房号保持String；依据当前VO字段取日期、状态及金额。 |
| 脱敏资料不能用于入住证件 | `service/impl/UserServiceImpl.java:78`，`static/app.js:262`、`:273` | 资料查询中的身份证已脱敏，预订表单要求用户补完整证件号，不回填掩码；Playwright用Fixtures完整值填写。 |
| 独立夹具与真实调度 | `BrowserE2EIT.java:56`、`:70`、`:76`、`:128`，`support/Fixtures.java:169`，`service/CustomTaskScheduler.java:29`、`:44`、`:54` | e2e独立MySQL/Redis；只seed基础房间/菜品/三员工，住客空；明确断言仅e2e且调度开启。有限本地测试桥只提供data/state/checkin/checkout/expire，SQL为固定夹具语句；checkin/checkout用JVM LocalDateTime.now()-1分钟，expire用DB NOW()-16分钟。没有生产SQL接口，未直接调用任务。 |
| 调度等待的既定边界 | `hotel.spec.js:66`、`:139` | 每5秒查状态，最多70秒；TC133超时检查完整等满70秒，不因一次查库未取消就提前通过。日志记录每轮状态。 |
| 真实浏览器计数与报告隔离 | `pom.xml:192`、`:199`、`:204`，`BrowserE2EIT.java:150`，`playwright.config.js:7` | API和browser-e2e分独立failsafe执行/summary；Playwright retries=0、workers=1；每个case启动一次真实Node/Chromium且解析非空1条报告；截图/trace按case保存，避免后一次清掉前一次。 |
| dev Swagger直接200与既有资源复用 | `application-dev.yml:11`，`config/SwaggerStaticPageConfig.java:10`、`:14`，`static/swagger-ui.html:8`、`:12`，`filter/LoginFilter.java:82` | dev挪Springdoc重定向入口；用标准MVC精确路径提供静态原入口，压过依赖自身宽泛资源handler；wrapper复用原CSS/bundle/preset直接加载OpenAPI，没有复制/vendor整套UI；filter仍仅dev匿名，其他profile鉴权。 |
| S8漏洞成因及最小修复 | `mapper/UserMapper.xml:29`、`:32`，`service/impl/UserServiceImpl.java:56`、`:58`、`:72` | 原mapper任意非null passwordToChange会写库，旧Service仅两项齐全才哈希/撤销；现在Service在mapper调用前拒绝仅带一项，保留BCrypt与正常撤销，不引跨字段validator抽象。 |
| 本地人工演示隔离 | `scripts/demo.ps1:13`、`:28`、`:40`、`:48`，`BrowserE2EIT.java:82` | 独立临时MySQL8/Redis7、随机DB口令和256位JWT密钥、dev演示脚本、退出finally清本次容器/恢复环境；`target/e2e/fixtures.json` 保存Fixtures填写值供人工操作。 |

交付前已抽查上述来源的实际路径与行号；修正过两项先前判断：价格日历未设置日期返回列表元素null（页面显示默认价）；Swagger原入口302不能算正本要求的200，保留严格网络断言并修资源路由。

## 运行记录与红绿过程

所有 Maven 命令实际使用 `C:/t/tools/apache-maven-3.9.9/bin/mvn.cmd`，工作目录为 W6；表中 `mvn` 为该可执行文件的简称。浏览器筛选命令共同带 `-Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -DskipApiTests=true`；只跑API的筛选命令共同带 `-Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -DskipBrowserTests=true`。这两类仅用于开发时定位，最终完整命令不带筛选或skip。

| 实际关键命令 / 记录 | 退出码 | 结果与解释 |
|---|---|---|
| `npm install --offline --ignore-scripts` | 0 | 使用已下载缓存安装锁定1.63.0的Playwright，生成package-lock.json；Chromium版本153.0.8010.12/v1243已由主会话下载验证，安装日志另存playwright-install.log。 |
| `mvn -B verify … -Dit.test=BrowserE2EIT`，`red-browser.log` | 1 | 有效首轮JUnit六次执行，6失败/0错误/0跳过：132/133/135/136/137在缺登录或注册UI按钮处超时；134首次Node exit=-1是进程异常，不计为业务红灯。更早的锚定grep误筛0条属于测试基建修正，不冒充功能红灯。 |
| `mvn -B verify … -Dit.test=BrowserE2EIT#tc134*`，`red-tc134.log` | 1 | TC134另行有效红：1失败/0错误/0跳过，浏览器缺注册入口；因此六条主用例都有有效功能红证据。 |
| `mvn -B verify -Dtest=LoginFilterTest -DskipBrowserTests=true …`，`red-logout-static.log` | 1 | 单元10次执行中4失败，静态资产返回401；unit阶段失败导致此命令中的API筛选未执行，不以它证明退出API红灯。 |
| `mvn -B verify … -Dit.test=TokenSessionIT#tc063*`，`red-logout-api.log` | 1 | 单独执行1条API，GET员工退出得到200而非405，1失败/0错误/0跳过；改生产mapping前确认实际红。 |
| `mvn -B verify -Dtest=LoginFilterTest -DskipBrowserTests=true -Dit.test=TokenSessionIT#tc036*+tc063*+tc116*`，`green-logout-static.log` | 0 | 静态/Swagger/baseContext单元10/10，相关API8/8；失败/错误/跳过0。 |
| `mvn -B verify … -Dit.test=BrowserE2EIT`，`browser-first-green.log`（目标绿灯尝试，仍红） | 1 | JUnit6失败/0错误/0跳过；132遇未设价null元素，133/134/135/137夹具JSON序列化LocalDateTime导致fetch failed（这些Playwright自身报告为error，不能写成正常业务断言红），136为Swagger根节点strict locator歧义。分别修null显示、Jackson模块/错误响应和定位器。 |
| `mvn -B verify … -Dit.test=BrowserE2EIT`，`browser-second-green.log` | 1 | 六条实际执行5通过/1失败，JUnit错误/跳过0；五条业务流程含真实cron已通过，136原/swagger-ui.html的302违反严格200契约。 |
| `mvn -B verify … -Dit.test=BrowserE2EIT#tc136*`，`green-tc136.log`、`green-tc136-final.log`（仍红） | 各1 | 静态原入口返回200后资源404；挪同层入口后依赖自身/swagger-ui*/**资源handler又捕获原入口导致404。实际trace与本地Springdoc2.2.0字节码共同定位，采用dev-only精确标准资源handler修复，不弱化断言。 |
| `mvn -B verify … -Dit.test=BrowserE2EIT#tc136*`，`green-tc136-resource.log` | 0 | JUnit及Playwright实际1/1，失败/错误/跳过0；匿名原入口、原资源、OpenAPI全200。 |
| `mvn -B verify … -Dit.test=PasswordHashingIT#tc041*`，`red-password-pair.log` | 1 | guard改前4次执行：单旧/单新两条都错误返回200，2失败/0错误/0跳过；既有齐全改密与新email-only正向均通过。 |
| `mvn -B verify … -Dit.test=PasswordHashingIT,TokenSessionIT#tc034*,RequestValidationIT#tc129*+tc130*`，`green-password-pair.log` | 0 | BCrypt/MD5迁移、单项拒绝、email-only、改密撤销、密码输入约束共16/16，失败/错误/跳过0。 |
| `node --check src/main/resources/static/app.js`、`node --check src/test/e2e/hotel.spec.js`；PowerShell脚本语法解析；`git diff --check` | 0 | 语法和diff空白检查通过。脚本语法检查不当作人工演示完成。 |
| **`mvn -B clean verify`，`full-verify.log`** | **0** | **27单元 + 252 API + 6真实Playwright = 285/285；0失败/0错误/0跳过；两个failsafe verify执行均完成；BUILD SUCCESS。完成时间2026-09-30 22:24:11 +08:00，用时06:35。** |

最终报告统计来自原生XML，记录在 `final-run-summary.json`。没有基线失败或清单外未修复失败。最终执行中的186个不同JUnit方法全部符合 `tcNNN_` 前缀，参数化共285次执行；137个编号前缀仅是命名检查，六条端到端主用例通过依据是实际Playwright流程和断言。

按主会话 `legacy-methods.json` 清单给11类25个旧补充方法加真实相关用例前缀；保留原语义后缀与断言，没有新增镜像测试。原上传格式参数化方法有5次执行，因此这些旧补充方法对应29次执行。旧名→新名记录在 `legacy-renames.json`，并标supplementary；其中时间夹具辅助验证关联TC133，不把辅助验证当主流程通过。

## M1～M7 发布前文档核对

按正本 `testplan.json.strategy.out_of_scope` 逐项人工查源码并核对 README、api-overview、architecture，不额外生成镜像测试。表中行号为最终交付文件行号。

| 项目 | 文档位置与处理 | 当前代码依据 / 核对结论 |
|---|---|---|
| M1 登录态立即失效 | `README.md:188`；`docs/architecture.md:149` | `service/RedisService.java:33`重复登录先revoke、`:42`撤销token和反向索引；`controller/StaffController.java:64`退出、`service/impl/StaffServiceImpl.java:56`停用/`:65`删除、`service/impl/UserServiceImpl.java:72`改密。文档明确员工退出、停用、删除、改密和重复登录立即生效；住客网页退出只是清本浏览器会话，当前没有住客服务端退出接口；两类TTL均3小时。 |
| M2 服务端计算金额 | `README.md:44`、`:200`；`docs/architecture.md:178` | `service/impl/OrderServiceImpl.java:82`～`:97`客房按晚计价；`:200`～`:212`餐饮查库价并重写unitPrice/totalPrice。客户端单价、总价不参与餐饮结算；正本TC134验证两单76.00。 |
| M3 事务一致性 | `README.md:45`；`docs/architecture.md:163`、`:178` | `service/impl/OrderServiceImpl.java:69`/`:75`下单、`:103`改期、`:200`餐饮；`service/impl/RoomServiceImpl.java:64`房态；`service/CustomTaskScheduler.java:30`/`:45`/`:55`任务都有Spring数据库事务。已删去价格批量更新“使用Spring事务”的不实枚举：实际 `service/impl/BusinessServiceImpl.java:264` 调单条 `mapper/RoomMapper.xml:24` upsert。文档仅称同一DB事务中的异常回滚，未声称Redis和DB统一分布式回滚。 |
| M4 V2仅设计及实际预订防冲突 | `README.md:11`、`:48`～`:53`、`:325`；`docs/architecture.md:176`、`:329` | 徽章为V2 Design；表格、正文与演进勾选明确每日库存/requestId幂等仅设计完成、未实现。当前 `mapper/RoomMapper.xml:5`房间行锁，`mapper/OrderMapper.xml:85`半开时刻区间重叠检查，`service/impl/OrderServiceImpl.java:82`事务内组合；删除旧“仅高并发冲突”的表述，说明当前可拒绝重叠预订。 |
| M5 成功响应code0 | `docs/api-overview.md:23`、`:29` | `constant/ResultCodeConstant.java:4` SUCCESS=0。统一示例和说明改为成功0、失败1，保留400/401/403/409等真实HTTP错误语义。 |
| M6 退房进入清洁中 | `README.md:196`；`docs/architecture.md:211`～`:214`；`docs/api-overview.md:138` | `service/CustomTaskScheduler.java:31`事务退房调用 `mapper/RoomMapper.xml:14`～`:15`条件将占用置2；清洁完成经 `service/impl/RoomServiceImpl.java:65` / 页面PUT rooms置0。状态图准确为定时退房 OCCUPIED→CLEANING→AVAILABLE；另保留前台手工置空闲结束订单的既有路径。TC132/133真实cron终态一致。 |
| M7 Swagger profile与网络契约 | `README.md:315`；`docs/api-overview.md:9` | `filter/LoginFilter.java:82`仅dev匿名Swagger/OpenAPI；`application-dev.yml:11`与dev-only `config/SwaggerStaticPageConfig.java:10`/`:14`精确资源入口。TC136匿名新浏览器原页面、资源、OpenAPI都200；其他profile的旧权限验证继续通过。 |

一并核对 REST 文档：`api-overview.md:56`、`:84`、`:85` 写明员工退出/支付/客房取消POST，餐饮取消PUT；成功code=0；不新增未实现的标准Bearer接口。

主会话补充发现的两处调度事实，单独文档提交落实于 `docs/architecture.md:327`、`:338`：退房 `releaseExpiredRooms` 已用 `scheduler_task_lock` 数据库行锁/分钟条件抢占，在事务内按该任务整批互斥；另两项任务采用条件更新；失败重试与补偿尚未实现，拆成独立未勾选待办。依据 `service/CustomTaskScheduler.java:31`～`:41`、`:46`～`:57`，`mapper/OrderMapper.xml:150`～`:157`，`mapper/RoomMapper.xml:11`～`:12`，`mapper/OrderMapper.xml:128`～`:136`。没有把三个任务都说成持有任务锁。

## 留存结果与复跑/演示

- `full-verify.log`：最终无筛选完整日志。
- `final-verify/surefire-reports/`、`final-verify/failsafe-reports/`、`final-verify/failsafe-e2e-reports/`：完成后复制的原生JUnit与两份failsafe summary，主会话再次clean不会覆盖这份实现证据。
- `final-verify/e2e/`：六条日志/XML、dev启动日志、Fixtures填写值、每条final截图与trace；另有TC135员工失效截图、TC136 Swagger截图。实现方已查看餐饮Top10和Swagger截图，确认界面正常；不以看截图代替人工走流程。
- `swagger-network-final.json`：从本次TC136原生trace解析的真实响应URL及状态；原入口、CSS、bundle、preset、两次OpenAPI响应全部200。
- `final-run-summary.json`：285次执行、186个不同方法、编号检查及六份Playwright计数/耗时；`legacy-renames.json`：旧补充方法改名对照。

复跑（依赖尚未安装时先npm ci；本机已安装且浏览器缓存已就绪）：

```powershell
Set-Location C:/t/hsb/.nova/worktrees/W6
npm ci
npx playwright install chromium
& 'C:/t/tools/apache-maven-3.9.9/bin/mvn.cmd' -B clean verify
# 定位单条浏览器时：
& 'C:/t/tools/apache-maven-3.9.9/bin/mvn.cmd' -B verify '-Dtest=NONE' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipApiTests=true' '-Dit.test=BrowserE2EIT#tc132*'
```

独立人工演示（使用完整验证已生成的jar，与API/e2e容器互不复用）：

```powershell
Set-Location C:/t/hsb/.nova/worktrees/W6
powershell -NoProfile -File scripts/demo.ps1 -Port 8080
```

等待 Started 日志后打开 `http://127.0.0.1:8080/`。员工演示账号：admin/Admin@123、front/Front@123、kitchen/Kitchen@123；住客13900000000/User@1234。临时MySQL/Redis和随机密钥由脚本生成，Ctrl+C后finally清本次容器并恢复进程环境；仅记录非敏感URL/容器ID到忽略的target/demo-info.json。

新增住客的完整注册/入住数据可取 `target/e2e/fixtures.json`（每次reset后由Fixtures生成；留存副本在final-verify/e2e）。其中base.rooms为e2e的1101等别名，dev演示库房号使用demo-data.sql的101/201等；人工预订从实际页面选demo房间。

## 主会话独立验证与人工演示

以下独立结果来自主会话执行记录；已读取 `verify-parent-summary.json` 并核对截图文件存在：

- 主会话在最终HEAD `2ece1a816744c40aea50100086f395525b6fa246` 独立执行完整clean verify，**285/285**通过：27单元/252 API/6浏览器，失败/错误/跳过0，两份failsafe summary分别252/6且timeout=false；六份Playwright XML各实际1/1，方法名编号合规，137个case编号完整。日志 `verify-parent.log`、统计 `verify-parent-summary.json`。
- 主会话已无冲突 `--no-ff` 合入 `nova/review-fixes`，合并提交 `7aed849e829a87418504a80ed7c31019f29c43b8`；该轮集成完整全验 `verify-integration.log` 已 285/285 通过，失败/错误/跳过均 0。之后的 UI 文案与并发鉴权补修复见本文件末尾的最终收口记录。
- 主会话实际启动W6 `scripts/demo.ps1` 的独立dev演示，用CUA亲自走Fixture E注册/登录、demo101房一晚199元预订、支付、取消后已退款；另走38元餐饮下单和取消；经理查询10/1当日/月营收0，Top10不计已取消餐饮单。这些是独立人工页面操作，不属于Playwright自动化替代。
- 人工退款截图 `C:/Users/Administrator/Documents/Codex/2026-09-30/nova-hotelsystembackend-review-fixes-c-t/outputs/W6-manual-refund.jpg`；经营分析截图同目录 `W6-manual-business.jpg`。截图由主会话保存，集成末轮结果与后续演示收尾由主会话补记。

## 改动文件与范围补齐

| 文件组 | 改动 |
|---|---|
| `src/main/resources/static/index.html`、`style.css`、`app.js` | 原生同源中文UI，角色导航和完整业务操作，加载/错误、金额/日期/状态、安全动态文本及401回登录。 |
| `filter/LoginFilter.java` | 具体静态资产仅GET/HEAD免登录；保留API及Swagger鉴权边界。 |
| `application-dev.yml`、`static/swagger-ui.html`、`config/SwaggerStaticPageConfig.java` | dev原Swagger入口直接200，复用已有依赖资源和OpenAPI，修复严格网络契约。 |
| `controller/StaffController.java`、`TokenSessionIT.java` | S14遗留GET退出改POST，补拒绝GET且不撤销、POST后撤销的最小反向验证并同步已有调用。 |
| `service/impl/UserServiceImpl.java`、`PasswordHashingIT.java` | S8/W1密码单项漏项补齐，最小Service guard及2个反向参数/1个email-only正向；不引validator抽象。 |
| `BrowserE2EIT.java`、`src/test/e2e/hotel.spec.js`、`application-e2e.yml`、`support/Fixtures.java` | 六条真实UI用例、独立e2e/dev容器、有限测试桥、真实cron夹具和等待、Fixtures别名与填写值保存。 |
| `filter/LoginFilterTest.java` | 4组静态路径放行边界；已有Swagger边界验证保持。 |
| `pom.xml`、`package.json`、`package-lock.json`、`playwright.config.js`、`.gitignore` | 单独failsafe browser-e2e执行与summary，精确锁测试依赖，忽略测试运行输出；非最终定位可选skip开关默认false。 |
| `scripts/demo.ps1` | 隔离Docker dev演示启动，随机密钥/端口、环境恢复、关闭清本次资源。 |
| `README.md`、`docs/api-overview.md`、`docs/architecture.md` | 静态UI/测试复跑/演示说明，M1～M7及当前REST/统计/调度事实修正；没有全面重写文档。 |
| 11类旧补充测试（详见legacy-renames.json） | 仅加相关tc编号前缀，保留原语义名称和断言，不新增重复测试。 |

本次两个安全补齐属于主会话明确授权的既有S8/S14修复范围。Swagger精确资源handler与填写完整证件号是履行既定端到端契约所需的最小修复。其他代码改动均在W6工作区；日志和本证据按派单保存在主工作区build/W6目录。

## 提交

```text
2ece1a8 W6 docs: clarify checkout task minute locking and pending failure retries
aeee6c6 W6 TC132-137: add native hotel UI and isolated browser regression; complete S8/S14 guards
```

`aeee6c6` 是实现方完整285/285通过时的源码版本；`2ece1a8`仅文档3增2删，按主会话要求独立提交。主会话已基于最终HEAD独立完整验证并--no-ff合入，合入后的末轮全测结果由主会话补记。

## 遗留

W6实现与自动化验收无未完成项、无未知测试失败。用户已决定跳过代码评审；未自行改变该流程。主会话独立全验与关键人工浏览器流程已完成并单独记录；合入后集成末轮全验结果由主会话补记。V2每日库存、requestId幂等、定时任务失败重试为既定未实现演进项，文档已准确标明，没有纳入本批新增实现。


## 最终收口（主会话，2026-09-30）

- 文案补修复 `fb38303eb541e0102c38775fd822660a4c600aad`：TC134 先断言精确可见“待完成”，真实红灯 1 失败、0 错误、0 跳过；随后只去掉 UI 的内部状态数字。主会话完整验证 `verify-parent-final.log` 285/285，通过后 `--no-ff` 合入 `6c5699697b5b9d1573e0012dac28782cde6f6bcd`，`verify-integration-final.log` 亦 285/285。
- 真实手查发现并发失效请求把经理错误切到住客登录。扩充既有 TC135：第二个真实经理登录撤销第一个令牌，经营页 stats/trend/top10 三个请求均返回 401、全部结束后仍须显示员工登录。修复前 `authguard-red.log` / `authguard-red-e2e/TC-135.xml` 实际 1 失败、0 错误、0 跳过，失败在员工登录标题断言。`authguard-preflight-skipped.log` 是参数导致未执行的定位命令，不计为红灯或有效验收。
- 最小共享修复 `app.js` 的 api()：保存原请求 token，只在该 token 仍等于当前 session 时清理并按原角色回登录；后续旧响应不能二次改变登录入口或撤销新会话。源码提交 `a23edaeaabb57e00074b108f4ec720707c89ff91`，仅生产 JS 与原有 TC135 两个文件；独立及合入后的完整验证 `verify-parent-auth.log` / `verify-integration-auth.log` 均 285/285、零失败/错误/跳过，合入 `4905e7d11a5676e48b7d5cde6d22e55c7461bbc9`。
- 最终餐厅演示另发现订单明细字段错配：实体 MealOrderItem 与 Mapper 都返回 `name`，共享 mealCard() 却读取 `dishName`。在原 TC134 对菜品 X 的真实餐厅明细新增名称断言，`dish-name-red.log` / `dish-name-red-e2e/TC-134.xml` 实际 1 失败、0 错误、0 跳过。`dish-name-preflight-empty.log` 因定向方法名写错未执行测试，不计为红灯或验收。随后只把 `item.dishName` 改为已有 `item.name`，源码 `6edcf1e3a915b3d4c3bde0591582e16f3c1c85f6`，仅 JS 与原 TC134 两行变化。
- 主会话最终独立完整 `clean verify`：`verify-parent-name.log` / `verify-parent-name-summary.json`，**285/285**，27 单元 + 252 API + 6 浏览器，失败/错误/跳过均 0。
- 无冲突 `git merge --no-ff nova/review-fixes-W6` 合入 `79e476ead522a034b507c29d939310566063631b`；集成 worktree 最终完整 `clean verify`：`verify-integration-name.log` / `verify-integration-name-summary.json`，**285/285**，失败/错误/跳过均 0。两份 failsafe summary 分别实际 252/6、timeout=false；六份原生 Playwright XML 各实际 1/1；全部 JUnit 方法带 tc 编号，定稿 TC001–137 无遗漏。
- 最终原始报告、六份浏览器 XML/截图/trace、Fixtures 数据留存于 `integration-ui-final/`；`results-final.json` 由 nova results.py 从本轮实际 XML 导入，137 个批准用例全部通过。
- M1–M7 文档与七篇代码地图已收口；文档提交 `dd67580b372cda690c163d61b320d0f858b2f690`，地图 _meta 的源码基线为 `79e476ead522a034b507c29d939310566063631b`。另更正文档的 Node 最低版本 20+、上传仅 MANAGER；两项均根据已安装依赖和生产注解核实。
- 无阻塞失败或未完成的批准用例。继续保留 F4–F7、历史订单间夜回填/既有库 DDL 迁移、生产时区及旧状态命名核实等已知事项。退款仅记录状态和未做渠道补退属于已批准默认；不宣称已接入资金渠道。
