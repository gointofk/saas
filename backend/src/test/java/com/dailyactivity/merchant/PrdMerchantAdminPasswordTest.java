package com.dailyactivity.merchant;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.infrastructure.JwtTokenService;
import com.dailyactivity.auth.infrastructure.PasswordHasher;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.merchant.application.MerchantService;
import com.dailyactivity.merchant.domain.ShippingFees;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.PrdTestFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PrdMerchantAdminPasswordTest extends PrdDatabaseTest {
    private static final String NEW_PASSWORD = "ChangedAdminPass123!";
    @Autowired private MerchantService merchants;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private AccountService accounts;
    @Autowired private JwtTokenService tokens;
    @Autowired private ObjectMapper mapper;
    @Autowired private MockMvc http;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void platformEditResetsOnlyTheInitialAdministratorAndInvalidatesOldLogin() throws Exception {
        var platform = PrdTestFixture.platform(jdbc);
        var ordinaryMember = PrdTestFixture.seedRestrictedSeller(jdbc, data.sellerId());
        var other = PrdTestFixture.seed(jdbc, PrdTestFixture.merchant(jdbc, "OTHER" + UUID.randomUUID().toString().substring(0, 8)));
        String memberHash = sellerHash(ordinaryMember.userId());
        String otherHash = sellerHash(other.sellerId());
        String platformHash = jdbc.queryForObject("SELECT password_hash FROM admin WHERE id=?", String.class, platform.userId());
        String customerHash = jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id=?", String.class, data.customerId());
        String oldToken = (String) accounts.sellerLogin(data.sellerAccount(), PrdTestFixture.SELLER_PASSWORD).get("accessToken");
        var merchant = merchantRepository.find(seller().merchantId()).orElseThrow();

        var response = http.perform(put("/api/v1/platform/merchants/{id}", merchant.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(platform).accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update(merchant, NEW_PASSWORD, NEW_PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(response).path("administrator").path("account").asText()).isEqualTo(data.sellerAccount());
        assertThat(response).doesNotContain(NEW_PASSWORD, "passwordHash", "password_hash");
        assertThat(PasswordHasher.matches(NEW_PASSWORD, sellerHash(data.sellerId()))).isTrue();
        assertThat(sellerHash(ordinaryMember.userId())).isEqualTo(memberHash);
        assertThat(sellerHash(other.sellerId())).isEqualTo(otherHash);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM admin WHERE id=?", String.class, platform.userId())).isEqualTo(platformHash);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id=?", String.class, data.customerId())).isEqualTo(customerHash);
        assertThatThrownBy(() -> accounts.sellerLogin(data.sellerAccount(), PrdTestFixture.SELLER_PASSWORD))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("UNAUTHORIZED");
        http.perform(get("/api/v1/admin/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken)).andExpect(status().isUnauthorized());
        assertThat(accounts.sellerLogin(data.sellerAccount(), NEW_PASSWORD)).containsKey("accessToken");
        assertThat(jdbc.queryForObject("SELECT updated_by_type FROM seller WHERE id=?", String.class, data.sellerId())).isEqualTo("platform");
        String audit = jdbc.queryForObject("SELECT detail FROM merchant_event WHERE merchant_id=? AND action='merchant_admin_password_reset'", String.class, merchant.id());
        assertThat(audit).contains(Long.toString(data.sellerId())).doesNotContain(NEW_PASSWORD, sellerHash(data.sellerId()));
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,NULL", "'', ''"}, nullValues = "NULL")
    void emptyPasswordFieldsPreserveTheExistingPasswordAndSession(String password, String confirmation) {
        var platform = PrdTestFixture.platform(jdbc);
        var merchant = merchantRepository.find(seller().merchantId()).orElseThrow();
        String before = sellerHash(data.sellerId());

        merchants.update(platform, merchant.id(), update(merchant, password, confirmation));

        assertThat(sellerHash(data.sellerId())).isEqualTo(before);
        accounts.assertValid(seller());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_event WHERE merchant_id=? AND action='merchant_admin_password_reset'", Long.class, merchant.id())).isZero();
    }

    @ParameterizedTest
    @CsvSource(value = {"short,short,INVALID_PASSWORD", "ChangedAdminPass123!,different,PASSWORD_CONFIRMATION_MISMATCH", "NULL,ChangedAdminPass123!,PASSWORD_CONFIRMATION_MISMATCH"}, nullValues = "NULL")
    void invalidPasswordsDoNotChangeMerchantOrCredentials(String password, String confirmation, String code) {
        var platform = PrdTestFixture.platform(jdbc);
        var merchant = merchantRepository.find(seller().merchantId()).orElseThrow();
        String before = sellerHash(data.sellerId());

        assertThatThrownBy(() -> merchants.update(platform, merchant.id(), update(merchant, password, confirmation)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);

        assertThat(merchantRepository.find(merchant.id()).orElseThrow()).isEqualTo(merchant);
        assertThat(sellerHash(data.sellerId())).isEqualTo(before);
    }

    @Test
    void staleMerchantVersionAndNonPlatformIdentityCannotResetPassword() throws Exception {
        var platform = PrdTestFixture.platform(jdbc);
        var merchant = merchantRepository.find(seller().merchantId()).orElseThrow();
        var request = update(merchant, NEW_PASSWORD, NEW_PASSWORD);
        merchants.update(platform, merchant.id(), update(merchant, null, null));
        assertThatThrownBy(() -> merchants.update(platform, merchant.id(), request))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("MERCHANT_CHANGED");
        assertThatThrownBy(() -> merchants.update(seller(), merchant.id(), request))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FORBIDDEN");
        http.perform(put("/api/v1/platform/merchants/{id}", merchant.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(seller()).accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
        assertThat(PasswordHasher.matches(PrdTestFixture.SELLER_PASSWORD, sellerHash(data.sellerId()))).isTrue();
    }

    @Test
    void missingInitialAdministratorDoesNotFallBackToAnotherMember() {
        var platform = PrdTestFixture.platform(jdbc);
        var otherMember = PrdTestFixture.seedRestrictedSeller(jdbc, data.sellerId());
        jdbc.update("UPDATE seller SET deleted=id WHERE id=?", data.sellerId());
        var merchant = merchantRepository.find(seller().merchantId()).orElseThrow();
        String before = sellerHash(otherMember.userId());

        assertThatThrownBy(() -> merchants.update(platform, merchant.id(), update(merchant, NEW_PASSWORD, NEW_PASSWORD)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
        assertThat(sellerHash(otherMember.userId())).isEqualTo(before);
        assertThat(merchantRepository.find(merchant.id()).orElseThrow()).isEqualTo(merchant);
    }

    @Test
    void merchantConfigurationFailureAlsoRollsBackThePasswordChange() {
        var platform = PrdTestFixture.platform(jdbc);
        var other = PrdTestFixture.seed(jdbc, PrdTestFixture.merchant(jdbc, "FAIL" + UUID.randomUUID().toString().substring(0, 8)));
        var merchant = merchantRepository.find(seller().merchantId()).orElseThrow();
        String before = sellerHash(data.sellerId());
        var request = new MerchantService.Update("Changed merchant", true, merchant.shippingFees(), merchant.updatedAt(),
                merchantRepository.find(other.seller().merchantId()).orElseThrow().domain(), merchant.logoUrl(), merchant.theme(), false, null, NEW_PASSWORD, NEW_PASSWORD);

        var nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.execute(status -> merchants.update(platform, merchant.id(), request)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("MERCHANT_DOMAIN_EXISTS");
        assertThat(sellerHash(data.sellerId())).isEqualTo(before);
        assertThat(merchantRepository.find(merchant.id()).orElseThrow()).isEqualTo(merchant);
    }

    private MerchantService.Update update(MerchantRepository.Merchant merchant, String password, String confirmation) {
        return new MerchantService.Update(merchant.name(), merchant.active(), merchant.shippingFees(), merchant.updatedAt(),
                merchant.domain(), merchant.logoUrl(), merchant.theme(), null, null, password, confirmation);
    }

    private String sellerHash(long id) {
        return jdbc.queryForObject("SELECT password_hash FROM seller WHERE id=?", String.class, id);
    }
}
