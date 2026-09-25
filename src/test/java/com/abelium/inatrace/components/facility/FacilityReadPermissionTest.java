package com.abelium.inatrace.components.facility;

import com.abelium.inatrace.api.ApiStatus;
import com.abelium.inatrace.api.errors.ApiException;
import com.abelium.inatrace.db.entities.codebook.FacilityType;
import com.abelium.inatrace.db.entities.common.Address;
import com.abelium.inatrace.db.entities.common.Country;
import com.abelium.inatrace.db.entities.common.User;
import com.abelium.inatrace.db.entities.company.Company;
import com.abelium.inatrace.db.entities.company.CompanyUser;
import com.abelium.inatrace.db.entities.facility.Facility;
import com.abelium.inatrace.db.entities.facility.FacilityLocation;
import com.abelium.inatrace.security.service.CustomUserDetails;
import com.abelium.inatrace.types.CompanyStatus;
import com.abelium.inatrace.types.CompanyUserRole;
import com.abelium.inatrace.types.Language;
import com.abelium.inatrace.types.UserRole;
import com.abelium.inatrace.types.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A company must be able to read its own "sellable" (public) facility before it owns any product.
 * The public-facility branch used to check only the product connection, so a new cooperative got
 * HTTP 403 on /api/chain/facility/{id} and could not record a single delivery.
 */
@SpringBootTest
class FacilityReadPermissionTest {

    @Autowired
    private FacilityService facilityService;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private final String runId = "facility-perm-" + System.nanoTime();

    private Long memberId;
    private Long outsiderId;
    private Long companyId;
    private Long companyUserId;
    private Long facilityId;
    private Long facilityTypeId;
    private Long countryId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Country country = new Country();
            country.setCode("ZY");
            country.setName("Facility permission test country " + runId);
            em.persist(country);

            User member = newUser("member");
            User outsider = newUser("outsider");

            Company company = new Company();
            company.setName("Facility permission test company " + runId);
            company.setStatus(CompanyStatus.ACTIVE);
            em.persist(company);

            CompanyUser companyUser = new CompanyUser();
            companyUser.setUser(member);
            companyUser.setCompany(company);
            companyUser.setRole(CompanyUserRole.COMPANY_USER);
            em.persist(companyUser);

            FacilityType facilityType = new FacilityType();
            facilityType.setCode("PERM_TEST_" + System.nanoTime() % 100000);
            facilityType.setLabel("Permission test type");
            em.persist(facilityType);

            Address address = new Address();
            address.setCity("Dschang");
            address.setCountry(country);
            FacilityLocation location = new FacilityLocation();
            location.setAddress(address);
            location.setPubliclyVisible(Boolean.FALSE);

            Facility facility = new Facility();
            facility.setName("Sellable facility " + runId);
            facility.setIsPublic(Boolean.TRUE);
            facility.setIsCollectionFacility(Boolean.TRUE);
            facility.setIsDeactivated(Boolean.FALSE);
            facility.setCompany(company);
            facility.setFacilityType(facilityType);
            facility.setFacilityLocation(location);
            em.persist(facility);
            em.flush();

            memberId = member.getId();
            outsiderId = outsider.getId();
            companyId = company.getId();
            companyUserId = companyUser.getId();
            facilityId = facility.getId();
            facilityTypeId = facilityType.getId();
            countryId = country.getId();
        });
    }

    private User newUser(String kind) {
        User user = new User();
        user.setEmail(runId + "-" + kind + "@example.test");
        user.setName(kind);
        user.setSurname("Tester");
        user.setStatus(UserStatus.ACTIVE);
        user.setLanguage(Language.EN);
        user.setRole(UserRole.USER);
        em.persist(user);
        return user;
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            remove(Facility.class, facilityId);
            remove(FacilityType.class, facilityTypeId);
            remove(CompanyUser.class, companyUserId);
            remove(Company.class, companyId);
            remove(User.class, memberId);
            remove(User.class, outsiderId);
            remove(Country.class, countryId);
        });
    }

    private <T> void remove(Class<T> type, Long id) {
        if (id != null) {
            T entity = em.find(type, id);
            if (entity != null) {
                em.remove(entity);
            }
        }
    }

    private CustomUserDetails userDetails(Long id, UserRole role) {
        return new CustomUserDetails(id, "u" + id + "@example.test", "n", "s", role);
    }

    @Test
    void companyMember_canReadOwnPublicFacility_beforeAnyProductExists() throws Exception {
        var facility = new TransactionTemplate(txManager).execute(status -> {
            try {
                return facilityService.getFacility(facilityId, userDetails(memberId, UserRole.USER), Language.EN);
            } catch (ApiException e) {
                throw new AssertionError("company member was refused: " + e.getMessage(), e);
            }
        });
        assertEquals(facilityId, facility.getId());
    }

    @Test
    void outsider_isStillRefused() {
        WrappedApiException wrapped = assertThrows(WrappedApiException.class, () ->
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    try {
                        facilityService.getFacility(facilityId, userDetails(outsiderId, UserRole.USER), Language.EN);
                    } catch (ApiException e) {
                        throw new WrappedApiException(e);
                    }
                }), "outsider should not read the facility");
        assertEquals(ApiStatus.UNAUTHORIZED, ((ApiException) wrapped.getCause()).getApiStatus());
    }

    /** Carries the checked ApiException out of the transaction callback. */
    private static final class WrappedApiException extends RuntimeException {
        WrappedApiException(ApiException cause) {
            super(cause);
        }
    }
}
