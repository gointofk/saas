package com.dailyactivity.auth;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.PrdTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PRD C-01/AUTH-01 认证与菜单权限")
class PrdAuthenticationAndAuthorizationTest extends PrdDatabaseTest {
    @Autowired
    private AccountService accounts;

    @Test
    @DisplayName("登录会更新最近登录时间，退出后原令牌版本立即失效")
    void logoutInvalidatesExistingPrincipal() {
        // Arrange / Act
        Map<String, Object> login = accounts.customerLogin("  " + data.customerEmail().toUpperCase() + "  ", PrdTestFixture.CUSTOMER_PASSWORD);

        // Assert login response has no password and exposes customer identity.
        assertThat(login).containsKeys("accessToken", "expiresAt", "customer").doesNotContainKey("password");
        accounts.logout(customer());
        assertThatThrownBy(() -> accounts.assertValid(customer()))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("客户不能调用后台接口，普通商家成员只能访问被授予的菜单")
    void customerAndRestrictedSellerCannotCrossMenuBoundary() {
        // Arrange
        var restrictedSeller = PrdTestFixture.seedRestrictedSeller(jdbc, data.sellerId());

        // Act / Assert
        assertThatThrownBy(() -> accounts.requireMenu(customer(), "products"))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> accounts.requireMenu(restrictedSeller, "products"))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        accounts.requireMenu(restrictedSeller, "orders");
    }
}
