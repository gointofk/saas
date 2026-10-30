# DailyActivity

DailyActivity is a research-supply commerce workspace with three authenticated surfaces:

- Customer catalogue, cart, checkout, orders, payment submission, profile and addresses.
- Seller console for dashboard metrics, order verification/fulfilment, products, customers, finance and payment channels.
- Platform console for merchant provisioning, domains, branding, themes and shipping fees.

## Run locally

当前为全新系统 V1.8，买家、商家成员和平台管理员分别使用 user、seller、admin 表，商家权限使用 seller_permission。无历史数据迁移或旧客户端兼容要求。商品、价格、成本、库存、类目、COA、商品绑定及文件均归属单一商户；各商户在同一 SaaS 应用和数据库内逻辑隔离。平台超级管理员独立于商户，只负责开通和配置商户，不管理商品目录，也不访问商户订单、客户或资金。买家邮箱在商户内唯一，可在不同商户分别建号；商家与平台登录账号分别在 seller、admin 表内唯一，可跨身份同名。

全新空库由部署人员执行 [完整初始化 SQL](doc/init_mysql56.sql)，随后按 [平台账号引导 SQL](doc/bootstrap_platform.sql) 设置账号、姓名和 Argon2id 摘要并显式执行。没有默认密码。通过通用域名 `/platform/` 登录平台，开通商户及首个管理员；商家使用 `/admin/`，买家通过商户唯一域名访问 `/`。平台配置商户名称、logo 地址、CSS 主题和运费；商户成员按自己的菜单权限维护本商户目录。DNS、HTTPS 及保留 Host 的反向代理配置见技术设计 V1.8 部署补充。

初始化结构直接包含商户独立目录、阶梯价、订单优惠、审核、分阶段付款和多包履约。系统尚未部署，不存在旧业务库或迁移步骤；只需对新建空测试库执行当前完整初始化 SQL。应用和测试不自动初始化或升级数据库；测试在事务中创建最小业务数据，客户 HTTP 请求同时提供令牌和所属商户 Host。

卖家按 V1～V6 配置数量档，买家只看到自己适用的价格，详见 [数量阶梯价设计](doc/quantity_pricing_design.md)。付款请求显式提交本次金额及订单版本；包裹与付款记录通过各自接口维护，前后端和 SQL 使用同一版本发布。

This is a production-oriented JDBC application. Business data is read and written directly through MySQL JDBC; the API does not ship with demo accounts, schema auto-initialization, or a fallback data source.

Initialize an empty MySQL 5.6 database once:

```bash
mysql --protocol=tcp -h127.0.0.1 -P3306 -uroot -p123456 huanhuan < doc/init_mysql56.sql
```

The default profile is `dev`, and its JDBC connection is the local test database from `dev.txt`
(`huanhuan`, `root`, `123456`). Before starting the API, replace the RSA PEM and secret placeholders
in `backend/src/main/resources/application-dev.yml`. To configure merchant email registration, inject
a stable `REGISTRATION_SECRET` of at least 32 bytes into the backend environment; the same value must
be shared by all backend instances. Do not execute the initialization script against a database
containing existing business data.

```bash
cd backend
mvn spring-boot:run
```

Use `--spring.profiles.active=test` or `--spring.profiles.active=live` to select another environment.
Those profiles intentionally contain separate database, key and filesystem placeholders and must be
filled by deployment configuration before use. Apart from `REGISTRATION_SECRET`, the application does
not read database or business secrets from environment-variable fallbacks.

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

## 商城主题维护

买家主题位于 `frontend/src/styles/themes/`，文件名与商户配置的主题标识一致：

- `classic.css`：经典主题，同时作为其他主题共用的基础样式。
- `forest.css`：森林主题的差异样式。
- `editorial.css`：简约目录主题的差异样式。

修改主题时直接编辑对应文件，覆盖规则限定在 `[data-storefront-theme="主题标识"]` 下；修改 classic.css 会影响继承它的其他主题。主题由 `frontend/src/styles.css` 统一导入。新增主题时，添加同名 CSS 文件和导入，并同步平台表单选项及后端 StorefrontService 的主题白名单。
