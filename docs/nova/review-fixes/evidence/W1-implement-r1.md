# W1 实现证据（第 1 轮）

- worktree：`C:\t\hsb\.nova\worktrees\W1`，分支 `nova/review-fixes-W1`。
- 恢复时 HEAD：`203f4b9`；交付 HEAD：`1840629`；集成基线：`4753d87`（W0、W4、W5 已合入）。
- 范围：S1–S3、S6–S9、S11、S13、P1、E3；batches.json 的 W1 共 40 条用例。另补 TC-136 的后端 Swagger 验证，完整端到端流程归 W6。
- 以 `conventions.md` 和 testplan.json 缺口默认值为准：GAP-03（忽略用户路径 id，身份证保留前 6 后 4 位）、GAP-06（员工同样迁移 MD5）、GAP-07（账号/IP 15 分钟失败 5 次，限制 15 分钟）、GAP-09（JWT_SECRET 缺失或不足 256 位拒绝启动）、GAP-23（只有 dev 匿名开放 Swagger；其他 profile 仍需 token）。
- 合入 W4、W5：`git merge nova/review-fixes` → `168b55c`，自动合并，无冲突。核对了 UserMapper Java/XML 及 application.yml：保留 W1 的密码更新、W4 的统计查询和按晚表、W5 的上传限制与 dev SQL 日志。
- 集成状态：已用 `git merge --no-ff nova/review-fixes-W1` 合入本地 `nova/review-fixes@eab69cd`，完整测试通过。

## 验收对照

下表由最终 JUnit XML 报告生成。参数化测试按实际执行次数计数，40 条 W1 用例全部有通过记录。
W1 主体实现来自上一会话，交接材料未提供其逐条历史红灯日志；本轮 E3 的红灯已实际运行并记录，未把回归通过当作历史红灯。

| 用例 | 测试（类 · 方法）及执行次数 | 结果 |
|---|---|---|
| TC-001 类上标 MANAGER、方法上标 FRONT，当前角色为 FRONT 时切面放行 | `RoleCheckAspectTest.tc001_methodAnnotationWinsOverClassAnnotation_frontAllowed` × 1 | 1/1 通过 |
| TC-002 类上标 MANAGER、方法上标 FRONT，当前角色为 MANAGER 时切面拒绝 | `RoleCheckAspectTest.tc002_methodAnnotationWinsOverClassAnnotation_managerRejected` × 1 | 1/1 通过 |
| TC-003 BaseContext 没有角色时切面直接拒绝 | `RoleCheckAspectTest.tc003_missingRoleIsRejected` × 1 | 1/1 通过 |
| TC-004 下游正常返回或抛异常时，LoginFilter 都在请求结束后清理 BaseContext | `LoginFilterTest.tc004_baseContextIsClearedAfterRequest` × 2 | 2/2 通过 |
| TC-005 用仓库原硬编码密钥签发的 JWT 在修复后验签失败 | `JwtUtilsTest.tc005_tokenSignedWithLegacyHardcodedKeyIsRejected` × 1 | 1/1 通过 |
| TC-006 src/main/java 中不再手动获取 Redis 连接 | `NoManualRedisConnectionTest.tc006_sourceNeverFetchesRedisConnectionManually` × 1 | 1/1 通过 |
| TC-010 住客、前台、餐厅调用 /business/** 的 7 个查询接口都返回 403 | `AccessControlIT.tc010_nonManagerCannotQueryBusiness` × 21<br/>`RoleCheckAspectTest.tc010_classAnnotationAppliesWhenMethodHasNone` × 1 | 22/22 通过 |
| TC-011 住客、前台、餐厅调用 POST /business/calendar 返回 403 且价格日历不变 | `AccessControlIT.tc011_nonManagerCannotUpdatePriceCalendar` × 3 | 3/3 通过 |
| TC-012 住客、前台、经理调用 GET /restaurant/live-order 返回 403 | `AccessControlIT.tc012_nonRestaurantCannotReadLiveOrders` × 3 | 3/3 通过 |
| TC-013 住客、前台、经理调用 PUT /restaurant/status 返回 403 且餐饮订单状态不变 | `AccessControlIT.tc013_nonRestaurantCannotChangeMealOrderStatus` × 3 | 3/3 通过 |
| TC-014 前台调用方法上标 MANAGER 的 POST /staff/register 被拒且不建员工 | `AccessControlIT.tc014_frontCannotRegisterStaff` × 1 | 1/1 通过 |
| TC-015 住客调用方法上标 MANAGER 的 DELETE /order/{id} 被拒且订单未删除 | `AccessControlIT.tc015_userCannotDeleteOrder` × 1 | 1/1 通过 |
| TC-016 单工作线程下，经理请求结束后不带 token 的 POST /staff/register 返回 401 且不建员工 | `AnonymousStaffRegisterIT.tc016_anonymousStaffRegisterOnManagersThreadIsRejected` × 1 | 1/1 通过 |
| TC-017 不在白名单、路径含 /login 或 /register 的请求不带 token 返回 401 | `AccessControlIT.tc017_pathsOutsideWhitelistRequireToken` × 3 | 3/3 通过 |
| TC-018 住客 A 请求 GET /user/{B 的 id} 只能拿到自己的信息 | `UserInfoIT.tc018_userCannotReadAnotherUsersInfo` × 1 | 1/1 通过 |
| TC-019 GET /user/{id} 返回的身份证号已脱敏 | `UserInfoIT.tc019_idCardNumberIsMasked` × 1 | 1/1 通过 |
| TC-032 员工重复登录后只有最新 token 有效 | `TokenSessionIT.tc032_staffReLoginInvalidatesPreviousToken` × 1 | 1/1 通过 |
| TC-033 员工被删除后其原 token 立即失效 | `TokenSessionIT.tc033_deletedStaffTokenIsRevoked` × 1<br/>`TokenSessionIT.tc033_disabledStaffTokenIsRevoked` × 1 | 2/2 通过 |
| TC-034 住客改密码后旧 token 立即失效 | `TokenSessionIT.tc034_userPasswordChangeRevokesOldToken` × 1 | 1/1 通过 |
| TC-035 住客重复登录后旧 token 失效（原本生效的路径保持有效） | `JwtUtilsTest.tc035_tokensForSameClaimsAreDistinct` × 1<br/>`TokenSessionIT.tc035_userReLoginInvalidatesPreviousToken` × 1 | 2/2 通过 |
| TC-036 员工退出后其 token 失效（原本生效的路径保持有效） | `TokenSessionIT.tc036_staffLogoutInvalidatesToken` × 1 | 1/1 通过 |
| TC-037 住客登录后 Redis 反向索引 key 指向本次 token | `TokenSessionIT.tc037_userLoginWritesSessionIndex` × 1 | 1/1 通过 |
| TC-038 员工登录后 Redis 反向索引 key 指向本次 token 且 token key 带 3 小时 TTL | `TokenSessionIT.tc038_staffLoginWritesSessionIndexAndTokenTtl` × 1 | 1/1 通过 |
| TC-039 住客注册后密码以 BCrypt 存储 | `PasswordHashingIT.tc039_registeredUserPasswordIsBcrypt` × 1 | 1/1 通过 |
| TC-040 经理新建员工后密码以 BCrypt 存储 | `PasswordHashingIT.tc040_staffCreatedByManagerHasBcryptPassword` × 1 | 1/1 通过 |
| TC-041 住客改密码后新密码以 BCrypt 存储 | `PasswordHashingIT.tc041_changedUserPasswordIsBcrypt` × 1 | 1/1 通过 |
| TC-042 旧 MD5 哈希的住客用正确密码登录后密码迁移为 BCrypt | `PasswordHashingIT.tc042_legacyUserIsMigratedToBcryptOnSuccessfulLogin` × 1 | 1/1 通过 |
| TC-043 旧 MD5 哈希的员工用正确密码登录后密码迁移为 BCrypt | `PasswordHashingIT.tc043_legacyStaffIsMigratedToBcryptOnSuccessfulLogin` × 1 | 1/1 通过 |
| TC-044 旧 MD5 哈希住客输错密码时不迁移 | `PasswordHashingIT.tc044_legacyUserIsNotMigratedOnWrongPassword` × 1 | 1/1 通过 |
| TC-045 库中仍为旧 MD5 哈希的住客用正确原密码改密码成功 | `PasswordHashingIT.tc045_userStillOnLegacyHashCanChangePassword` × 1 | 1/1 通过 |
| TC-046 住客登录时账号不存在与密码错误的提示相同 | `LoginFailureIT.tc046_userLoginUnknownAccountAndWrongPasswordLookTheSame` × 1 | 1/1 通过 |
| TC-047 员工登录时账号不存在与密码错误的提示相同 | `LoginFailureIT.tc047_staffLoginUnknownAccountAndWrongPasswordLookTheSame` × 1 | 1/1 通过 |
| TC-048 同一账号失败 N-1 次（4 次）后仍可用正确密码登录 | `LoginRateLimitIT.tc048_fourFailuresStillAllowCorrectPassword` × 2 | 2/2 通过 |
| TC-049 同一账号失败 N 次（5 次）后正确密码也被限制 | `LoginRateLimitIT.tc049_fiveFailuresLockTheAccount` × 2 | 2/2 通过 |
| TC-050 同一 IP 对不同账号累计失败达到阈值后，其他账号也被限制 | `LoginRateLimitIT.tc050_fiveFailuresFromOneIpAcrossAccountsLockTheIp` × 1 | 1/1 通过 |
| TC-051 限制时长过后可以重新登录 | `LoginRateLimitIT.tc051_loginAllowedAgainAfterLockExpires` × 1 | 1/1 通过 |
| TC-058 未配置 JWT 密钥环境变量时应用拒绝启动 | `JwtSecretRequiredIT.tc058_applicationRefusesToStartWithoutJwtSecret` × 1<br/>`JwtUtilsTest.tc058_secretShorterThan256BitsIsRejected` × 1 | 2/2 通过 |
| TC-061 白名单外 Origin 的预检请求不返回允许来源头 | `CorsIT.tc061_preflightFromUnlistedOriginGetsNoAllowOrigin` × 1 | 1/1 通过 |
| TC-062 白名单内 Origin 的预检请求返回该 Origin 且允许凭证 | `CorsIT.tc062_preflightFromListedOriginIsAllowedWithCredentials` × 1 | 1/1 通过 |
| TC-116 登录、退出和撤销 token 不触发 Redis SCAN | `TokenSessionIT.tc116_loginLogoutAndRevocationDoNotScan` × 6 | 6/6 通过 |
| TC-136 空库执行 schema.sql 后以开发 profile 启动，演示账号可登录，Swagger UI 可打开 | `DefaultProfileIT.tc136_defaultProfileSwaggerRequiresToken` × 1<br/>`DevProfileDemoDataIT.tc136_devProfileSwaggerLoadsWithoutToken` × 1<br/>`LoginFilterTest.tc136_swaggerWhitelistRespectsProfileAndPathBoundaries` × 4 | 6/6 通过（后端部分；浏览器端到端待 W6） |

## 实现要点

- E3：LoginFilter 通过 Spring Environment 判断是否启用 dev；仅放行 `/swagger-ui.html`、`/swagger-ui/**`、`/v3/api-docs`、`/v3/api-docs/**`。非 dev 延续 token 校验，普通匿名登录/注册仍按精确路径白名单处理。
- 路径边界：默认、dev、test、e2e 四组单元验证；带 `/hotel` context path 仍正确。`/swagger-ui.html/extra`、`/swagger-ui-extra`、`/v3/api-docs-extra`、`/rooms/swagger-ui/index.html` 均不得因相似前缀放行。
- 真实 HTTP：dev 匿名获取 UI（跟随 302 重定向）、CSS/JS、swagger-config 及 OpenAPI JSON 均为 200，paths 非空；默认生产 profile 对相同匿名路径均返回 401。
- 测试约定：W1 四个补充测试改为含 tc 编号的方法名。JWT 单元测试取 Fixtures.registration("N") 的手机号；过滤器纯单元测试使用该别名派生的模拟身份，未写死数据库 id。业务集成测试使用 base 的住客、员工、房间等别名。
- 未引入新依赖或额外 Swagger 配置项。

## 运行记录

Maven 使用 `C:\t\tools\apache-maven-3.9.9\bin\mvn.cmd`。完整验证命令为 `mvn -B clean verify`；Docker Desktop 运行中，沿用 MySQL 8.0、Redis 7 和 api.version=1.44，JVM/数据库时区为 Asia/Shanghai。

| 命令/阶段 | 退出码 | 结果 |
|---|---|---|
| `mvn -B verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=DevProfileDemoDataIT#tc136*,DefaultProfileIT#tc136*`（只加测试） | 1 | 2 次执行，1 通过、1 失败：dev `/swagger-ui.html` 实际 401，期望 200。日志 `e3-red.log` |
| `mvn -B verify -Dtest=LoginFilterTest -Dit.test=DevProfileDemoDataIT#tc136*,DefaultProfileIT#tc136*`（E3 修复后） | 0 | surefire 6/6，failsafe 2/2；0 失败、0 错误、0 跳过。日志 `e3-green.log` |
| `mvn -B clean verify`（合入 W4/W5 后） | 0 | surefire 23/23，failsafe 153/153；0 失败、0 错误、0 跳过。日志 `verify-w1-r1.log` |
| `mvn -B clean verify`（统一补充测试编号与夹具后，最终 W1） | 0 | surefire 23/23，failsafe 153/153；0 失败、0 错误、0 跳过。日志 `verify-w1-final.log` |
| `mvn -B clean verify`（集成 worktree，`eab69cd`） | 0 | surefire 23/23，failsafe 153/153；0 失败、0 错误、0 跳过 |

JUnit 报告：两个 worktree 各自的 `target/surefire-reports/`、`target/failsafe-reports/`。摘要与日志保存于主工作区 `.nova/review-fixes/build/W1/`。基线失败清单为「无」。

## 改动文件

本轮生产代码只补 LoginFilter 的 dev Swagger 白名单；补充/整理测试位于 LoginFilterTest、JwtUtilsTest、RoleCheckAspectTest、TokenSessionIT、DefaultProfileIT、DevProfileDemoDataIT。
以下为整个 W1 相对集成基线的完整文件清单（包含前一会话实现；A 新增、M 修改）：

```text
M	pom.xml
M	src/main/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspect.java
M	src/main/java/com/winniethepooh/hotelsystembackend/config/GlobalCorsConfig.java
M	src/main/java/com/winniethepooh/hotelsystembackend/context/BaseContext.java
M	src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java
M	src/main/java/com/winniethepooh/hotelsystembackend/controller/UserController.java
M	src/main/java/com/winniethepooh/hotelsystembackend/filter/LoginFilter.java
M	src/main/java/com/winniethepooh/hotelsystembackend/mapper/StaffMapper.java
M	src/main/java/com/winniethepooh/hotelsystembackend/mapper/UserMapper.java
A	src/main/java/com/winniethepooh/hotelsystembackend/service/LoginAttemptService.java
M	src/main/java/com/winniethepooh/hotelsystembackend/service/RedisService.java
M	src/main/java/com/winniethepooh/hotelsystembackend/service/impl/StaffServiceImpl.java
M	src/main/java/com/winniethepooh/hotelsystembackend/service/impl/UserServiceImpl.java
M	src/main/java/com/winniethepooh/hotelsystembackend/utils/JwtUtils.java
A	src/main/java/com/winniethepooh/hotelsystembackend/utils/PasswordUtils.java
M	src/main/resources/application.yml
M	src/main/resources/db/demo-data.sql
M	src/main/resources/mapper/StaffMapper.xml
M	src/main/resources/mapper/UserMapper.xml
A	src/test/java/com/winniethepooh/hotelsystembackend/AccessControlIT.java
A	src/test/java/com/winniethepooh/hotelsystembackend/AnonymousStaffRegisterIT.java
A	src/test/java/com/winniethepooh/hotelsystembackend/CorsIT.java
M	src/test/java/com/winniethepooh/hotelsystembackend/DefaultProfileIT.java
M	src/test/java/com/winniethepooh/hotelsystembackend/DevProfileDemoDataIT.java
M	src/test/java/com/winniethepooh/hotelsystembackend/HotelSystemBackendApplicationTests.java
A	src/test/java/com/winniethepooh/hotelsystembackend/JwtSecretRequiredIT.java
A	src/test/java/com/winniethepooh/hotelsystembackend/LoginFailureIT.java
A	src/test/java/com/winniethepooh/hotelsystembackend/LoginRateLimitIT.java
A	src/test/java/com/winniethepooh/hotelsystembackend/NoManualRedisConnectionTest.java
A	src/test/java/com/winniethepooh/hotelsystembackend/PasswordHashingIT.java
M	src/test/java/com/winniethepooh/hotelsystembackend/RequestValidationIT.java
A	src/test/java/com/winniethepooh/hotelsystembackend/TokenSessionIT.java
A	src/test/java/com/winniethepooh/hotelsystembackend/UserInfoIT.java
A	src/test/java/com/winniethepooh/hotelsystembackend/aspect/RoleCheckAspectTest.java
A	src/test/java/com/winniethepooh/hotelsystembackend/filter/LoginFilterTest.java
M	src/test/java/com/winniethepooh/hotelsystembackend/support/Fixtures.java
M	src/test/java/com/winniethepooh/hotelsystembackend/support/IntegrationTestBase.java
A	src/test/java/com/winniethepooh/hotelsystembackend/utils/JwtUtilsTest.java
M	src/test/resources/application-test.yml
A	src/test/resources/jwt/legacy-hardcoded-key.jwt
```

## 提交

以下提交均留在本机，未 fetch、未 push：

```text
43dd97e W1 S11 TC-005 TC-058: JWT 密钥改由 hotel.jwt.secret（JWT_SECRET）提供，缺失或不足 256 位拒绝启动
c82b605 W1 S1 S2 TC-001~004 TC-010~017: 类上的 @RoleRequired 生效；白名单精确匹配，请求结束清理 BaseContext
1b50872 W1 S6 S7 P1 TC-006 TC-032~038 TC-116: 登录态改用 session:{ROLE}_{id} 反向索引撤销，员工 token 3 小时 TTL，去掉全量 SCAN
63fcc2d W1 S8 TC-039~045: 密码改用 BCrypt；旧 MD5 哈希登录成功时迁移，改密码时兼容旧哈希
b56a708 W1 S9 TC-046~051: 登录失败统一提示「账号或密码错误」；按账号和 IP 计数，15 分钟内失败 5 次限制 15 分钟
a987a68 Merge nova/review-fixes (W0 修复轮) into nova/review-fixes-W1
1c79037 W1 S3 TC-018 TC-019: GET /user/{id} 只返回当前住客本人，身份证号保留前 6 后 4 位
203f4b9 W1 S13 TC-061 TC-062: CORS 只允许 hotel.cors.allowed-origins（CORS_ALLOWED_ORIGINS）白名单，CORS 过滤器显式先于 LoginFilter
0feeead W1 E3 TC-136: 仅 dev profile 匿名开放 Swagger UI 资源和 OpenAPI 文档
168b55c Merge branch 'nova/review-fixes' into nova/review-fixes-W1
1840629 W1 测试约定: 补充测试带用例编号，模拟身份改取 Fixtures 别名
```

## 遗留

1. W1 无阻塞失败；TC-136 的完整 Playwright 浏览器流程仍由 W6 实现，后端验证通过不代表整条端到端用例已完成。
2. 沿用 `.nova/review-fixes/build/followups.md` 的 W0 F4–F7：DTO 必填/长度约束、多个字段非法时提示不固定、校验先于鉴权、Docker API/Redis 版本差异。
3. W4 的按晚营收依赖 W2 在下单、前台开单、改期时写入 room_order_night；当前接口下单的营收仍有该已知缺口。
4. 代码地图 03-data、06-conventions 待最终批次后更新；W2、W3、W6 尚未开始。


## 后续批次收口

W6 已以真实浏览器完成 TC136：dev 匿名 Swagger 入口、资源与 /v3/api-docs 均实际 200；其他 profile 的后端反向鉴权通过。W2 已完成下单/改期的 room_order_night 写入，W6 已完成六条端到端，最终集成 285/285。代码地图已更新；以上替代当时的待做事项。
