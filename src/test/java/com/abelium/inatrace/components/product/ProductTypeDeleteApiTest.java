package com.abelium.inatrace.components.product;

import com.abelium.inatrace.components.common.TokenService;
import com.abelium.inatrace.db.entities.codebook.ProductType;
import com.abelium.inatrace.db.entities.common.Plot;
import com.abelium.inatrace.db.entities.common.User;
import com.abelium.inatrace.db.entities.common.UserCustomer;
import com.abelium.inatrace.db.entities.common.UserCustomerProductType;
import com.abelium.inatrace.db.entities.company.Company;
import com.abelium.inatrace.db.entities.value_chain.ValueChain;
import com.abelium.inatrace.db.entities.value_chain.enums.ValueChainStatus;
import com.abelium.inatrace.support.AbstractMySqlIntegrationTest;
import com.abelium.inatrace.types.CompanyStatus;
import com.abelium.inatrace.types.UserCustomerType;
import com.abelium.inatrace.types.UserRole;
import com.abelium.inatrace.types.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * Deleting a product type from Settings → Types: an unused type goes, a type still referenced by a value
 * chain, a farmer or a plot is refused with a message naming what uses it, in the caller's language.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductTypeDeleteApiTest extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager em;
    @Autowired private TokenService tokenService;
    @Value("${INATrace.auth.accessTokenCookieName}") private String accessCookieName;

    private User admin;
    private Cookie adminSession;
    private Cookie userSession;
    private Company company;

    @BeforeEach
    void seed() {
        admin = user("admin-product-type-delete@test", UserRole.SYSTEM_ADMIN);
        adminSession = session(admin);
        userSession = session(user("user-product-type-delete@test", UserRole.USER));
        company = new Company();
        company.setName("Product type delete cooperative");
        company.setStatus(CompanyStatus.ACTIVE);
        em.persist(company);
    }

    @Test
    void unusedProductTypeIsDeleted() throws Exception {
        ProductType type = productType("PT_UNUSED");

        MvcResult result = deleteAs(adminSession, type, "EN");

        assertEquals(200, result.getResponse().getStatus(), body(result));
        // The test transaction wraps the request: flush the removal before reading back.
        em.flush();
        em.clear();
        assertNull(em.find(ProductType.class, type.getId()));
    }

    @Test
    void productTypeOfAValueChainIsRefused() throws Exception {
        ProductType type = productType("PT_CHAIN");
        ValueChain chain = new ValueChain();
        chain.setName("Cocoa chain");
        chain.setDescription("Cocoa chain");
        chain.setValueChainStatus(ValueChainStatus.ENABLED);
        chain.setProductType(type);
        chain.setCreatedBy(admin);
        em.persist(chain);

        MvcResult result = deleteAs(adminSession, type, "EN");

        assertEquals(400, result.getResponse().getStatus(), body(result));
        assertTrue(body(result).contains("Product type \\\"PT_CHAIN name\\\" cannot be removed: it is used by 1 value chain(s)."), body(result));
        assertStillThere(type);
    }

    @Test
    void productTypeOfFarmersAndPlotsIsRefusedInTheCallersLanguage() throws Exception {
        ProductType type = productType("PT_FARM");
        UserCustomer farmer = farmer("Mbarga");
        UserCustomerProductType crop = new UserCustomerProductType();
        crop.setUserCustomer(farmer);
        crop.setProductType(type);
        em.persist(crop);
        Plot plot = new Plot();
        plot.setFarmer(farmer);
        plot.setCrop(type);
        plot.setPlotName("Parcelle 1");
        em.persist(plot);

        MvcResult result = deleteAs(adminSession, type, "FR");

        assertEquals(400, result.getResponse().getStatus(), body(result));
        assertTrue(body(result).contains("1 producteur(s), 1 parcelle(s)"), body(result));
        assertTrue(body(result).contains("Impossible de supprimer le type de produit"), body(result));
        assertStillThere(type);
    }

    @Test
    void onlyASystemAdminCanDelete() throws Exception {
        ProductType type = productType("PT_FORBIDDEN");

        MvcResult result = deleteAs(userSession, type, "EN");

        assertEquals(403, result.getResponse().getStatus(), body(result));
        assertStillThere(type);
    }

    private MvcResult deleteAs(Cookie session, ProductType type, String language) throws Exception {
        em.flush();
        return mockMvc.perform(delete("/api/product-type/" + type.getId())
                        .param("id", String.valueOf(type.getId()))
                        .header("language", language)
                        .cookie(session))
                .andReturn();
    }

    private void assertStillThere(ProductType type) {
        em.clear();
        assertNotNull(em.find(ProductType.class, type.getId()));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private ProductType productType(String code) {
        ProductType type = new ProductType();
        type.setCode(code);
        type.setName(code + " name");
        em.persist(type);
        return type;
    }

    private UserCustomer farmer(String surname) {
        UserCustomer farmer = new UserCustomer();
        farmer.setCompany(company);
        farmer.setType(UserCustomerType.FARMER);
        farmer.setName("Jeanne");
        farmer.setSurname(surname);
        em.persist(farmer);
        return farmer;
    }

    private User user(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setName("Product type");
        user.setSurname("Delete");
        user.setPassword("not-used");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        em.persist(user);
        return user;
    }

    private Cookie session(User user) {
        return new Cookie(accessCookieName, tokenService.createAccessToken(user));
    }
}
