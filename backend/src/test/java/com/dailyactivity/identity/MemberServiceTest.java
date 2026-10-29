package com.dailyactivity.identity;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Account;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.domain.port.AccountRepository;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.identity.application.MemberService;
import com.dailyactivity.identity.domain.port.MemberRepository;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MemberServiceTest {
    private final AccountRepository users = mock(AccountRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final AccountService accounts = new AccountService(users, null, null, merchants);
    private final MemberService service = new MemberService(members, accounts, merchants, users);

    @Test
    void ordinaryMemberCanStillBeEdited() {
        Principal actor = actor(8, true);
        ordinaryTarget();

        service.update(actor, 10, new MemberService.MemberRequest("member", "Updated", null, null, List.of("orders")));

        verify(members).update(11, 10, new MemberRepository.MemberInput("member", "Updated", null, List.of("orders")), new com.dailyactivity.auth.domain.Actor("seller",8));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ordinaryMemberStatusCanStillBeChanged(boolean active) {
        Principal actor = actor(8, true);
        ordinaryTarget();

        service.status(actor, 10, active);

        verify(members).setActive(11, 10, active, new com.dailyactivity.auth.domain.Actor("seller",8));
    }

    @Test
    void crossMerchantTargetStillReturnsNotFound() {
        Principal actor = actor(8, true);
        when(users.findByIdForUpdate("seller",7)).thenReturn(Optional.of(account(7, 12, true)));

        assertThatThrownBy(() -> service.status(actor, 7, false))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(members);
    }

    private Principal actor(long id, boolean manager) {
        when(users.findActiveById("seller",id)).thenReturn(Optional.of(account(id, 11, manager)));
        when(users.findPermissions(id)).thenReturn(Set.of("members", "orders"));
        when(merchants.find(11)).thenReturn(Optional.of(new MerchantRepository.Merchant(11, "TEST", "Test", true, BigDecimal.ZERO, Instant.EPOCH)));
        return new Principal(id, "seller", 0, 11);
    }

    private void ordinaryTarget() {
        when(users.findByIdForUpdate("seller",10)).thenReturn(Optional.of(account(10, 11, false)));
        when(members.find(11, 10)).thenReturn(Optional.of(new MemberRepository.Member(10, "member", "Member", true, List.of("orders"), Instant.EPOCH)));
    }

    private Account account(long id, long merchantId, boolean manager) {
        return new Account(id, "seller", "member-" + id, null, "Member", null, null, null, null, true, null, null, 0, null, merchantId);
    }
}
