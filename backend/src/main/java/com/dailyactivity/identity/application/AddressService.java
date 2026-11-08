package com.dailyactivity.identity.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.identity.domain.port.AddressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AddressService {
    private final AddressRepository addresses;
    private final AccountService accounts;

    public AddressService(AddressRepository addresses, AccountService accounts) {
        this.addresses = addresses;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Principal p) {
        return addresses.findByCustomer(accounts.customer(p).id()).stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> create(Principal p, AddressRequest r) {
        validate(r);
        long id = addresses.create(accounts.customer(p).id(), r.input(), p.userId());
        return Map.of("id", id);
    }

    @Transactional
    public void update(Principal p, long id, AddressRequest r) {
        validate(r);
        addresses.update(accounts.customer(p).id(), id, r.input(), p.userId());
    }

    @Transactional
    public void remove(Principal p, long id) {
        addresses.remove(accounts.customer(p).id(), id, p.userId());
    }

    @Transactional
    public void makeDefault(Principal p, long id) {
        addresses.makeDefault(accounts.customer(p).id(), id, p.userId());
    }

    private Map<String, Object> view(AddressRepository.Address a) {
        Map<String, Object> v = new java.util.LinkedHashMap<>();
        v.put("id", a.id());
        v.put("name", a.name());
        v.put("street", a.street());
        v.put("apt", a.apt());
        v.put("city", a.city());
        v.put("region", a.region());
        v.put("postalCode", a.postalCode());
        v.put("phone", a.phone());
        v.put("isDefault", a.isDefault());
        return v;
    }

    private void validate(AddressRequest r) {
        if (r.name() == null || r.name().isBlank() || r.street() == null || r.street().isBlank() || r.city() == null || r.city().isBlank() || r.postalCode() == null || r.postalCode().isBlank() || r.phone() == null || r.phone().isBlank())
            throw ApiException.badRequest("INVALID_ADDRESS", "Required address fields are missing.");
        if (r.name().trim().length() > 100 || r.street().trim().length() > 200
                || (r.apt() != null && r.apt().length() > 100) || r.city().trim().length() > 100
                || (r.region() != null && r.region().length() > 100))
            throw ApiException.badRequest("INVALID_ADDRESS", "Address fields exceed their maximum length.");
        String postalCode = r.postalCode().trim();
        String phone = r.phone().trim();
        long phoneDigits = phone.chars().filter(Character::isDigit).count();
        if (!postalCode.matches("[A-Za-z0-9](?:[A-Za-z0-9 -]{0,10}[A-Za-z0-9])?")
                || !phone.matches("[0-9+(). -]+") || phone.length() > 32 || phoneDigits < 6 || phoneDigits > 15)
            throw ApiException.badRequest("INVALID_ADDRESS", "Postal code or phone format is invalid.");
    }

    public record AddressRequest(String name, String street, String apt, String city, String region,
                                 String postalCode, String phone, boolean isDefault) {
        AddressRepository.AddressInput input() {
            return new AddressRepository.AddressInput(name.trim(), street.trim(), apt, city.trim(), region, postalCode.trim(), phone.trim(), isDefault);
        }
    }
}
