package com.dailyactivity.auth;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.PrdTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

@AutoConfigureMockMvc
@DisplayName("PRD C-01 HTTP 认证与资源边界")
class PrdHttpAuthorizationTest extends PrdDatabaseTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accounts;

    @Test
    @DisplayName("缺少 Bearer 令牌的客户接口返回 401")
    void missingBearerTokenIsUnauthorized() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/customer/products")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    @DisplayName("客户令牌不能访问后台商品接口")
    void customerTokenCannotAccessAdminEndpoint() throws Exception {
        String token = (String) accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), PrdTestFixture.CUSTOMER_PASSWORD).get("accessToken");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/products")
                        .header(HttpHeaders.HOST, jdbc.queryForObject("SELECT domain FROM merchant WHERE id=?",String.class,data.customer().merchantId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("商家令牌不能访问客户接口")
    void sellerTokenCannotAccessCustomerEndpoint() throws Exception {
        String token = (String) accounts.sellerLogin(data.sellerAccount(), PrdTestFixture.SELLER_PASSWORD).get("accessToken");

        MvcResult result = mockMvc.perform(get("/api/v1/customer/products")
                        .header(HttpHeaders.HOST, jdbc.queryForObject("SELECT domain FROM merchant WHERE id=?",String.class,data.customer().merchantId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("菜单权限在 MVC 入口统一校验")
    void restrictedSellerCannotAccessUnassignedMenu() throws Exception {
        var restricted = PrdTestFixture.seedRestrictedSeller(jdbc, data.sellerId());
        String account = jdbc.queryForObject("SELECT account FROM seller WHERE id=?", String.class, restricted.userId());
        String token = (String) accounts.sellerLogin(account, PrdTestFixture.SELLER_PASSWORD).get("accessToken");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/products")
                        .header(HttpHeaders.HOST, jdbc.queryForObject("SELECT domain FROM merchant WHERE id=?",String.class,data.customer().merchantId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).contains("FORBIDDEN");
    }

    @Test
    @DisplayName("只有订单管理权限不能搜索或编辑待审核商品")
    void orderMenuCannotEditAuditOrder() throws Exception {
        long id = orderId(createPendingOrder("audit-http-permission", 1));
        var restricted = PrdTestFixture.seedRestrictedSeller(jdbc, data.sellerId());
        String account = jdbc.queryForObject("SELECT account FROM seller WHERE id=?", String.class, restricted.userId());
        String token = (String) accounts.sellerLogin(account, PrdTestFixture.SELLER_PASSWORD).get("accessToken");

        var searchResult = mockMvc.perform(get("/api/v1/admin/order-audits/{id}/products", id)
                .header(HttpHeaders.HOST, jdbc.queryForObject("SELECT domain FROM merchant WHERE id=?",String.class,data.customer().merchantId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        var editResult = mockMvc.perform(patch("/api/v1/admin/order-audits/{id}", id)
                .header(HttpHeaders.HOST, jdbc.queryForObject("SELECT domain FROM merchant WHERE id=?",String.class,data.customer().merchantId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"items\":[],\"expectedUpdatedAt\":\"2026-10-02T00:00:00Z\"}")).andReturn();

        assertThat(searchResult.getResponse().getStatus()).isEqualTo(403);
        assertThat(editResult.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("停用客户后原 HTTP 令牌不能继续访问")
    void disabledCustomerTokenCannotContinueUsingApi() throws Exception {
        String token = (String) accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), PrdTestFixture.CUSTOMER_PASSWORD).get("accessToken");
        jdbc.update("UPDATE `user` SET active=0,auth_version=auth_version+1 WHERE id=?", data.customerId());

        MvcResult result = mockMvc.perform(get("/api/v1/customer/products")
                        .header(HttpHeaders.HOST, jdbc.queryForObject("SELECT domain FROM merchant WHERE id=?",String.class,data.customer().merchantId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }
}
