# W2 + W3 实现证据（r1）

## 完成情况

基线 `eab69cd`（W0/W1/W4/W5 已合入）；分支 `nova/review-fixes-W2`；代码 worktree `C:/t/hsb/.nova/worktrees/W2`。
最终代码提交 `bccb03563df6f59b2385b6f297170d3a0d426335`。W2 的 46 条与 W3 的 10 条用例共 56 条都有生产入口测试，参数和补充边界展开为 `OrderWorkflowIT` 的 95 次执行，全部通过。
完整 `clean verify`：surefire 23、failsafe 248，总 271；失败 0、错误 0、跳过 0。基线失败清单为「无」。提交后 worktree 干净。
依用户已确认的轻量流程，本批次跳过独立代码评审；主会话已亲自完成独立完整验证并合入，集成分支复测也已完成：271/271，失败、错误、跳过均 0，详见末尾记录。

## 输入与约定

- 需求正本：`.nova/review-fixes/test-plan/inputs/代码评审问题清单.md` 的 S4/S5/S14、B1–B11/B13、P2/P5 条目。
- 测试正本：`docs/nova/review-fixes/testplan.json`；派单快照：`.nova/review-fixes/build/W2/case-inputs.json`，含 56 条完整用例、判据、缺口和 strategy。
- 命名与默认值：`.nova/review-fixes/build/conventions.md`，尤其 §1 时区、§5 餐饮取消接口和 §6 错误关键字；缺口按 GAP-04/05/11–17/19/22/24/25/28/29 默认执行。
- 已读取 nova runtime、implementer、grounding，代码地图 README/05-rules/06-conventions；地图扫描基线旧于当前代码，结论均回当前代码确认。
- 新增测试用 `base.user/room/dish`、`Fixtures.guest` 和标准下单体；不存在的 id/房号使用用例明确给出的无效值。每次参数执行前由现有基类清库、清 Redis、恢复基础数据。
- 复用现有 Fixtures、IntegrationTestBase、SqlCounter、BusinessException、LocalDateUtil 和 RoomMapper.getPriceCalendars。未新增依赖、通用服务层或新订单状态。

## 验收对照（56 条）

所有下单、支付、取消、评价、修改、列表操作走真实 HTTP；任务通过 Spring bean 的生产任务方法调用。TC-094 是两个真实应用上下文共享同一 MySQL/Redis，汇总两个 SqlCounter；没有用两次调用一个实例替代多实例。
正向或回归用例原本已满足的规则保持绿，未伪造红灯；TC-063 的首次红灯证实 GET 仍改状态，随后仅修改为 POST 再执行其他规则的完整红灯。TC-105/106 的基线反向响应可能由缺少接口产生，TC-105 补充正向取消先红，最终在真实新增接口上验证归属和状态。

| 用例与预期 | 测试（文件、方法、参数数） | 修复前 | 最终 |
|---|---|---|---|
| TC-020 住客 A 给住客 B 已完成的客房订单写评价被拒 | `OrderWorkflowIT.tc020_cannotCommentAnotherUsersCompletedRoomOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:118`) | 红 1/1 | 1/1 绿 |
| TC-021 住客 A 给住客 B 已完成的餐饮订单写评价被拒 | `OrderWorkflowIT.tc021_cannotCommentAnotherUsersCompletedMealOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:124`) | 红 1/1 | 1/1 绿 |
| TC-022 住客给本人进行中（status=0）的客房订单写评价被拒 | `OrderWorkflowIT.tc022_cannotCommentOngoingRoomOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:130`) | 红 1/1 | 1/1 绿 |
| TC-023 住客给本人新订单状态（order_status=0）的餐饮订单写评价被拒 | `OrderWorkflowIT.tc023_cannotCommentNewMealOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:134`) | 红 1/1 | 1/1 绿 |
| TC-024 评分越界（-1、0、6、999）返回 400 且评分不落库 | `OrderWorkflowIT.tc024_invalidStarsAre400AndUnchanged` × 4 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:139`) | 红 4/4 | 4/4 绿 |
| TC-025 评分取边界有效值（1、5）时落库 | `OrderWorkflowIT.tc025_validStarBoundariesAreWritten` × 2 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:146`) | 基线已满足 2/2（回归） | 2/2 绿 |
| TC-026 评价内容 500 字时落库 | `OrderWorkflowIT.tc026_500CharacterCommentIsWritten` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:152`) | 基线已满足 1/1（回归） | 1/1 绿 |
| TC-027 评价内容 501 字时返回 400 且不落库 | `OrderWorkflowIT.tc027_501CharacterCommentIs400AndUnchanged` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:158`) | 红 1/1 | 1/1 绿 |
| TC-028 餐饮下单篡改单价和总价后按菜品表价格落库 | `OrderWorkflowIT.tc028_mealPricesComeFromDatabase` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:164`) | 红 1/1 | 1/1 绿 |
| TC-029 餐饮明细中 dishId 不存在时拒绝下单 | `OrderWorkflowIT.tc029_missingDishIsRejected` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:179`) | 红 1/1 | 1/1 绿 |
| TC-030 餐饮明细中菜品已软删除时拒绝下单 | `OrderWorkflowIT.tc030_deletedDishIsRejected` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:180`) | 红 1/1 | 1/1 绿 |
| TC-031 餐饮明细中菜品已下架时拒绝下单 | `OrderWorkflowIT.tc031_disabledDishIsRejected` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:181`) | 红 1/1 | 1/1 绿 |
| TC-063 用 GET 调用支付、取消返回 405 且订单状态不变 | `OrderWorkflowIT.tc063_getCannotChangeOrder` × 2 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:184`) | 首次红 2/2；仅 S14 修复后绿 | 2/2 绿 |
| TC-064 住客预订与已支付订单区间重叠被拒（部分重叠、完全包含、同日早入住） | `OrderWorkflowIT.tc064_paidOverlapIsRejected` × 3 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:195`) | 红 3/3 | 3/3 绿 |
| TC-065 住客预订与未支付但仍在 15 分钟支付期内的订单区间重叠被拒 | `OrderWorkflowIT.tc065_unpaidOverlapIsRejected` × 3 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:196`) | 红 3/3 | 3/3 绿 |
| TC-066 首尾相接的区间不算重叠 | `OrderWorkflowIT.tc066_exactTouchingIntervalsAreAllowed` × 2 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:198`) | 基线已满足 2/2（回归） | 2/2 绿 |
| TC-067 前台开当天入住的单，与当天稍后入住、房间仍空闲的已有订单重叠时被拒且房态保持空闲 | `OrderWorkflowIT.tc067_frontOverlapCannotChangeRoomOrWriteOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:204`) | 红 1/1 | 1/1 绿 |
| TC-068 20 个并发请求预订同一房间同一晚只成功一单 | `OrderWorkflowIT.tc068_20SimultaneousBookingsHaveOneWinnerInEachOf10Rounds` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:211`) | 红 1/1 | 1/1 绿 |
| TC-069 被超时任务取消的订单再支付被拒 | `OrderWorkflowIT.tc069_timeoutCancelledOrderCannotBePaid` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:223`) | 红 1/1 | 1/1 绿 |
| TC-070 住客主动取消的订单再支付被拒 | `OrderWorkflowIT.tc070_cancelledOrderCannotBePaid` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:228`) | 红 1/1 | 1/1 绿 |
| TC-071 已支付订单再次支付被拒 | `OrderWorkflowIT.tc071_paidOrderCannotBePaidAgain` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:232`) | 基线已满足 1/1（回归） | 1/1 绿 |
| TC-072 创建已满 16 分钟、尚未被超时任务取消的订单支付被拒 | `OrderWorkflowIT.tc072_paymentDeadlineIsCheckedWithoutScheduler` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:236`) | 红 1/1 | 1/1 绿 |
| TC-073 支付他人已支付的订单被拒且不再提示房间已被预订 | `OrderWorkflowIT.tc073_ownershipIsCheckedBeforePaymentStatus` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:240`) | 红 1/1 | 1/1 绿 |
| TC-074 支付不存在的订单返回明确原因 | `OrderWorkflowIT.tc074_missingOrderHasSpecificPaymentFailure` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:245`) | 红 1/1 | 1/1 绿 |
| TC-075 同一订单并发支付和取消 50 轮，不出现「已取消 + 已支付」 | `OrderWorkflowIT.tc075_concurrentPaymentAndCancellationNeverLeaveCancelledPaidIn50Rounds` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:249`) | 红 1/1 | 1/1 绿 |
| TC-076 取消本人未支付、未入住的订单 | `OrderWorkflowIT.tc076_unpaidFutureOrderCanBeCancelled` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:259`)<br/>`OrderWorkflowIT.tc076_paidFutureCancellationRefundsAndExcludesRevenue` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:456`) | 红 1/2 | 2/2 绿 |
| TC-077 终态订单（已完成、已取消）不能取消 | `OrderWorkflowIT.tc077_terminalRoomOrdersCannotBeCancelled` × 2 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:264`) | 红 2/2 | 2/2 绿 |
| TC-078 入住时间已过的已支付进行中订单不能取消（房间已占用、仍空闲） | `OrderWorkflowIT.tc078_startedOrderCannotBeCancelledRegardlessOfRoomStatus` × 2 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:271`) | 红 2/2 | 2/2 绿 |
| TC-079 取消他人的订单被拒（回归） | `OrderWorkflowIT.tc079_cannotCancelAnotherUsersRoomOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:276`) | 基线已满足 1/1（回归） | 1/1 绿 |
| TC-080 住客下单写订单失败时不留下入住人记录 | `OrderWorkflowIT.tc080_nightInsertFailureRollsBackGuestOrderAndNights` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:462`)<br/>`OrderWorkflowIT.tc080_orderWriteFailureRollsBackNewGuest` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:279`) | 红 2/2 | 2/2 绿 |
| TC-081 房号不存在时返回明确原因且不写任何记录 | `OrderWorkflowIT.tc081_missingRoomIsRejectedBeforeAnyWrite` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:286`) | 红 1/1 | 1/1 绿 |
| TC-082 入住日期为昨天时返回 400 | `OrderWorkflowIT.tc082_yesterdaysCheckinIs400` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:291`) | 红 1/1 | 1/1 绿 |
| TC-083 入住日期为今天时下单成功 | `OrderWorkflowIT.tc083_todayCanBeBookedAndAmountAndNightArePersisted` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:295`) | 红 1/1 | 1/1 绿 |
| TC-084 离店不晚于入住时返回 400（同一天、离店早于入住） | `OrderWorkflowIT.tc084_nonPositiveNightsAre400` × 2 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:307`) | 红 2/2 | 2/2 绿 |
| TC-085 入住 30 晚（上限）下单成功 | `OrderWorkflowIT.tc085_30NightsAreAllowed` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:311`) | 红 1/1 | 1/1 绿 |
| TC-086 入住 31 晚时返回 400 | `OrderWorkflowIT.tc086_31NightsAre400` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:315`) | 红 1/1 | 1/1 绿 |
| TC-087 30 晚订单计价只查一次价格日历 | `OrderWorkflowIT.tc087_pricing30NightsSelectsCalendarOnce` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:318`) | 红 1/1 | 1/1 绿 |
| TC-088 前台开单标明已收款时订单为已支付 | `OrderWorkflowIT.tc088_paidFrontOrderHasAmountAndTwoNights` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:322`)<br/>`OrderWorkflowIT.tc088_unpaidFrontOrderIsNotExpiredAfter15Minutes` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:326`)<br/>`OrderWorkflowIT.tc088_unpaidFrontOrderIsEnabledAndReleasedByTime` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:480`) | 红 3/3 | 3/3 绿 |
| TC-089 前台把有当前订单的占用房间改为空闲，订单被结束 | `OrderWorkflowIT.tc089_manualCheckoutEndsCurrentOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:335`) | 基线已满足 1/1（回归） | 1/1 绿 |
| TC-090 前台把查不到当前订单的占用房间改为空闲 | `OrderWorkflowIT.tc090_occupiedRoomWithoutOrderCanBecomeAvailable` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:339`)<br/>`OrderWorkflowIT.tc090_emptyRoomCanBeMarkedCleaningOrRepair` × 2 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:497`) | 红 3/3 | 3/3 绿 |
| TC-091 改不存在房间的房态返回明确原因 | `OrderWorkflowIT.tc091_invalidRoomStatusRejectedAtEveryWriteEntry` × 3 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:502`)<br/>`OrderWorkflowIT.tc091_missingRoomStatusChangeHasSpecificFailure` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:344`) | 红 4/4 | 4/4 绿 |
| TC-092 在住房间被标为维修中或清洁中后，入住推进任务不改回占用 | `OrderWorkflowIT.tc092_checkinTaskPreservesManualRoomStatus` × 2 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:348`)<br/>`OrderWorkflowIT.tc092_expiredOrderCannotBeEnabled` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:487`) | 红 3/3 | 3/3 绿 |
| TC-093 到期释放任务中途失败时房间和订单一起回滚 | `OrderWorkflowIT.tc093_successfulReleaseNeedsCleaningBeforeAvailable` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:491`)<br/>`OrderWorkflowIT.tc093_releaseFailureRollsBackRoomAndOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:355`) | 红 2/2 | 2/2 绿 |
| TC-094 两个实例同时触发释放任务，每张到期订单只处理一次 | `OrderWorkflowIT.tc094_twoInstancesReleaseEachOrderOnlyOnce` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:361`) | 红 1/1 | 1/1 绿 |
| TC-095 前台把订单改到已被占用的房间时段被拒 | `OrderWorkflowIT.tc095_moveToOverlappingRoomIsRejected` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:380`) | 红 1/1 | 1/1 绿 |
| TC-096 前台把订单改成离店早于入住返回 400 | `OrderWorkflowIT.tc096_invalidModificationDatesAre400` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:384`) | 红 1/1 | 1/1 绿 |
| TC-097 前台延长订单离店日后按新日期重新计价 | `OrderWorkflowIT.tc097_nightRewriteFailureRollsBackModificationAndOriginalNights` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:471`)<br/>`OrderWorkflowIT.tc097_extensionRepricesAndRewritesNights` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:388`) | 红 2/2 | 2/2 绿 |
| TC-098 前台把 2 晚订单换到空闲套房后按新房型重新计价 | `OrderWorkflowIT.tc098_moveToSuiteRepricesNights` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:397`) | 红 1/1 | 1/1 绿 |
| TC-099 修改已取消或已完成的订单被拒 | `OrderWorkflowIT.tc099_terminalOrderCannotBeModified` × 2 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:404`) | 红 2/2 | 2/2 绿 |
| TC-100 非纯数字房号在房间列表、房间详情、房态墙中原样返回字符串 | `OrderWorkflowIT.tc100_roomNumbersRemainStrings` × 6 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:409`) | 红 6/6 | 6/6 绿 |
| TC-104 餐厅取消新订单状态的餐饮订单 | `OrderWorkflowIT.tc104_restaurantCanCancelNewMealOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:419`) | 红 1/1 | 1/1 绿 |
| TC-105 住客不能取消本人已推进到状态 1 的餐饮订单 | `OrderWorkflowIT.tc105_userCannotCancelProgressedMealOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:423`)<br/>`OrderWorkflowIT.tc105_userCanCancelOwnNewMealOrderOnlyOnce` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:508`) | 红 1/2 | 2/2 绿 |
| TC-106 住客不能取消他人新订单状态的餐饮订单 | `OrderWorkflowIT.tc106_userCannotCancelOthersNewMealOrder` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:427`) | 基线已满足 1/1（回归） | 1/1 绿 |
| TC-117 列表接口的 SQL 条数不随返回行数增长 | `OrderWorkflowIT.tc117_listSqlCountDoesNotGrowWithRows` × 3 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:433`) | 红 3/3 | 3/3 绿 |
| TC-122 分页 pageSize=100 时正常返回 | `OrderWorkflowIT.tc122_pageSize100Works` × 1 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:447`) | 基线已满足 1/1（回归） | 1/1 绿 |
| TC-123 分页参数越界时返回 400 | `OrderWorkflowIT.tc123_paginationOutOfBoundsIs400` × 5 (`src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java:451`) | 红 5/5 | 5/5 绿 |

## 红绿运行记录

所有命令的工作目录均为 `C:/t/hsb/.nova/worktrees/W2`。Maven 路径 `C:/t/tools/apache-maven-3.9.9/bin/mvn.cmd`。
定向命令：`& 'C:/t/tools/apache-maven-3.9.9/bin/mvn.cmd' -B verify '-Dtest=NONE' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=OrderWorkflowIT'`。
完整命令：`& 'C:/t/tools/apache-maven-3.9.9/bin/mvn.cmd' -B clean verify`。
命令均等待实际退出码，未把启动成功当通过。Docker Desktop 运行中；沿用现有 MySQL 8.0 与 Redis 7 测试基建（Redis 版本沿用 W0 已记录环境差异）；JVM/数据库均 Asia/Shanghai，created_at 超时夹具使用 SQL `NOW() - INTERVAL ... MINUTE`。

| 阶段 | 退出码 | 真实结果 | 日志 |
|---|---|---|---|
| 首次红：仅加 56 用例与初始补充测试 | 1 | 83 次，72 失败、0 错误/跳过；并发首轮 17 单成功；GET 仍可改状态，POST 未映射 | `red-initial.log` |
| 第二次红：只把支付、取消改 POST | 1 | 83 次，69 失败、0 错误/跳过；并发首轮 20 单成功；取消后仍可支付、释放异常不回滚 | `red-after-s14.log`、`red-after-s14-results.json` |
| 缺口补充红：退款、逐晚写入/改期回滚、未收款前台任务、清洁/维修/无效房态、本人餐单取消 | 1 | 95 次，81 失败、0 错误/跳过；14 次已有行为满足判据 | `red-gaps.log`、`red-gaps-results.json` |
| 首轮定向绿：集中根因修复 | 0 | 95/95，0 失败/错误/跳过 | `green-first.log`、`green-first-tests.json` |
| 首次完整 clean verify | 1 | 单元 23 通过；集成 248 次、3 失败、0 错误/跳过；总 271 | `verify-first.log` |
| 修复完整回归后 clean verify | 0 | 单元 23/23、集成 248/248；总 271，0 失败/错误/跳过 | `verify-final.log`、`verify-final-summary.json` |

## 完整回归的三项来源与处理

1. `BusinessStatsIT.p4_statusWallShowsGuestUntilCheckoutDay`：原查询按天（含离店当天）显示入住人；首次 JOIN 只取此刻在住且房态为占用的订单，丢失原行为。已在生产 JOIN 中保留原按天入住人语义，另外按时刻取当前入住起止时间；原业务断言未放宽，方法编号改为 `tc007_statusWallShowsGuestUntilCheckoutDay`。
2. `ErrorResponseIT.c1_plainRuntimeMessageIsNoLongerSwallowed`：原夹具用客户端 totalAmount 不匹配触发金额校验失败，直接与定稿 S5「忽略客户端金额」冲突。经主会话核对 S5 与 TC-028 后授权迁移为真正非法的空明细；保留 HTTP 400、明确提示和两表零落库，额外断言 code=1；编号为 `tc127_emptyMealOrderReturnsSpecific400AndWritesNothing`。这是补充错误响应测试的契约迁移，不是恢复旧客户端金额校验。
3. `InfrastructureIT.infra_sqlCounterSeesStatementsRunOnTomcatThreads`：旧断言绑死 `select * from room` 文本，JOIN 仍被正常捕获却文本不匹配。经主会话授权改为 SELECT 查询 room 表的语义匹配；继续真实 HTTP、200/code=0、返回总数 10 的断言，编号为 `tc117_sqlCounterSeesRoomQueryRunOnTomcatThreads`。

## 实现与出处自查

- 下单共用事务内锁房、查重叠、计价并写逐晚明细：`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:80`。
- 共享重叠检查：`src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java:180`。
- 重叠按时刻左闭右开，排除自身，锁定当前读：`src/main/resources/mapper/OrderMapper.xml:86`。
- 支付在一条条件 UPDATE 中检查归属、进行中、未支付和数据库时钟期限：`src/main/resources/mapper/OrderMapper.xml:113`。
- 取消在一条条件 UPDATE 中处理状态和退款 pay_status=2：`src/main/resources/mapper/OrderMapper.xml:121`。
- 逐晚明细批量写入：`src/main/resources/mapper/OrderMapper.xml:75`。
- 同一分钟退房任务只能有一个实例抢到数据库锁记录：`src/main/resources/mapper/OrderMapper.xml:154`。
- 退房的房态与订单更新放在同一事务中：`src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java:31`。
- 房态墙单条 JOIN 查询，同时保留按天显示入住人、按时刻显示在住起止时间：`src/main/resources/mapper/RoomMapper.xml:152`。

以上 9 处出处逐项由当前文件行号机械核对；测试表中的方法行号同样从源码提取并与 JUnit 报告对应。

- B1：下单和前台开单共用房间行锁与当前读重叠检查，按 [checkin, checkout) 比较；改期锁源/目标房间并排除自身。
- B2/B3：支付、取消各自一条条件 UPDATE；支付期限用数据库时钟；取消已支付未来订单同时置退款状态 2，避免并发出现已取消 + 已支付。
- B4/B5/B10：住客/前台下单与修改都在事务里；两种 INSERT 都写 total_amount；只查一次价格日历；下单批量写逐晚明细，改期整单删除后重写，触发写入错误时全部回滚。已入住订单续住允许保留原历史入住日，仍检查离店先后、30 晚、冲突和状态。
- GAP-14：paid=true/false 控制前台支付状态；user_id 为 NULL 的前台单不被 15 分钟任务取消，并能按时入住和退房。
- S4/S5/S14：评价条件 UPDATE 约束归属与已完成；Bean Validation 约束星级 1–5、500 字；菜品价格与状态从数据库读取，服务端金额写主单和明细；支付和取消只开放 POST。
- B6–B9/B11/B13：空房与无当前订单均能改合法房态，不存在房间返回具体原因；入住推进不覆盖清洁/维修、不激活已过离店时刻订单；退房进入清洁中并和订单一起回滚；房号为字符串；餐厅/住客只可取消新餐单，住客须本人。
- P2/P5：订单总览、房间列表、房态墙使用固定查询条数；三个分页入口按 page≥1、1≤pageSize/limit≤100 返回 400。

## 改动文件

25 个代码/SQL/测试文件；范围外必需兼容改动仅 `BusinessServiceImpl` 的 RoomMapper 参数签名适配（增加可空 date 参数），未改经营统计计算。共享 StaffController 分页属于 TC-123 明确要求。三个旧测试的调整见上节。

```text
M	src/main/java/com/winniethepooh/hotelsystembackend/controller/OrderController.java
M	src/main/java/com/winniethepooh/hotelsystembackend/controller/RoomController.java
M	src/main/java/com/winniethepooh/hotelsystembackend/controller/StaffController.java
M	src/main/java/com/winniethepooh/hotelsystembackend/dto/CommentOrderDTO.java
M	src/main/java/com/winniethepooh/hotelsystembackend/dto/InsertRoomOrderDTO.java
M	src/main/java/com/winniethepooh/hotelsystembackend/mapper/FoodMapper.java
M	src/main/java/com/winniethepooh/hotelsystembackend/mapper/OrderMapper.java
M	src/main/java/com/winniethepooh/hotelsystembackend/mapper/RoomMapper.java
M	src/main/java/com/winniethepooh/hotelsystembackend/service/CustomTaskScheduler.java
M	src/main/java/com/winniethepooh/hotelsystembackend/service/OrderService.java
M	src/main/java/com/winniethepooh/hotelsystembackend/service/RoomService.java
M	src/main/java/com/winniethepooh/hotelsystembackend/service/impl/BusinessServiceImpl.java
M	src/main/java/com/winniethepooh/hotelsystembackend/service/impl/OrderServiceImpl.java
M	src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RestaurantServiceImpl.java
M	src/main/java/com/winniethepooh/hotelsystembackend/service/impl/RoomServiceImpl.java
M	src/main/java/com/winniethepooh/hotelsystembackend/vo/QueryRoomsVO.java
M	src/main/java/com/winniethepooh/hotelsystembackend/vo/RoomStatusWallVO.java
M	src/main/resources/db/schema.sql
M	src/main/resources/mapper/FoodMapper.xml
M	src/main/resources/mapper/OrderMapper.xml
M	src/main/resources/mapper/RoomMapper.xml
M	src/test/java/com/winniethepooh/hotelsystembackend/BusinessStatsIT.java
M	src/test/java/com/winniethepooh/hotelsystembackend/ErrorResponseIT.java
M	src/test/java/com/winniethepooh/hotelsystembackend/InfrastructureIT.java
A	src/test/java/com/winniethepooh/hotelsystembackend/OrderWorkflowIT.java
```

## 提交

```text
bccb035 W2/W3 TC-020~031 TC-063~100 TC-104~106 TC-117 TC-122~123: 修复订单一致性、计价、评价、房态与任务
```

实现者交回时全部提交留在本地，未 fetch/pull/push；随后主会话合入集成分支并完成复测（记录如下）。Git 行尾规范化只修正索引存储格式，没有在最终验证后改变源码内容。
过程证据与日志按派单保存于主工作区 `.nova/review-fixes/build/W2/`（不混入业务源码提交）。

## 独立验证与合入（主会话）

- 主会话亲自对 `bccb03563df6f59b2385b6f297170d3a0d426335` 执行完整 `clean verify`，真实退出码 0；单元 23/23、集成 248/248，总 271，失败/错误/跳过均 0。日志 `.nova/review-fixes/build/W2/verify-parent.log`，2026-09-30 21:29:25 完成。
- 已用 `--no-ff` 合入集成分支，合入提交 `e3f88c556b437fccc7e4ba394a403a2d8bd68392`（Git 已核实提交与父提交）。
- 主会话对合入提交 `e3f88c556b437fccc7e4ba394a403a2d8bd68392` 完成集成分支完整 `clean verify`，真实退出码 0、BUILD SUCCESS；单元 23/23、集成 248/248，总 271，失败/错误/跳过均 0。日志 `.nova/review-fixes/build/W2/verify-integration.log`，2026-09-30 21:31:34 完成。

## 遗留与交接

- W2/W3 没有未完成用例、已知阻塞失败或跳过测试。用户授权跳过的代码评审未伪称完成；独立完整验收、合入与集成复测已完成；W6 端到端由主会话接续。
- 新增 scheduler_task_lock 的建表语句已在 schema.sql；已有数据库部署本提交前需执行该建表脚本。退房整批共用一行任务锁，同一分钟仅运行一次，失败时事务回滚允许重试；源码 ponytail 注释说明批次扩大时改为逐单抢占提交。
- 依 GAP-12/17，退款只记状态；改期只更新金额，不对接补款/退款渠道。
- 代码地图与 M1–M7 文档一致性核对仍由主会话最终批次统一更新。


## 后续批次收口

W6 六条真实浏览器端到端已完成；M1–M7 与最终代码地图已更新，最终集成完整测试 285/285。历史库迁移与退款渠道等约定边界继续保留。
