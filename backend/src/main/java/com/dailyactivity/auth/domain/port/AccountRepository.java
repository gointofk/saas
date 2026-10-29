package com.dailyactivity.auth.domain.port;

import com.dailyactivity.auth.domain.Account;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface AccountRepository {
    Optional<Account> findCustomerByEmail(long merchantId, String email);

    Optional<Account> findPlatformByAccount(String account);

    Optional<Account> findSellerByAccount(String account);

    Optional<Account> findActiveById(String userType, long id);

    Optional<Account> findCustomerById(long id);

    Optional<Account> findByIdForUpdate(String userType, long id);

    Set<String> findPermissions(long sellerId);

    void touchLastLogin(String userType, long id, Instant now);

    boolean incrementAuthVersion(String userType, long id, long expectedVersion);

    boolean updateCustomerName(long id, String name, long actorId);

    boolean updateCustomerPassword(long id, String passwordHash, long actorId);

}
