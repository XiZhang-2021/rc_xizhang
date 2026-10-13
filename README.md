# API 通知系统：AI Coding 作业

本项目是一个内部 HTTP 通知服务的最小可行实现（MVP）。业务系统提交完整请求后，服务先把任务持久化到 MySQL，再异步调用供应商，并通过重试、定时补偿和供应商回调记录投递与处理结果。

项目对应三个场景：用户注册后通知广告系统、订阅付款后通知 CRM、购买商品后通知库存系统。业务系统不需要同步等待供应商返回业务结果；提交成功表示任务已保存，不表示供应商已完成处理。

## 提交内容与阅读顺序

| 内容 | 文件 | 对应作业要求 |
| --- | --- | --- |
| 项目概览 | 本 README | 问题理解、实现范围、运行入口 |
| 详细设计 | [docs/DESIGN.md](docs/DESIGN.md) | 系统边界、架构、MySQL 持久化、可靠性、失败处理、取舍与演进 |
| AI 使用说明 | [AI_USAGE.md](AI_USAGE.md) | AI 的帮助、未采纳的建议、作者作出的决策 |
| 运行与接口手册 | [rc_xizhang_coding/README.md](rc_xizhang_coding/README.md) | 环境配置、九个端点、请求示例和测试命令 |
| MVP 代码 | [rc_xizhang_coding](rc_xizhang_coding) | Controller、Service、Mapper、后台投递及测试 |
| 数据库建表 | [schema.sql](rc_xizhang_coding/src/main/resources/schema.sql) | 三张业务表的完整 DDL |

文档按 2026-10-13 工作区代码整理。仓库采用作业要求的 `rc_{your_nickname}` 命名形式，本地目录为 `rc_xizhang`；应用位于子目录 `rc_xizhang_coding`。

## 对问题的理解

供应商的地址、Header、Body 和可用性各不相同。让每个业务系统分别处理超时、重复请求和重试，会重复建设相同逻辑。本服务集中负责通知的接收、持久化和投递，供应商仍负责广告归因、Contact 状态更新或实际库存变更。

原题把三类业务作为示例，并未规定具体接口数量或供应商返回格式。本实现进一步采用作者明确提出的约定：

- 三类业务分别提供 Controller、Service 和 Mapper。
- POST 提交通知，GET 查询状态，POST 接收 callback；出站请求支持 GET/POST。
- 调用方提供完整 URL、method、headers、body 及幂等键 requestId。
- 供应商使用 retryable、repeatRequest、success 三字段响应，并在处理后回调。
- 使用 MySQL + MyBatis；保留 MVP 必需状态，去掉持久化重试计数、时间及锁字段。

这是基于约定协议的通知服务，尚不是可以直接对接任意第三方 API 的通用集成平台。

## 整体实现

| 场景 | Controller → Service → Mapper | 业务表 |
| --- | --- | --- |
| 广告注册 | AdvertisingController → AdvertisingService → AdvertisingMapper | advertising_notification_task |
| CRM 付款 | CrmController → CrmService → CrmMapper | crm_notification_task |
| 库存变更 | InventoryController → InventoryService → InventoryMapper | inventory_notification_task |

三条链路共用入库与回调基础逻辑、HTTP 客户端、线程池、退避重试和 MySQL 命名锁。每张业务表只有七个字段：`id`、`task_id`、`request_id`、`request_data`、`transfer_success`、`processed`、`delivery_status`。

处理流程：

1. 校验完整请求，生成 UUID taskId，提交 MySQL 事务。
2. 把任务交给后台线程，向调用方返回任务信息；新任务为 HTTP 202。
3. 后台获取该 taskId 的锁，发送请求，根据供应商响应更新投递状态。
4. 可重试失败在本轮等待 100/200/400ms，最多发送四次；耗尽后保留 PENDING。
5. 定时任务继续扫描可投递记录；供应商 callback 成功后仅将 processed 更新为 true。

`transferSuccess` 表示收到明确的转发成功响应，`processed` 表示收到处理成功回调。两者分开维护，允许回调比出站响应更早到达。

## 接口概览

| 业务 | POST 提交 | GET 查询 | POST callback |
| --- | --- | --- | --- |
| 广告 | `/api/advertising/tasks` | `/api/advertising/tasks/{taskId}` | `/api/advertising/callback` |
| CRM | `/api/crm/tasks` | `/api/crm/tasks/{taskId}` | `/api/crm/callback` |
| 库存 | `/api/inventory/tasks` | `/api/inventory/tasks/{taskId}` | `/api/inventory/callback` |

提交及查询需要 `X-Api-Key`，回调需要对应供应商的 `X-Callback-Key`。GET 查询不会触发发送；JSON 中的 `method` 决定出站 HTTP 方法。同一业务内相同 requestId 去重，不同业务独立去重。

## 本地运行

代码使用 Java 17、Spring Boot 4.1.1、MyBatis Starter 4.0.0 和 MySQL，版本以 [pom.xml](rc_xizhang_coding/pom.xml) 为准。

在仓库根目录执行：

```bash
cd rc_xizhang_coding
# 已有可用 MySQL 时可跳过；连接配置需与下方默认值一致。
docker compose up -d --wait
export NOTIFICATION_API_KEY='local-internal-key'
export SUPPLIER_CALLBACK_KEY='local-supplier-key'
mvn spring-boot:run
```

默认应用端口为 8080，数据库为 `localhost:3307/notification`，开发账号为 `notification / notification-local`。如果本机数据库地址不同，通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 修改。应用启动创建不存在的业务表，不自动创建数据库，也不删除原有表。

另开终端启动演示供应商：

```bash
cd rc_xizhang_coding
export SUPPLIER_CALLBACK_KEY='local-supplier-key'
python3 scripts/mock_supplier.py
```

演示供应商监听 9090，支持三个业务路径的 GET/POST 并回调对应接口。示例密钥和数据库密码仅用于本地演示。完整 curl 示例、IDEA 运行配置、已有 MySQL 接入方法及打包命令见[运行手册](rc_xizhang_coding/README.md)。

## 验证情况

2026-10-12 的已有测试报告记录 **48 项测试全部通过，0 失败、0 错误、0 跳过**：13 项单元测试及 35 项真实 MySQL 集成测试。本次文档整理核对了代码和该报告，没有重新执行测试。参数化测试按广告、CRM、库存三个业务展开计数。

已有验证还包括 IDEA 编译、Maven 打包，以及打包程序连接本地演示供应商完成三类业务各自的 GET/POST 转发和成功回调。实际数据库验证使用 MySQL 8.0；Compose 的默认镜像为 8.4，不将默认配置视为已经验证过的环境。

在应用目录执行 `mvn test` 可运行单元测试；完整测试还需配置专用 `notification_test` 数据库及 `MYSQL_TEST_URL`。**集成测试会清空测试库的三张业务表，不可指向业务库。** 重现步骤和覆盖边界见[详细设计中的测试说明](docs/DESIGN.md#9-测试与验证)。

## 主要取舍

- 持久化后再投递，内存队列不是任务的唯一存储位置；队列满或进程退出后可由数据库补偿。
- 采用允许重复的投递重试方式，依赖供应商幂等；不承诺 exactly-once 或最终业务成功。
- 三次重试限制单轮，不限制任务生命周期；没有总期限、熔断、限流或回调超时补偿。
- 仅扫描 PENDING 且两个成功标志均为 false 的记录，避免把已送达但未回调的任务反复发送。
- 复用已有 MySQL 做持久化和命名锁，暂不引入 Redis、消息队列或独立调度平台。

各项选择的原因、代价和演进触发条件见[详细设计](docs/DESIGN.md)；作者与 AI 的决策分工见 [AI 使用说明](AI_USAGE.md)。
