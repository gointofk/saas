package com.dailyactivity.identity;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.identity.application.AddressService;
import com.dailyactivity.identity.application.CustomerService;
import com.dailyactivity.identity.application.MemberService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.PrdTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PRD C-01/C-09/C-10 身份、地址与成员边界")
class PrdIdentityBoundaryTest extends PrdDatabaseTest {
    @Autowired
    private AccountService accounts;

    @Autowired
    private AddressService addresses;

    @Autowired
    private CustomerService customers;

    @Autowired
    private MemberService members;

    @Test
    @DisplayName("不存在的客户邮箱不能登录")
    void customerLoginRejectsUnknownEmail() {
        assertThatThrownBy(() -> accounts.customerLogin(data.customer().merchantId(), "missing-" + data.customerEmail(), PrdTestFixture.CUSTOMER_PASSWORD))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("客户密码错误不能登录")
    void customerLoginRejectsWrongPassword() {
        assertThatThrownBy(() -> accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), "WrongPassword123!"))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("已停用客户不能登录，已有身份也立即失效")
    void disabledCustomerCannotLoginOrUseExistingPrincipal() {
        jdbc.update("UPDATE `user` SET active=0,auth_version=auth_version+1 WHERE id=?", data.customerId());

        assertThatThrownBy(() -> accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), PrdTestFixture.CUSTOMER_PASSWORD))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("UNAUTHORIZED");
        assertThatThrownBy(() -> accounts.assertValid(customer()))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("客户资料修改只更新姓名且不返回客户内部分类")
    void profileUpdateOnlyChangesName() {
        Map<String, Object> updated = accounts.updateCustomerProfile(customer(), "  Renamed Customer  ");

        assertThat(updated)
                .containsEntry("name", "Renamed Customer")
                .containsEntry("email", data.customerEmail())
                .doesNotContainKeys("priceTier", "customerType");
        assertThat(jdbc.queryForObject("SELECT email FROM `user` WHERE id=?", String.class, data.customerId()))
                .isEqualTo(data.customerEmail());
        assertThat(jdbc.queryForObject("SELECT price_tier FROM `user` WHERE id=?", String.class, data.customerId()))
                .isEqualTo("V1");
    }

    @Test
    @DisplayName("客户修改密码后旧密码失效，新密码可以登录")
    void passwordChangeInvalidatesOldPassword() {
        accounts.changeCustomerPassword(customer(), PrdTestFixture.CUSTOMER_PASSWORD, "NewCustomerPass123!", "NewCustomerPass123!");

        assertThatThrownBy(() -> accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), PrdTestFixture.CUSTOMER_PASSWORD))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("UNAUTHORIZED");
        assertThat(accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), "NewCustomerPass123!")).containsKeys("accessToken", "customer");
    }

    @Test
    @DisplayName("地址可以新增、切换默认地址，删除默认地址后自动回退")
    void addressDefaultAndDeleteLifecycle() {
        long secondId = ((Number) addresses.create(customer(), new AddressService.AddressRequest(
                "Second", "2 Test Street", null, "Shanghai", "Shanghai", "200001", "13900000000", false)).get("id")).longValue();

        addresses.makeDefault(customer(), secondId);
        assertThat(addresses.list(customer()))
                .anySatisfy(address -> assertThat(address).containsEntry("id", secondId).containsEntry("isDefault", true));

        addresses.remove(customer(), secondId);
        assertThat(addresses.list(customer()))
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("id", data.addressId())
                .containsEntry("isDefault", true);
    }

    @Test
    @DisplayName("客户不能修改其他客户的地址")
    void addressOwnershipIsEnforced() {
        jdbc.update("INSERT INTO customer_address(customer_id,recipient_name,street,city,region,postal_code,phone,is_default,created_by_user_id,updated_by_user_id) "
                        + "VALUES(?,?,?,?,?,?,?,0,?,?)",
                data.referrerId(), "Other", "Other Street", "Shanghai", "Shanghai", "200002", "13700000000", data.referrerId(), data.referrerId());
        long otherAddress = jdbc.queryForObject("SELECT id FROM customer_address WHERE customer_id=? AND street=?", Long.class, data.referrerId(), "Other Street");

        assertThatThrownBy(() -> addresses.update(customer(), otherAddress, new AddressService.AddressRequest(
                "Hijack", "Hijacked", null, "Shanghai", "Shanghai", "200003", "13600000000", false)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("创建客户生成八位编码并保存推荐人与佣金比例快照")
    void customerCreationStoresReferralConfiguration() {
        String email = "created-" + data.customerEmail();
        Map<String, Object> result = customers.create(seller(), new CustomerService.CustomerRequest(
                email, "Created Customer", "CreatedPass123!", "CreatedPass123!", "regular", "V2",
                data.referrerId(), new BigDecimal("0")));
        long id = ((Number) result.get("id")).longValue();

        Map<String, Object> row = customers.get(seller(), id);
        assertThat(row)
                .containsEntry("email", email)
                .containsEntry("referrerId", data.referrerId())
                .containsEntry("priceTier", "V2")
                .containsEntry("commissionRate", new BigDecimal("0.00"));
        assertThat((String) row.get("userCode")).matches("[0-9A-Z]{8}");
    }

    @Test
    @DisplayName("卖家编辑客户时填写的新密码会生效并使旧密码失效")
    void customerEditResetsPassword() {
        String replacement = "SellerResetPass123!";
        customers.update(seller(), data.customerId(), new CustomerService.CustomerRequest(
                data.customerEmail(), "Test Customer", replacement, replacement, "regular", "V1",
                data.referrerId(), new BigDecimal("5.00")));

        assertThatThrownBy(() -> accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), PrdTestFixture.CUSTOMER_PASSWORD))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("UNAUTHORIZED");
        assertThat(accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), replacement))
                .containsKeys("accessToken", "customer")
                .doesNotContainKey("password");
    }

    @Test
    @DisplayName("卖家编辑客户时两次密码不一致会拒绝并保持旧密码")
    void customerEditRejectsMismatchedPasswordConfirmation() {
        assertThatThrownBy(() -> customers.update(seller(), data.customerId(), new CustomerService.CustomerRequest(
                data.customerEmail(), "Test Customer", "SellerResetPass123!", "MismatchPass123!", "regular", "V1",
                data.referrerId(), new BigDecimal("5"))))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("PASSWORD_CONFIRMATION_MISMATCH");

        assertThat(accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), PrdTestFixture.CUSTOMER_PASSWORD))
                .containsKeys("accessToken", "customer");
    }

    @Test
    @DisplayName("后台客户可以打开其订单和直接推荐客户明细")
    void adminCustomerNestedDetailsRespectCustomerBoundary() {
        long id = orderId(createOrder("customer-nested-detail-1", 1));

        assertThat(orders.adminCustomerOrders(seller(), data.customerId(), null, null, null, null, null, 1).items())
                .anySatisfy(order -> assertThat(order).containsEntry("id", id).containsEntry("orderNo", order.get("orderNo")));
        assertThat(orders.adminCustomerOrderDetail(seller(), data.customerId(), id))
                .containsEntry("id", id)
                .containsKey("items");
        assertThat(customers.adminReferrals(seller(), data.referrerId(), 1).items())
                .anySatisfy(customer -> assertThat(customer).containsEntry("id", data.customerId()).containsEntry("referrerId", data.referrerId()));
    }

    @Test
    @DisplayName("推荐比例超过 100% 被拒绝")
    void customerCreationRejectsInvalidCommissionRate() {
        assertThatThrownBy(() -> customers.create(seller(), new CustomerService.CustomerRequest(
                "bad-rate-" + data.customerEmail(), "Bad Rate", "BadPass123!!", "BadPass123!!", "regular", "V1",
                data.referrerId(), new BigDecimal("100.01"))))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_COMMISSION_RATE");
    }

    @Test
    @DisplayName("客户等级 V5 和 V6 可以保存")
    void customerSupportsTierFiveAndSix() {
        for (String tier : List.of("V5", "V6")) {
            String email = tier.toLowerCase() + "-" + data.customerEmail();
            customers.create(seller(), new CustomerService.CustomerRequest(
                    email, "Tier " + tier, "TierCustomer123!", "TierCustomer123!", "regular", tier,
                    data.referrerId(), new BigDecimal("5")));
            assertThat(jdbc.queryForObject("SELECT price_tier FROM `user` WHERE email=?", String.class, email))
                    .isEqualTo(tier);
        }
    }

    @Test
    @DisplayName("最后一个拥有成员管理权限的商家不能被停用")
    void lastMemberManagerCannotBeDisabled() {
        // The shared test database may contain an application administrator.
        // Restrict this transaction to the fixture member so the invariant is
        // tested independently of pre-existing rows.
        jdbc.update("UPDATE seller SET active=0 WHERE 1=1 AND id<>?", data.sellerId());
        assertThatThrownBy(() -> members.status(seller(), data.sellerId(), false))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("LAST_MEMBER_MANAGER");
    }

    @Test
    @DisplayName("成员权限列表不允许重复")
    void memberPermissionValidationRejectsDuplicates() {
        assertThatThrownBy(() -> members.create(seller(), new MemberService.MemberRequest(
                "duplicate-" + data.sellerAccount(), "Duplicate", "MemberPass123!", "MemberPass123!", List.of("orders", "orders"))))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_MEMBER");
    }

    @Test
    @DisplayName("成员不能授予不存在的菜单权限")
    void memberPermissionValidationRejectsUnknownCodes() {
        assertThatThrownBy(() -> members.create(seller(), new MemberService.MemberRequest(
                "unknown-" + data.sellerAccount(), "Unknown", "MemberPass123!", "MemberPass123!", List.of("not-a-menu"))))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("FORBIDDEN");
    }
}
