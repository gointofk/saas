# DailyActivity 开发指引

## 适用范围

本文件适用于整个仓库。后续开发、修复、评审和测试均应遵循本指引；若子目录中出现更具体的 `AGENTS.md`，则子目录规则优先。

DailyActivity 是一个前后端分离的共享商品、多商户独立经营商城：客户侧负责选购、下单、付款申报和订单跟踪，商家侧负责客户、商品、收款、履约、佣金和内部成员管理。

## 仓库结构

- `backend/`：Spring Boot 后端，JDK 17、Spring MVC、Validation、Spring JDBC、MySQL。
- `frontend/`：Vue 3 + TypeScript + Vite 前端，同时承载客户侧和商家侧界面。
- `doc/prd_v1.md`：业务范围、角色权限、流程、状态和验收口径。
- `doc/tech_design.md`：架构、接口、数据、事务、安全和部署设计。
- `doc/init_mysql56.sql`：MySQL 5.6 空库的完整初始化脚本，不是增量迁移脚本。
- `README.md`：本地启动和验证入口。

## 开始工作前

1. 先阅读与任务相关的 PRD 和技术设计章节，再检查现有实现、测试和 SQL；不要仅凭页面表现推断业务规则。
2. 执行 `git status --short`，保留用户已有的未提交改动。只修改任务需要的文件，不顺手格式化或重写无关代码。
3. 修改接口时同时检查后端 Controller/DTO、前端 `src/lib/api.ts` 及调用方、错误结构和相关测试。
4. 修改数据模型或 SQL 时，先搜索所有表、字段、索引和状态值的引用，评估历史数据和兼容性。

规则发生冲突时，按 `doc/tech_design.md` 中声明的优先级处理：当前用户要求 > PRD 已确认业务规则（B）> PRD 实施建议（E）> Demo/参考 UI。发现文档、数据库和实现互相矛盾时，不要静默选择；在交付说明中指出差异，并在任务范围内同步必要文档。

## 后端约定

### 技术与结构

- 以 `backend/pom.xml` 中锁定的版本为准；当前基线为 Java 17 和 Spring Boot 3.5.6。没有明确任务时不要擅自升级框架或依赖。
- 项目是按业务包组织的模块化单体。业务包内部遵循 `api`、`application`、`domain`、`infrastructure` 分层。
- Controller 只处理 HTTP 映射、请求校验和当前身份获取；业务规则、权限编排和事务放在 Application Service；Repository 接口放在 `domain/port`；参数化 SQL 实现在 `infrastructure`。
- 使用构造器注入。不要引入字段注入，也不要让 Controller 直接访问 JDBC Repository。
- 持久化使用 Spring JDBC 和显式 SQL。除非需求明确改变技术路线，不引入 JPA/Hibernate、Spring Security、Redis、消息队列或服务端 Session。

### API、安全与错误

- 买家存于 user、商家成员存于 seller、平台管理员存于 admin，成员菜单存于 seller_permission。认证查询必须同时指定身份类型和 ID；三表可有同号 ID，审计按类型和 ID 关联，不能混用。
- 买家和成员固定归属一个商户，订单下单时固化 merchant_id。所有私有业务的查询、统计、导出、详情和写操作必须校验商户归属；根 Repository 显式传入商户范围。
- 商品、等级价、成本、库存、类目、COA 共用；公共管理要求商户授权与成员菜单同时满足。COA 绑定统一要求 certificates。库存流水按商户隔离。
- 平台超级管理员使用独立 platform 身份，不归属商户；商户、域名、品牌、主题、运费及公共授权由平台管理，不授予跨商户订单/客户/财务访问。商家成员不持有 merchant_management 特殊能力。运费来自 merchant.shipping_fee。
- 买家登录按唯一商户域名查找账号，邮箱在商户内唯一；客户请求同时校验域名、JWT 与持久账号归属。路径解析应与 MVC 路由一致，不能因分号参数等路径变体跳过校验。

- REST API 按身份位于 `/api/v1/customer/**`、`/api/v1/admin/**` 或 `/api/v1/platform/**`；已有签名文件接口沿用 `/api/v1/files/**`。客户、商家、平台 JWT 的 audience、用户类型和数据范围必须严格隔离。
- 使用现有 `AuthFilter`、`AuthSupport` 和 `AccountService` 校验身份、`authVersion`、账号状态、客户数据归属及商家菜单权限。前端隐藏按钮不能代替服务端鉴权。
- 写操作必须由事务、行锁、条件更新或业务唯一索引防止重复副作用；有并发覆盖风险的订单、商品等主表操作可用 `updated_at` 做乐观冲突检查。除有明确业务键的单次消费场景外，不使用通用 `Idempotency-Key` 或保存首次响应的幂等表。
- 业务错误使用 `ApiException` 和稳定的错误码；不要向客户端泄露堆栈、SQL、密钥或内部文件路径。Filter 内异常与 MVC 异常分别沿用现有响应方式。
- 请求与响应字段变更应保持兼容；若确需破坏性变更，必须同步前端、测试和技术文档并明确说明。

### 事务与业务数据

- 写操作在最外层 Application Service 上建立 `@Transactional` 边界；只读查询使用 `@Transactional(readOnly = true)`。避免依赖同类内部调用触发事务。
- 涉及订单、库存、付款、应收应付、佣金和事件的变更必须原子提交。并发敏感路径沿用行锁、条件更新或唯一索引保护。
- 金额使用 `BigDecimal`，明确精度和舍入规则；不要使用 `double`/`float`。已完成订单、付款和佣金等历史快照不得随主数据变化而重算。
- 时间持久化和 JSON 输出以 UTC 为基线；只有明确的业务日历逻辑（例如订单号日期）才使用指定业务时区。
- 分页从 1 开始，固定每页 10 条，沿用 `RequestValidation` 和 `PageResponse`，不要增加可变 page size。
- 业务主数据按现有规则软删除；订单、资金、事件、流水和快照不得物理删除。

### 数据库

- 兼容 MySQL 5.6：不要依赖更高版本才支持的 SQL、数据库视图、触发器、存储过程、外键约束或未实际执行的 `CHECK` 约束。
- 所有动态值必须使用参数绑定；表名、列名或排序字段如需动态拼接，必须使用服务端白名单。
- `doc/init_mysql56.sql` 只允许用于已确认的全新空库。未经用户明确授权，不执行初始化脚本，不清空或重建现有数据库。
- 若任务改变表结构，应同步维护初始化 SQL、Repository SQL、测试夹具和 `doc/tech_design.md`。项目当前没有增量迁移框架，不要伪造已上线迁移已被执行。

## 前端约定

- 使用 Vue 3 Composition API、TypeScript 严格模式和 Vite；以 `frontend/package.json` 的实际依赖为准。文档中规划但尚未安装的 Vue Router、Pinia、Vue I18n 等不得假定已经可用。
- API 请求集中复用或扩展 `frontend/src/lib/api.ts`，保持 `/api/v1` 前缀、Bearer Token、JSON/FormData 处理和 401 清理一致；请求未结束时禁用触发请求的按钮。
- 客户侧、商家侧和平台侧使用独立的认证存储键与授权上下文，不能串用 token 或在前端伪造权限。
- 业务金额、状态和权限结论以服务端响应为准，不在浏览器中维护可与后端分叉的业务事实。
- 新增交互应覆盖加载、空数据、校验失败、服务端错误、重复提交和窄屏场景。保持现有视觉语言；参考 UI 只作设计输入，不复制其模拟数据或浏览器端业务逻辑。
- 修改 API 类型或字段时，避免无约束的 `any`；优先补充明确的 TypeScript 类型并更新所有调用点。

## 文档维护

- 业务范围、角色、流程、状态或验收口径变化时更新 `doc/prd_v1.md`。
- 架构、接口、数据结构、事务、安全、配置或部署方式变化时更新 `doc/tech_design.md`。
- 仅为实现既有规则而做的内部重构通常无需改 PRD，但若文档已经与实际实现不符，应在同一任务内修正相关描述。
- 不要把“计划实现”“示例”或“参考 UI 行为”写成已经验证通过的事实。

## 构建与验证

后端测试依赖一个已经通过 `doc/init_mysql56.sql` 初始化的 MySQL 测试库，连接信息来自 `application-test.yml`。测试用例会在事务中创建自己的最小业务数据；不要依赖数据库中预存的 Demo 数据。

```bash
cd backend
mvn test
```

需要聚焦验证时可运行指定测试类：

```bash
cd backend
mvn -Dtest=PrdOrderLifecycleTest test
```

前端首次安装或锁文件未变化时优先使用确定性安装，然后执行完整类型检查和生产构建：

```bash
cd frontend
npm ci
npm run build
```

本地联调：

```bash
cd backend
mvn spring-boot:run
```

```bash
cd frontend
npm run dev
```

提交前至少运行与改动直接相关的测试；跨层功能应同时执行 `mvn test` 和 `npm run build`。若因数据库、密钥或环境缺失无法验证，明确记录未运行的命令和原因，不要声称测试已通过。

## 交付与安全边界

- 不提交 `target/`、`node_modules/`、`dist/`、`.DS_Store`、日志、运行时上传文件或其他生成物。
- 不在代码、文档、日志或回复中新增真实生产密码、JWT/签名密钥、个人信息或支付凭据。`application-live.yml` 中的占位符必须由部署配置替换。
- 不修改或删除 `backend/runtime/` 中已有上传文件，不将它们作为固定测试数据。
- 不执行破坏性数据库操作，不回滚用户改动，不创建提交或推送远端，除非用户明确要求。
- 完成任务时简要说明改动范围、关键设计决定、已执行的验证及仍存在的风险。
