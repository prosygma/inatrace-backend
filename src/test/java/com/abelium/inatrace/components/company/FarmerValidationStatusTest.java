package com.abelium.inatrace.components.company;

import com.abelium.inatrace.api.ApiStatus;
import com.abelium.inatrace.api.errors.ApiException;
import com.abelium.inatrace.components.company.api.ApiListFarmersRequest;
import com.abelium.inatrace.components.company.api.ApiUserCustomer;
import com.abelium.inatrace.db.entities.common.User;
import com.abelium.inatrace.db.entities.common.UserCustomer;
import com.abelium.inatrace.db.entities.company.Company;
import com.abelium.inatrace.db.entities.company.CompanyUser;
import com.abelium.inatrace.security.service.CustomUserDetails;
import com.abelium.inatrace.types.CompanyStatus;
import com.abelium.inatrace.types.CompanyUserRole;
import com.abelium.inatrace.types.FarmerValidationStatus;
import com.abelium.inatrace.types.Language;
import com.abelium.inatrace.types.UserCustomerType;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register #3: collectors may add farmers from the field, but those farmers wait for a supervisor
 * (company admin or system admin) to validate or reject them.
 */
@SpringBootTest
class FarmerValidationStatusTest {

    @Autowired
    private CompanyService companyService;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private final String runId = "farmer-validation-" + System.nanoTime();

    private Long companyId;
    private Long adminId;
    private Long collectorId;
    private final List<Long> companyUserIds = new ArrayList<>();
    private final List<Long> farmerIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Company company = new Company();
            company.setName("Farmer validation test company " + runId);
            company.setStatus(CompanyStatus.ACTIVE);
            em.persist(company);

            User admin = newUser("admin");
            User collector = newUser("collector");
            companyUserIds.add(enrol(company, admin, CompanyUserRole.COMPANY_ADMIN));
            companyUserIds.add(enrol(company, collector, CompanyUserRole.COMPANY_USER));
            em.flush();

            companyId = company.getId();
            adminId = admin.getId();
            collectorId = collector.getId();
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

    private Long enrol(Company company, User user, CompanyUserRole role) {
        CompanyUser companyUser = new CompanyUser();
        companyUser.setCompany(company);
        companyUser.setUser(user);
        companyUser.setRole(role);
        em.persist(companyUser);
        em.flush();
        return companyUser.getId();
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            farmerIds.forEach(id -> remove(UserCustomer.class, id));
            em.flush();
            companyUserIds.forEach(id -> remove(CompanyUser.class, id));
            remove(Company.class, companyId);
            remove(User.class, adminId);
            remove(User.class, collectorId);
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

    private CustomUserDetails as(Long userId) {
        return new CustomUserDetails(userId, "u" + userId + "@example.test", "n", "s", UserRole.USER);
    }

    private ApiUserCustomer addFarmer(Long userId, String name) {
        ApiUserCustomer request = new ApiUserCustomer();
        request.setType(UserCustomerType.FARMER);
        request.setName(name);
        request.setSurname("Validation");
        // A client-sent state must be ignored
        request.setValidationStatus(FarmerValidationStatus.VALIDATED);
        ApiUserCustomer created = new TransactionTemplate(txManager).execute(status -> {
            try {
                return companyService.addUserCustomer(companyId, request, as(userId), Language.EN);
            } catch (ApiException e) {
                throw new IllegalStateException(e);
            }
        });
        farmerIds.add(created.getId());
        return created;
    }

    private ApiException reviewExpectingError(Long userId, Long farmerId, FarmerValidationStatus status) {
        try {
            new TransactionTemplate(txManager).executeWithoutResult(tx -> {
                try {
                    companyService.setUserCustomerValidationStatus(farmerId, status, as(userId), Language.EN);
                } catch (ApiException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof ApiException apiException) {
                return apiException;
            }
            throw e;
        }
        throw new AssertionError("expected the review to be refused");
    }

    private ApiUserCustomer review(Long userId, Long farmerId, FarmerValidationStatus status) {
        return new TransactionTemplate(txManager).execute(tx -> {
            try {
                return companyService.setUserCustomerValidationStatus(farmerId, status, as(userId), Language.EN);
            } catch (ApiException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void farmerAddedByCollector_isPending_evenIfClientClaimsValidated() {
        assertEquals(FarmerValidationStatus.PENDING, addFarmer(collectorId, "Collected").getValidationStatus());
    }

    @Test
    void farmerAddedByCompanyAdmin_isValidated() {
        assertEquals(FarmerValidationStatus.VALIDATED, addFarmer(adminId, "Registered").getValidationStatus());
    }

    @Test
    void onlyTheCompanyAdmin_canReviewAFarmer() {
        Long farmerId = addFarmer(collectorId, "Reviewed").getId();

        ApiException refused = reviewExpectingError(collectorId, farmerId, FarmerValidationStatus.VALIDATED);
        assertEquals(ApiStatus.UNAUTHORIZED, refused.getApiStatus());

        assertEquals(FarmerValidationStatus.REJECTED, review(adminId, farmerId, FarmerValidationStatus.REJECTED).getValidationStatus());
        assertEquals(FarmerValidationStatus.VALIDATED, review(adminId, farmerId, FarmerValidationStatus.VALIDATED).getValidationStatus());
    }

    @Test
    void farmersList_canBeFilteredByValidationStatus() {
        Long pendingId = addFarmer(collectorId, "Pending").getId();
        Long validatedId = addFarmer(adminId, "Validated").getId();

        ApiListFarmersRequest request = new ApiListFarmersRequest();
        request.setValidationStatus(FarmerValidationStatus.PENDING);
        request.setSortBy("BY_ID");
        List<ApiUserCustomer> pending = new TransactionTemplate(txManager).execute(tx -> {
            try {
                return companyService.getUserCustomersForCompanyAndType(companyId, UserCustomerType.FARMER, request, as(adminId), Language.EN).getItems();
            } catch (ApiException e) {
                throw new IllegalStateException(e);
            }
        });

        assertTrue(pending.stream().anyMatch(f -> f.getId().equals(pendingId)), "pending farmer should be listed");
        assertTrue(pending.stream().noneMatch(f -> f.getId().equals(validatedId)), "validated farmer should be filtered out");
    }
}
