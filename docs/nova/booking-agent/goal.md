---
feature: booking-agent
status: approved
---

# 目标：为住客预订助手出一份可直接拆 Story 开发的技术方案

## 要达成什么

- G1（核心）：方案能支撑住客经对话完成查房、询价、预订、支付、取消、查订单、点餐的闭环（PRD 场景 S-01～S-07、用户故事 US-01～US-08）。判定：每条用户故事都能在方案里找到对应的接口、工具和流程设计；按方案开发后，PRD 附录的演示脚本能走完。
- G2（核心）：写操作安全可控——模型只能提议、用户确认后服务端执行，身份与归属由代码强制，确认幂等。判定：方案写清待确认动作的生成、校验、执行和失效规则，以及幂等表和工具层鉴权；对应的测试要点覆盖「未确认不下单」「越权为 0」「重复确认只有一单」「价格变动拒绝」。
- G3（顺带）：可测试、可稳定演示。判定：方案给出 LlmClient 抽象与假模型实现，集成测试和 Playwright 端到端测试不依赖真实模型；未配置模型 key 时其他功能不受影响。
- G4（顺带）：模型接入方式正确。判定：方案按 Responses API、store=false、Redis 保存完整输入项（含加密推理项）实现多轮工具循环，并写清 openai-java 的接入点和配置项。

## 范围

- 包括：后端 agent 模块（对话接口、SSE、工具层、待确认动作、会话存储、限流、幂等表）、模型接入（openai-java + 假模型）、前端静态页聊天面板与确认卡片、测试方案要点（集成、端到端、真实模型评测）、配置与上线说明。
- 不包括：真实支付渠道；语音、多语言；经理/前台/餐厅角色的 agent；替他人预订；推荐算法；把现有订单接口全部改造为幂等（只为确认动作建幂等，普通下单接口的幂等留作后续）。

## 产出物

一份技术方案 docs/nova/booking-agent/design.md，给后续拆 Story（nova:stories）和开发使用。

## 约束

- 技术选型已定：Spring Boot 3.3 + MyBatis + MySQL + Redis（现有）；OpenAI gpt-6-luna；官方 com.openai:openai-java；Responses API，store=false；前端为现有 src/main/resources/static 原生页面。
- PRD 待决策 D1～D4 已定（见素材「决策与补充」）。
- 复用现有代码与约定：鉴权（LoginFilter、RoleCheckAspect、BaseContext）、订单与计价服务、登录限流的 Redis 计数方式、测试基建（Testcontainers、Fixtures 别名、Playwright）。不改动已有 285 个测试的行为。
- 定位为简历与演示项目，不做生产级的多实例会话一致性、审计留存等。

## 素材

- 需求：飞书 PRD https://mcn7m001m9qm.feishu.cn/wiki/Nnzswna30iMWMrkwuaRcVp9enld（本地副本 .nova/booking-agent/design/inputs/PRD.md）
- 决策与补充：.nova/booking-agent/design/inputs/决策与补充.md
- 代码地图：docs/codemap/hotelsystembackend/（提交 79e476e，之后源码无变化）
- 程序根：C:\t\hsb（master，4c639b7）
- 预订一致性设计：docs/booking-consistency.md
