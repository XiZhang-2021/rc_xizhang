# API 通知服务 MVP

提交文档入口：[仓库 README](../README.md) · [详细设计](../docs/DESIGN.md) · [AI 使用说明](../AI_USAGE.md)。本文侧重运行、配置和接口操作。

Java 17 + Spring Boot 4.1.1 + MyBatis 4.0 + MySQL。实现完整请求持久化、requestId 幂等、后台转发、三次退避重试、供应商回调、定时补偿和任务查询。广告、CRM、库存各使用一张七字段表，没有持久化重试次数、时间字段或锁字段。

## 项目结构

Java 类按职责分包，每个 DTO 和状态枚举独立成文件：

```text
src/main/java/xizhang/rc_xizhang_coding/
├── RcXiZhangCodingApplication.java
├── controller/   # HTTP 接口：AdvertisingController、CrmController、InventoryController
├── service/      # 三个业务 Service、共用 AbstractTaskService、TaskDispatcher、DeliveryWorker
├── mapper/       # 三个业务 Mapper；TaskMapper 仅定义公共方法，不注册 Bean
├── entity/       # 数据库实体：NotificationTask
├── dto/          # SubmitRequest、CallbackRequest、RequestData、TaskView、Submission
├── enums/        # 状态枚举：DeliveryStatus
├── config/       # 配置绑定与线程池：NotificationProperties、DeliveryConfiguration
├── client/       # 供应商 HTTP 调用：SupplierClient
├── job/          # 定时补偿：CompensationJob
├── lock/         # MySQL 分布式锁：MysqlTaskLock
├── filter/       # 接口鉴权过滤器：ApiKeyFilter
├── validation/   # 请求与回调校验：RequestPolicy
└── exception/    # 统一异常响应：ApiErrors

src/main/resources/
├── application.properties
└── schema.sql

src/test/java/xizhang/rc_xizhang_coding/
├── RcXizhangCodingApplicationTests.java  # MySQL 集成测试
├── service/                            # 投递单元测试
├── client/                             # 供应商响应单元测试
├── validation/                         # 请求校验单元测试
└── support/                            # 测试数据工具
```

启动类位于根包，Spring 组件、配置属性和 MyBatis Mapper 均从根包下扫描。

## 三条业务调用链

| 场景 | Controller | Service | Mapper | MySQL 表 |
| --- | --- | --- | --- | --- |
| 用户注册后通知广告系统 | AdvertisingController | AdvertisingService | AdvertisingMapper | advertising_notification_task |
| 订阅付款后通知 CRM 更改 Contact | CrmController | CrmService | CrmMapper | crm_notification_task |
| 购买后通知库存系统 | InventoryController | InventoryService | InventoryMapper | inventory_notification_task |

三个业务 Service 分别注入自己的 Mapper。`AbstractTaskService` 复用入库、幂等、查询和回调逻辑；发送线程、重试、锁和定时任务复用同一套实现，并显式传递对应业务的 Mapper。只有三个具体 Mapper 带 `@Mapper`，公共接口不注册 Bean。

本版继续采用“调用方提供完整请求”的约定：三个入口都接收 requestId、url、method、headers、body。PDF 没有指定三家供应商的实际字段协议，因此不虚构 Contact、订单或库存字段映射，也不在本服务扣库存或判断付款是否成功。

## 启动

在本目录执行，准备 JDK 17、Maven 和 Docker：

```bash
docker compose up -d --wait
export NOTIFICATION_API_KEY='local-internal-key'
export SUPPLIER_CALLBACK_KEY='local-supplier-key'
mvn spring-boot:run
```

本机 MySQL 已运行时可跳过 `docker compose up`。默认应用端口 8080、数据库端口 3307、数据库名 notification。应用启动执行 `src/main/resources/schema.sql`，只在表不存在时创建，不清理已有记录。

Compose 默认使用 MySQL 8.4；2026-10-12 的集成测试实际使用 MySQL 8.0 镜像。如果已有该镜像，也可使用：

```bash
MYSQL_IMAGE=mysql:8.0 docker compose up -d --wait
```

上述固定数据库密码和示例密钥仅用于本机开发；部署时从环境变量提供。开发数据库只绑定本机 127.0.0.1。停止数据库使用 `docker compose stop`，不会删除数据卷。

IDEA 直接运行 `RcXiZhangCodingApplication` 时，也需在运行配置中设置 `NOTIFICATION_API_KEY` 和 `SUPPLIER_CALLBACK_KEY`。本地验证使用 Maven 3.9.10。若 IDEA 无法识别依赖，可重新导入 Maven；使用 Lombok 时需开启注解处理。

## 本地演示

在第二个终端启动示例供应商（Python 3 标准库，无需安装包）：

```bash
export SUPPLIER_CALLBACK_KEY='local-supplier-key'
python3 scripts/mock_supplier.py
```

示例供应商只用于演示：收到 GET 或 POST 后返回三字段结果，并回调本服务；重复请求会再次回调。其幂等记录在内存中，重启即丢失；示例自身不实现可靠回调重试。

提交广告注册通知（其出站请求为 POST）：

```bash
curl -i http://localhost:8080/api/advertising/tasks \
  -H 'X-Api-Key: local-internal-key' \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId": "advertising:register-001",
    "url": "http://localhost:9090/advertising/notify",
    "method": "POST",
    "headers": {"Content-Type": "application/json"},
    "body": "{\"userId\":\"u-001\",\"event\":\"registered\"}"
  }'
```

新任务返回 HTTP 202：

```json
{
  "taskId": "生成的 UUID",
  "requestId": "advertising:register-001",
  "transferSuccess": false,
  "processed": false,
  "deliveryStatus": "PENDING"
}
```

将响应中的 taskId 用于查询：

```bash
curl http://localhost:8080/api/advertising/tasks/替换为taskId \
  -H 'X-Api-Key: local-internal-key'
```

供应商响应和回调均完成后，两个标志均为 true，状态为 SUCCESS。查询不返回原始 Header 和 Body，避免暴露供应商认证信息。

同一 requestId、相同请求重复提交返回 HTTP 200 和原 taskId；相同 requestId 搭配不同内容返回 HTTP 409。requestId 在同一业务表内唯一，支持 1..128 个 ASCII 字母、数字及 `._:-`，区分大小写。

## 接口

每个业务 Controller 提供三个端点，共九个端点：

| 业务 | POST 提交通知 | GET 查询状态 | POST 供应商回调 |
| --- | --- | --- | --- |
| 广告注册 | /api/advertising/tasks | /api/advertising/tasks/{taskId} | /api/advertising/callback |
| CRM 付款 | /api/crm/tasks | /api/crm/tasks/{taskId} | /api/crm/callback |
| 库存变更 | /api/inventory/tasks | /api/inventory/tasks/{taskId} | /api/inventory/callback |

提交和查询使用 `X-Api-Key`，回调使用 `X-Callback-Key`。GET 查询没有发送副作用；出站使用 GET 还是 POST，由提交 JSON 中的 `method` 决定。原通用 `/api/tasks`、`/api/callbacks` 不再作为业务入口。

同一 requestId 在同一业务内去重，不同业务可以使用同一个 requestId，分别生成任务。查询和回调只访问该业务的表，跨业务 taskId 返回 404。若同一供应商在所有业务间全局去重，调用方需要提供带业务前缀的 requestId（例如 `advertising:register-001`）。

回调示例：

```bash
curl -i http://localhost:8080/api/advertising/callback \
  -H 'X-Callback-Key: local-supplier-key' \
  -H 'Content-Type: application/json' \
  -d '{"taskId":"替换为taskId","requestId":"advertising:register-001","success":true}'
```

成功持久化后返回 HTTP 204。未知任务返回 404，requestId 不匹配返回 409，错误密钥返回 401。失败回调不把已成功的 processed 改回 false，重复成功回调安全。供应商应在未收到确认时重试回调。

## 另外两类通知示例

CRM 通知示例使用 GET 调用供应商，业务参数放在完整 URL 中：

```bash
curl -i http://localhost:8080/api/crm/tasks \
  -H 'X-Api-Key: local-internal-key' -H 'Content-Type: application/json' \
  -d '{"requestId":"crm:payment-001","url":"http://localhost:9090/crm/notify?contactId=contact-001&status=paid","method":"GET","headers":{},"body":""}'
```

库存通知示例使用 POST 和 XML Body：

```bash
curl -i http://localhost:8080/api/inventory/tasks \
  -H 'X-Api-Key: local-internal-key' -H 'Content-Type: application/json' \
  -d '{"requestId":"inventory:order-001","url":"http://localhost:9090/inventory/notify","method":"POST","headers":{"Content-Type":"application/xml"},"body":"<purchase><sku>sku-001</sku><quantity>1</quantity></purchase>"}'
```

三个入口都支持向供应商发送 GET 或 POST。上述供应商字段仅是本地演示数据，接入真实供应商时替换 URL、Headers 和 Body；供应商分别配置对应业务的 callback 地址。示例脚本按 `/advertising/notify`、`/crm/notify`、`/inventory/notify` 路径自动回调对应业务，`CALLBACK_BASE_URL` 可修改服务地址，`SUPPLIER_PORT` 可修改示例监听端口。

## 请求存储与还原

| 列 | 类型 | 用途 |
| --- | --- | --- |
| id | BIGINT UNSIGNED | 自增主键 |
| task_id | CHAR(36) UNIQUE | UUID 任务标识 |
| request_id | VARCHAR(128) UNIQUE | 调用方幂等键 |
| request_data | JSON | URL、method、headers、原始文本 body |
| transfer_success | BOOLEAN DEFAULT FALSE | 明确转发成功 |
| processed | BOOLEAN DEFAULT FALSE | 回调处理成功 |
| delivery_status | VARCHAR(16) DEFAULT PENDING | PENDING / SUCCESS / DUPLICATE / FAILED |

三张表使用上面完全相同的七字段结构，完整建表语句见 `src/main/resources/schema.sql`。每个具体 Mapper 的 SQL 固定访问自己的表，不从 HTTP 参数拼接表名。

原 `notification_task` 表保留，不删除或自动迁移；旧记录没有业务类别，不能可靠判断应该进入哪张表。新版本只扫描三张业务表。请求通过 MyBatis 落库并提交后才触发发送。并发去重依赖数据库唯一约束，不采用“先查后插”作为唯一保证。

Body 按原文本和 Content-Type 发送，支持 JSON、XML、表单等文本；默认 UTF-8，最大 1 MiB。Header 名称统一小写用于重复请求比较，Body 按文本精确比较，不对 JSON 业务内容作语义归一化。

服务添加 `X-Task-Id` 和 `X-Request-Id`，调用方不可自行设置它们。Host、Content-Length、Connection 等传输控制 Header 也不接受。供应商需预先同意这两个元数据 Header、三字段响应及回调接口。

## 投递与补偿规则

| retryable / repeatRequest / success | 动作 |
| --- | --- |
| false / false / true，且 HTTP 2xx | transfer_success=true，SUCCESS |
| true / false / false | 本轮重试，耗尽后仍 PENDING |
| false / true / false | DUPLICATE，不记为发送成功，不再转发 |
| false / false / false | FAILED，不再转发 |
| 连接异常、超时 | 本轮重试，耗尽后仍 PENDING |
| 字段缺失、不是布尔值、组合矛盾、无法解析等 | FAILED，不再转发 |

HTTP 错误却声称 success=true 视为协议错误。HTTP 状态本身不替代供应商字段；例如没有有效三字段的 503 按协议错误处理。自动跳转关闭，避免重定向绕过目标白名单。

每轮首次发送后最多三次重试，依次等待 100/200/400ms。计数仅在内存中；定时 Job 默认每轮完成后等待 30 秒，再分页扫描 PENDING 且两个成功标志均为 false 的任务。扫描开始记录本轮最大 ID，避免持续新请求使扫描永不结束。

短期重试结束不会形成永久失败状态；持续可重试失败会在后续 Job 中开始新一轮，不设生命周期总次数上限。已送达未回调、重复响应和明确不可重试失败均不会被 Job 再次发送。MVP 不做回调超时和对账。

## 分布式锁与并发

使用 MySQL `GET_LOCK`，每个 taskId 一把锁，多个应用实例必须连接同一个 MySQL 写节点。一个 JDBC 连接持有本轮锁，网络调用期间不持有数据库事务或行锁。正常结束显式 RELEASE_LOCK；连接终止由 MySQL 释放锁。释放异常时关闭物理连接，避免把可能持锁的连接交还连接池。

参考：[MySQL 命名锁文档](https://dev.mysql.com/doc/refman/8.4/en/locking-functions.html)。该锁只在单个 MySQL 服务器上协调，不提供跨独立数据库节点的全局锁，也不能替代供应商 requestId 幂等。

每次发送和写结果前检查锁归属。回调不等待出站锁，只更新 processed，出站线程只更新投递字段。即使先收到成功回调，再收到出站响应，也不会将 processed 覆盖回 false。

即时投递使用最多 4 个线程和有界队列，队列满时保留数据库任务交给 Job。Job 依次扫描三个业务 Mapper，顺序处理各表批次；默认数据库池为 12 个连接，为持锁连接、业务读写和回调留出容量。不增加消息队列或 Redis。

## 配置

| 环境变量 / properties | 默认值 | 说明 |
| --- | --- | --- |
| NOTIFICATION_API_KEY | 必填 | 内部提交与查询接口密钥 |
| SUPPLIER_CALLBACK_KEY | 必填 | demo 供应商回调密钥 |
| SUPPLIER_ORIGIN | http://localhost:9090 | 允许的协议、主机和端口，不包含路径 |
| DB_URL | 本地 3307/notification | MySQL JDBC URL |
| DB_USERNAME / DB_PASSWORD | notification / notification-local | 本机开发数据库凭据 |
| SERVER_PORT | 8080 | 服务端口 |
| notification.http-timeout | 3s | 整个 HTTP 交换的等待上限 |
| notification.scan-delay-ms | 30000 | Job 两轮之间的间隔 |
| notification.batch-size | 100 | 每次查询批量 |
| notification.workers | 4 | 即时投递线程数，范围 1..4 |
| notification.queue-capacity | 100 | 即时投递队列容量 |

接入更多供应商可配置：

```properties
notification.suppliers.crm.origin=https://crm.example.com
notification.suppliers.crm.callback-key=${CRM_CALLBACK_KEY}
```

目标按 origin 精确匹配；回调根据任务原始 URL 找到对应供应商的密钥。此 MVP 以 origin 区分供应商，不支持同一 origin 下不同租户的独立凭据。白名单由部署方控制，只添加可信供应商。生产环境应使用 HTTPS；认证 Header 存于数据库中，查询接口不返回它们，日志不打印请求或供应商响应正文。

MyBatis 4.0 与 Spring Boot 4 / Java 17 的版本匹配依据：[MyBatis 官方兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。

## 测试

不启动数据库也可执行单元测试：

```bash
mvn test
```

真实 MySQL 集成测试需单独数据库 `notification_test`，测试会清空其中的 advertising_notification_task、crm_notification_task、inventory_notification_task，不能使用业务数据库：

```bash
docker compose exec -T mysql mysql -uroot -proot-local \
  -e 'CREATE DATABASE IF NOT EXISTS notification_test; GRANT ALL ON notification_test.* TO "notification"@"%";'
MYSQL_TEST_URL='jdbc:mysql://localhost:3307/notification_test?connectTimeout=3000&socketTimeout=10000' mvn test
```

没有 MYSQL_TEST_URL 时，MySQL 集成测试明确跳过。测试中的模拟供应商由 Java 测试进程启动，无需额外运行 Python 示例。

2026-10-12 的已有报告记录 48 项测试全部通过（13 项单元测试、35 项真实 MySQL 集成测试，0 失败、0 错误、0 跳过）。2026-10-13 文档整理核对了该报告，未重新执行测试。三个业务均覆盖 POST 提交、GET 查询、出站 GET/POST、独立回调和跨业务隔离，并覆盖并发幂等与冲突、响应组合、四次发送及退避间隔、回调认证与单调更新、回调先到达、锁互斥及异常释放、定时补偿分页、原始 XML Body 还原和目标限制。

打包：

```bash
mvn package
java -jar target/rc_xizhang_coding-0.0.1-SNAPSHOT.jar
```

运行 jar 前同样需要配置两个密钥并启动数据库。

## AI 使用说明

完整说明见仓库根目录的 [AI_USAGE.md](../AI_USAGE.md)，其中区分作者明确提出的要求、未采纳的 AI 建议和 AI 作出的实现选择，并记录 MVP 的能力边界。
