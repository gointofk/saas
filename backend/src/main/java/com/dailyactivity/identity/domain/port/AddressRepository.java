package com.dailyactivity.identity.domain.port;

import java.util.List;
import java.util.Optional;

public interface AddressRepository {
    List<Address> findByCustomer(long customerId);

    Optional<Address> findByIdForUpdate(long customerId, long id);

    long create(long customerId, AddressInput input, long actorId);

    void update(long customerId, long id, AddressInput input, long actorId);

    void remove(long customerId, long id, long actorId);

    void makeDefault(long customerId, long id, long actorId);

    record Address(long id, String name, String street, String apt, String city, String region,
                   String postalCode, String phone, boolean isDefault) {
    }

    record AddressInput(String name, String street, String apt, String city, String region,
                        String postalCode, String phone, boolean isDefault) {
    }
}
