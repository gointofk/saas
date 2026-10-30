package com.dailyactivity.identity.domain.port;

import com.dailyactivity.auth.domain.Actor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MemberRepository {
    List<Member> page(long merchantId, String query, int page);

    long count(long merchantId, String query);

    long create(long merchantId, MemberInput input, Actor actorId);

    long createMerchantAdministrator(long merchantId, MemberInput input, Actor actorId);

    void update(long merchantId, long id, MemberInput input, Actor actorId);

    void setActive(long merchantId, long id, boolean active, Actor actorId);

    Optional<Member> find(long merchantId, long id);

    Optional<InitialAdministrator> initialAdministrator(long merchantId);

    void resetPassword(long merchantId, long id, String passwordHash, Actor actorId);

    long activeMemberManagers(long merchantId);

    record InitialAdministrator(long id, String account, String name, boolean active) {}

    record Member(long id, String account, String name, boolean active, boolean merchantAdmin, List<String> permissions, Instant updatedAt) {
        public Member(long id, String account, String name, boolean active, List<String> permissions, Instant updatedAt) {
            this(id, account, name, active, false, permissions, updatedAt);
        }
    }

    record MemberInput(String account, String name, String passwordHash, List<String> permissions) {
    }
}
