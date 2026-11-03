package com.dailyactivity.merchant;

import com.dailyactivity.auth.api.AuthFilter;
import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.infrastructure.JwtTokenService;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.merchant.application.StorefrontService;
import com.dailyactivity.merchant.domain.ShippingFees;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.dailyactivity.auth.api.AuthSupport;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.mock.web.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorefrontSecurityTest {
    private final MerchantRepository merchants=mock(MerchantRepository.class);
    private final StorefrontService storefront=new StorefrontService(merchants);
    private MerchantRepository.Merchant merchant(long id,boolean active) {
        return new MerchantRepository.Merchant(id,"TEST","Shop",active,ShippingFees.uniform(BigDecimal.TEN),Instant.EPOCH,"shop.test","/ytpeps-logo.svg","classic");
    }
    @Test void normalizesDomainsAndRejectsUrls() {
        assertThat(StorefrontService.normalizeDomain(" SHOP.Test. ")).isEqualTo("shop.test");
        assertThatThrownBy(()->StorefrontService.normalizeDomain("https://shop.test/path")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->StorefrontService.normalizeDomain("shop.test:443")).isInstanceOf(ApiException.class);
    }
    @Test void requestHostSelectsTenantAndForwardedHeadersCannotOverrideIt() {
        var request=new MockHttpServletRequest(); request.addHeader("Host","shop.test:5173");request.addHeader("X-Forwarded-Host","other.test");
        when(merchants.findByDomain("shop.test")).thenReturn(Optional.of(merchant(11,true)));
        assertThat(storefront.resolve(request).id()).isEqualTo(11);
        verify(merchants,never()).findByDomain("other.test");
    }
    @Test void unknownOrDisabledStorefrontIsRejected() {
        var request=new MockHttpServletRequest();request.addHeader("Host","shop.test");
        assertThatThrownBy(()->storefront.resolve(request)).isInstanceOf(ApiException.class);
        when(merchants.findByDomain("shop.test")).thenReturn(Optional.of(merchant(11,false)));
        assertThatThrownBy(()->storefront.resolve(request)).isInstanceOf(ApiException.class);
    }
    @Test void foreignCustomerTokenAndPlatformTokenCannotReachCustomerHandlers() throws Exception {
        var tokens=mock(JwtTokenService.class);var accounts=mock(AccountService.class);
        var filter=new AuthFilter(tokens,accounts,new ObjectMapper(),storefront);
        when(merchants.findByDomain("shop.test")).thenReturn(Optional.of(merchant(11,true)));
        for(var principal:new Principal[]{new Principal(7,"customer",0,12),new Principal(8,"platform",0,0)}) {
            when(tokens.parse("token")).thenReturn(principal);
            var request=new MockHttpServletRequest("GET","/api/v1/customer/products");request.addHeader("Host","shop.test");request.addHeader("Authorization","Bearer token");
            var response=new MockHttpServletResponse();var chain=new MockFilterChain();
            filter.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(403);assertThat(chain.getRequest()).isNull();
        }
    }
    @Test void logoAndThemeUseRestrictedValues() {
        StorefrontService.validateBranding("https://cdn.example.com/logo.png","editorial");
        assertThatThrownBy(()->StorefrontService.validateBranding("javascript:alert(1)","classic")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->StorefrontService.validateBranding("//outside.test/logo","classic")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->StorefrontService.validateBranding("/logo.png","../../custom")).isInstanceOf(ApiException.class);
    }
    @Test void platformJwtHasIndependentAudienceAndNoMerchant() {
        var config=new com.dailyactivity.config.AuthProperties("test","customer","admin",30,60,"test-only-signing-material-at-least-32-bytes",300,10);
        var service=new JwtTokenService(new com.dailyactivity.auth.infrastructure.JwtKeyProvider(config),config);
        var platform=new Principal(7,"platform",0,0);
        assertThat(service.parse(service.issue(platform).accessToken())).isEqualTo(platform);
        assertThatThrownBy(()->service.parse(service.issue(new Principal(8,"customer",0,0)).accessToken())).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/v1/customer/probe", "/api/v1/customer;ignored/probe", "/api/v1;ignored/customer/probe", "/api;ignored/v1/customer/probe"})
    void routedCustomerRequestsAlwaysCheckStorefront(String path) throws Exception {
        var tokens=mock(JwtTokenService.class);
        var accounts=mock(AccountService.class);
        var filter=new AuthFilter(tokens,accounts,new ObjectMapper(),storefront);
        var mvc=MockMvcBuilders.standaloneSetup(new StorefrontProbe()).addFilters(filter).build();
        when(merchants.findByDomain("shop.test")).thenReturn(Optional.of(merchant(11,true)));

        when(tokens.parse("token")).thenReturn(new Principal(7,"customer",0,12));
        mvc.perform(get(path).header("Host","shop.test").header("Authorization","Bearer token"))
                .andExpect(status().isForbidden());

        when(tokens.parse("token")).thenReturn(new Principal(7,"customer",0,11));
        mvc.perform(get(path).header("Host","shop.test").header("Authorization","Bearer token"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/v1/customer/probe", "/api/v1/customer;ignored/probe"})
    void platformTokenCannotUseCustomerRoutesWithMatrixParameters(String path) throws Exception {
        var tokens=mock(JwtTokenService.class);
        var accounts=mock(AccountService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new StorefrontProbe())
                .addFilters(new AuthFilter(tokens,accounts,new ObjectMapper(),storefront)).build();
        when(tokens.parse("token")).thenReturn(new Principal(8,"platform",0,0));
        mvc.perform(get(path).header("Host","shop.test").header("Authorization","Bearer token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(merchants);
    }

    @RestController
    static class StorefrontProbe {
        @GetMapping("/api/v1/customer/probe")
        public String probe(HttpServletRequest request) {
            AuthSupport.principal(request,"customer");
            return "ok";
        }
    }

}
