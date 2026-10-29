package com.dailyactivity.identity.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.infrastructure.PasswordHasher;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.common.RequestValidation;
import com.dailyactivity.identity.domain.port.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.HashSet;

@Service
public class MemberService {
    private final MemberRepository members;
    private final AccountService accounts;
    private final com.dailyactivity.merchant.domain.port.MerchantRepository merchants;
    private final com.dailyactivity.auth.domain.port.AccountRepository users;

    public MemberService(MemberRepository members, AccountService accounts, com.dailyactivity.merchant.domain.port.MerchantRepository merchants, com.dailyactivity.auth.domain.port.AccountRepository users) {
        this.merchants = merchants;
        this.users = users;
        this.members = members;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberRepository.Member> page(Principal p, String q, Integer page) {
        accounts.seller(p);
        int current = RequestValidation.page(page);
        return PageResponse.of(members.page(p.merchantId(), q, current), current, members.count(p.merchantId(), q));
    }

    @Transactional
    public Map<String, Object> create(Principal p, MemberRequest r) {
        accounts.requireMenu(p, "members");
        merchants.lock(p.merchantId());
        accounts.validateMemberPermissions(p, r.permissions());
        validate(r, true);
        return Map.of("id", members.create(p.merchantId(), new MemberRepository.MemberInput(r.account().trim().toLowerCase(), r.name().trim(), PasswordHasher.hash(r.password()), r.permissions()), p.actor()));
    }

    @Transactional
    public void update(Principal p, long id, MemberRequest r) {
        accounts.requireMenu(p, "members");
        merchants.lock(p.merchantId());
        lockTargetMember(p, id);
        accounts.validateMemberPermissions(p, r.permissions());
        validate(r, false);
        protectLastMemberManager(p, id, r.permissions(), true);
        members.update(p.merchantId(), id, new MemberRepository.MemberInput(r.account().trim().toLowerCase(), r.name().trim(), r.password() == null || r.password().isBlank() ? null : PasswordHasher.hash(r.password()), r.permissions()), p.actor());
    }

    @Transactional
    public void status(Principal p, long id, boolean active) {
        accounts.requireMenu(p, "members");
        merchants.lock(p.merchantId());
        lockTargetMember(p, id);
        if (!active) protectLastMemberManager(p, id, null, false);
        members.setActive(p.merchantId(), id, active, p.actor());
    }

    private void lockTargetMember(Principal p, long id) {
        users.findByIdForUpdate("seller", id).filter(a -> a.seller() && a.merchantId() == p.merchantId())
                .orElseThrow(() -> ApiException.notFound("Member not found."));

    }

    private void validate(MemberRequest r, boolean passwordRequired) {
        boolean passwordProvided = r.password() != null && !r.password().isBlank();
        boolean confirmationProvided = r.confirmation() != null && !r.confirmation().isBlank();
        if ((passwordRequired && !passwordProvided)
                || (passwordProvided && (r.password().length() < 12 || r.password().length() > 64)))
            throw ApiException.badRequest("INVALID_PASSWORD", "Password must be 12–64 characters.");
        if (r.account() == null || r.account().isBlank() || r.account().trim().length() > 64
                || r.name() == null || r.name().isBlank() || r.name().trim().length() > 100
                || r.permissions() == null || r.permissions().isEmpty() || new HashSet<>(r.permissions()).size() != r.permissions().size()
                || (passwordProvided && !r.password().equals(r.confirmation()))
                || (!passwordProvided && confirmationProvided))
            throw ApiException.badRequest("INVALID_MEMBER", "Member fields are invalid.");
    }

    private void protectLastMemberManager(Principal p, long id, java.util.List<String> permissions, boolean updatingPermissions) {
        var existing = members.find(p.merchantId(), id).orElseThrow(() -> ApiException.notFound("Member not found."));
        boolean currentlyManager = existing.active() && existing.permissions().contains("members");
        boolean willRemainManager = updatingPermissions && permissions != null && permissions.contains("members");
        if (currentlyManager && !willRemainManager && members.activeMemberManagers(p.merchantId()) <= 1)
            throw ApiException.conflict("LAST_MEMBER_MANAGER", "At least one active member with members permission is required.");
    }

    public record MemberRequest(String account, String name, String password, String confirmation,
                                java.util.List<String> permissions) {
    }
}
