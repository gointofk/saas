package com.dailyactivity.identity.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MemberRepository {
    List<Member> page(long merchantId, String query, int page);

    long count(long merchantId, String query);

    long create(long merchantId, MemberInput input, long actorId);

    void update(long merchantId, long id, MemberInput input, long actorId);

    void setActive(long merchantId, long id, boolean active, long actorId);

    Optional<Member> find(long merchantId, long id);

    long activeMemberManagers(long merchantId);

    record Member(long id, String account, String name, boolean active, List<String> permissions, Instant updatedAt, boolean merchantManagement) {
    }

    record MemberInput(String account, String name, String passwordHash, List<String> permissions) {
    }
}
