package com.dailyactivity.merchant;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Account;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.domain.port.AccountRepository;
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
    private final AccountRepository users=mock(AccountRepository.class);
    private final MerchantRepository merchants=mock(MerchantRepository.class);
    private final AccountService accounts=new AccountService(users,null,null,merchants);
    private final Principal seller=new Principal(7,"seller",0,11);
    private final Account account=new Account(7,"seller","test-member",null,"Test",null,null,null,null,true,null,null,0,null,11);
    @BeforeEach
    void activeMerchant() {
        when(users.findActiveById("seller",7)).thenReturn(Optional.of(account));
        when(merchants.find(11)).thenReturn(Optional.of(new MerchantRepository.Merchant(11,"TEST","Test",true,BigDecimal.TEN,Instant.EPOCH)));
        when(users.findPermissions(7)).thenReturn(Set.of("products","orders","members"));
    }
    @Test
    void catalogMenusAreControlledByTheMerchantMemberPermission() {
        assertThatCode(()->accounts.requireMenu(seller,"products")).doesNotThrowAnyException();
        when(users.findPermissions(7)).thenReturn(Set.of("orders", "members"));
        assertThatThrownBy(()->accounts.requireMenu(seller,"products")).isInstanceOf(ApiException.class);
        assertThatCode(()->accounts.requireMenu(seller,"orders")).doesNotThrowAnyException();
    }
    @Test
    void memberCanManageItsOwnCatalogMenusButCannotGrantUnknownPermission() {
        assertThatCode(()->accounts.validateMemberPermissions(seller,List.of("products","certificates","categories"))).doesNotThrowAnyException();
        assertThatThrownBy(()->accounts.validateMemberPermissions(seller,List.of("merchant_management"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->accounts.requirePlatformAdmin(seller)).isInstanceOf(ApiException.class);
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
    void effectivePermissionsComeOnlyFromTheMerchantMemberRecord() {
        assertThat(accounts.effectivePermissions(account)).containsExactlyInAnyOrder("products","orders","members");
        assertThatThrownBy(()->accounts.requireMenu(seller,"certificates")).isInstanceOf(ApiException.class);
    }
    @Test
    void platformIdentityDoesNotDependOnMerchantAndCannotUseSellerMenus() {
        var platform=new Principal(9,"platform",0,0);
        when(users.findActiveById("platform",9)).thenReturn(Optional.of(new Account(9,"platform","platform",null,"Platform",null,null,null,null,true,null,null,0,null,0)));
        assertThatCode(()->accounts.requirePlatformAdmin(platform)).doesNotThrowAnyException();
        assertThatThrownBy(()->accounts.requireMenu(platform,"orders")).isInstanceOf(ApiException.class);
        verify(merchants,never()).find(0);
    }

    @Test
    void identicalIdsAcrossAccountTypesRemainIndependent() {
        var buyer=new Account(7,"customer",null,"buyer@test.local","Buyer",null,"TST","regular","V1",true,null,null,0,null,11);
        var admin=new Account(7,"platform","admin",null,"Admin",null,null,null,null,true,null,null,0,null,0);
        when(users.findActiveById("customer",7)).thenReturn(Optional.of(buyer));
        when(users.findActiveById("platform",7)).thenReturn(Optional.of(admin));
        assertThat(accounts.customer(new Principal(7,"customer",0,11))).isEqualTo(buyer);
        assertThat(accounts.seller(seller)).isEqualTo(account);
        assertThatCode(()->accounts.requirePlatformAdmin(new Principal(7,"platform",0,0))).doesNotThrowAnyException();
        assertThatThrownBy(()->accounts.requireMenu(new Principal(7,"platform",0,0),"orders")).isInstanceOf(ApiException.class);
    }

}
