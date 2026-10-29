# DailyActivity

DailyActivity is a research-supply commerce workspace with two authenticated surfaces:

- Customer catalogue, cart, checkout, orders, payment submission, profile and addresses.
- Seller console for dashboard metrics, order verification/fulfilment, products, customers, finance and payment channels.

## Run locally

数量阶梯价版本使用 [数量阶梯价升级 SQL](doc/migration_20261028_quantity_prices.sql)：已有库须先停写、备份并确认两张新表尚不存在，再人工执行脚本后切换新应用。脚本仅新增商品等级阶梯和订单阶梯快照表，不回填或改写历史商品及订单价格；未配置阶梯的等级、无阶梯快照的历史订单继续使用原固定价。应用和测试不自动升级，测试库同样需要对应结构。卖家按 V1～V6 分别配置数量档，买家只看到自己适用的价格；详细规则见 [数量阶梯价设计](doc/quantity_pricing_design.md)。

多商户版本 V1.7：商品、等级价、成本、库存、类目和 COA 共用，其他业务按买家/成员所属商户隔离。指定商户的指定管理员负责开通商户和授予公共管理菜单，不自动获得跨商户业务访问权；登录账号和邮箱仍全局唯一，订单编号格式保持不变。

已有 V1.6 数据库部署前必须人工执行 [多商户迁移 SQL](doc/migration_20261027_merchants.sql)。先停写、备份；本次脚本已按用户确认在开头设置 `@migration_manager_id = 4233`、`@migration_shipping_fee = 0`，其他环境使用前须核对并修改参数。整个文件须在同一个 mysql 会话执行，执行工具必须遇错停止，不能开启“出错继续”；前置校验失败只会令当前语句报错。DDL 隐式提交，勿用 `--force`，勿盲目重复执行。全部历史私有业务归默认商户，旧账号重新登录。应用及测试不会自动迁移。

全新空库初始化后，使用 [首个商户开通 SQL](doc/bootstrap_merchant.sql)，显式提供管理员账号及 Argon2id 摘要等参数；没有默认密码。运费改存 `merchant.shipping_fee`，由具有商户管理能力的账号在商户管理页面维护，旧 `app.store.shipping-fee` 不再使用。测试库需要 V1.7 结构，集成测试在事务中建立自己的商户和最小业务数据。

已有数据库使用新增优惠功能前，需要停机、备份并人工执行一次 [订单优惠升级 SQL](doc/migration_20261002_order_discount.sql)，前提是 `sales_order.discount_amount` 尚不存在。历史订单优惠额默认 0，历史金额不变；不要在已有库重新执行全量初始化脚本。应用和测试不会自动升级表结构。

分阶段付款使用 [分阶段付款升级 SQL](doc/migration_20261007_staged_payment.sql)。现有库先停写、备份并人工核对脚本中的只读检查结果，全部为空后才执行变更；应用和测试不自动迁移。新前后端必须一起发布，付款请求的本次金额和订单版本必填。已申报付款不阻止待审核订单修改正品和金额，原申报快照保留；逐笔核实后累计付清。测试库也需要对应结构。

多包履约使用 [订单包裹迁移 SQL](doc/migration_20261008_order_parcels.sql)。先停写并备份，检查源数据；历史已履约订单迁移为一个整单包裹，核对数量、运单与事件附件后删除 `sales_order.tracking_no`、`carrier` 及旧索引。脚本末尾 `migration_errors` 与 `legacy_columns_remaining` 必须均为 0；异常时禁止启动新版，先人工核对。应用和测试不自动迁移，买卖两端与后端需一起发布。新单和历史单统一读取包裹，不保留旧物流字段或历史来源标记；已有库不能重新初始化。测试库也必须有包裹结构。详见技术设计 11.6。

This is a production-oriented JDBC application. Business data is read and written directly through MySQL JDBC; the API does not ship with demo accounts, schema auto-initialization, or a fallback data source.

Initialize an empty MySQL 5.6 database once:

```bash
mysql --protocol=tcp -h127.0.0.1 -P3306 -uroot -p123456 huanhuan < doc/init_mysql56.sql
```

The default profile is `dev`, and its JDBC connection is the local test database from `dev.txt`
(`huanhuan`, `root`, `123456`). Before starting the API, replace the RSA PEM and secret placeholders
in `backend/src/main/resources/application-dev.yml`. Do not execute the initialization script against
a database containing existing business data.

```bash
cd backend
mvn spring-boot:run
```

Use `--spring.profiles.active=test` or `--spring.profiles.active=live` to select another environment.
Those profiles intentionally contain separate database, key and filesystem placeholders and must be
filled by deployment configuration before use. The application does not read database or business
secrets from environment-variable fallbacks.

```bash
cd backend
mvn spring-boot:run
```

Start the Vue workspace in another terminal:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173/`.

## Verification

```bash
cd backend && mvn test
cd frontend && npm run build
```

The API uses `/api/v1/customer/*` and `/api/v1/admin/*`, signed bearer tokens, separate customer/seller audiences, account authentication versions for immediate invalidation, fixed ten-item pagination, transactional row locks, one-time checkout quotes, order/product `updated_at` conflict checks, decimal money arithmetic, and explicit fulfilment transitions. Action buttons stay disabled while their API request is pending. The Vue application is a browser client only; all business facts come from the API and MySQL.
