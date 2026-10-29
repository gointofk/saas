package com.dailyactivity.merchant;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Account;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.domain.port.UserRepository;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MerchantAuthorizationTest {
    private final UserRepository users=mock(UserRepository.class);
    private final MerchantRepository merchants=mock(MerchantRepository.class);
    private final AccountService accounts=new AccountService(users,null,null,merchants);
    private final Principal seller=new Principal(7,"seller",0,11);
    private final Account account=new Account(7,"seller","test-member",null,"Test",null,null,null,null,true,null,null,0,null,11,false);
    @BeforeEach
    void activeMerchant() {
        when(users.findActiveById(7)).thenReturn(Optional.of(account));
        when(merchants.find(11)).thenReturn(Optional.of(new MerchantRepository.Merchant(11,"TEST","Test",true,BigDecimal.TEN,Instant.EPOCH)));
        when(users.findPermissions(7)).thenReturn(Set.of("products","orders","members"));
        when(merchants.permissions(11)).thenReturn(Set.of("products"));
    }
    @Test
    void publicMenuRequiresBothGrantsAndRevocationAffectsExistingPrincipal() {
        assertThatCode(()->accounts.requireMenu(seller,"products")).doesNotThrowAnyException();
        when(merchants.permissions(11)).thenReturn(Set.of());
        assertThatThrownBy(()->accounts.requireMenu(seller,"products")).isInstanceOf(ApiException.class);
        assertThatCode(()->accounts.requireMenu(seller,"orders")).doesNotThrowAnyException();
    }
    @Test
    void memberCannotGrantPublicPermissionOutsideMerchantScope() {
        assertThatThrownBy(()->accounts.validateMemberPermissions(seller,List.of("certificates"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->accounts.validateMemberPermissions(seller,List.of("merchant_management"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->accounts.requireMerchantManager(seller)).isInstanceOf(ApiException.class);
    }
    @Test
    void tokenMerchantMustMatchPersistedAccount() {
        assertThatThrownBy(()->accounts.assertValid(new Principal(7,"seller",0,12))).isInstanceOf(ApiException.class);
    }
    @Test
    void disabledMerchantInvalidatesOtherwiseActiveAccount() {
        when(merchants.find(11)).thenReturn(Optional.of(new MerchantRepository.Merchant(11,"TEST","Test",false,BigDecimal.TEN,Instant.EPOCH)));
        assertThatThrownBy(()->accounts.assertValid(seller)).isInstanceOf(ApiException.class);
    }
    @Test
    void merchantGrantDoesNotGiveMembersAdditionalMenus() {
        when(merchants.permissions(11)).thenReturn(Set.of("products","certificates","categories"));
        assertThat(accounts.effectivePermissions(account)).containsExactlyInAnyOrder("products","orders","members");
        assertThatThrownBy(()->accounts.requireMenu(seller,"certificates")).isInstanceOf(ApiException.class);
    }
}
