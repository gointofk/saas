package com.dailyactivity.auth.domain.port;

import com.dailyactivity.auth.domain.Account;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface UserRepository {
    Optional<Account> findCustomerByEmail(String email);

    Optional<Account> findSellerByAccount(String account);

    Optional<Account> findActiveById(long id);

    Optional<Account> findCustomerById(long id);

    Optional<Account> findByIdForUpdate(long id);

    Set<String> findPermissions(long sellerId);

    void touchLastLogin(long id, Instant now);

    boolean incrementAuthVersion(long id, long expectedVersion);

    boolean updateName(long id, String name, long actorId);

    boolean updatePassword(long id, String passwordHash, long actorId);

    boolean updateActive(long id, boolean active, long actorId);
}
