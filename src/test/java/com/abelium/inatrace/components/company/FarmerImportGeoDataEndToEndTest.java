package com.abelium.inatrace.components.company;

import com.abelium.inatrace.components.common.TokenService;
import com.abelium.inatrace.db.entities.codebook.ProductType;
import com.abelium.inatrace.db.entities.common.Country;
import com.abelium.inatrace.db.entities.common.PlotCoordinate;
import com.abelium.inatrace.db.entities.common.User;
import com.abelium.inatrace.db.entities.common.UserCustomer;
import com.abelium.inatrace.db.entities.company.Company;
import com.abelium.inatrace.db.entities.company.CompanyUser;
import com.abelium.inatrace.db.entities.value_chain.CompanyValueChain;
import com.abelium.inatrace.db.entities.value_chain.ValueChain;
import com.abelium.inatrace.db.entities.value_chain.enums.ValueChainStatus;
import com.abelium.inatrace.types.CompanyStatus;
import com.abelium.inatrace.types.CompanyUserRole;
import com.abelium.inatrace.types.DocumentType;
import com.abelium.inatrace.types.Language;
import com.abelium.inatrace.types.UserRole;
import com.abelium.inatrace.types.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real, end-to-end verification that a genuine farmer-import upload - through the actual HTTP
 * endpoints, against the live "inatrace_local" database - accepts valid plot geodata and rejects
 * invalid geodata, using the real shipped "other countries" template as the base file (same one
 * verified column-for-column in {@link FarmerImportTemplateAssetTest}).
 *
 * <p>All fixture data (country/user/company/product type/value chain and anything the import
 * creates) is tagged with a unique run id and removed in {@link #cleanup()}, so this test leaves
 * the shared local database exactly as it found it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FarmerImportGeoDataEndToEndTest {

    private static final String TEMPLATE_PATH =
            "src/test/resources/farmer-import/Template_list_of_farmers_other_countries_en.xlsx";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    @Value("${INATrace.auth.accessTokenCookieName}")
    private String accessTokenCookieName;

    @Value("${INATrace.fileStorage.root}")
    private String fileStorageRoot;

    private final String runId = "geo-e2e-" + System.nanoTime();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long countryId;
    private Long userId;
    private Long companyId;
    private Long productTypeId;
    private Long valueChainId;
    private Long companyValueChainId;
    private Long companyUserId;
    private String accessToken;
    private final List<Long> createdDocumentIds = new ArrayList<>();

    @BeforeEach
    void seedFixtures() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Country country = new Country();
            country.setCode("ZZ");
            country.setName("Geodata E2E Test Country " + runId);
            em.persist(country);

            User user = new User();
            user.setEmail(runId + "@example.test");
            user.setName("Geo");
            user.setSurname("Tester");
            user.setStatus(UserStatus.ACTIVE);
            user.setLanguage(Language.EN);
            user.setRole(UserRole.SYSTEM_ADMIN);
            em.persist(user);

            Company company = new Company();
            company.setName("Geodata E2E Test Company " + runId);
            company.setStatus(CompanyStatus.ACTIVE);
            em.persist(company);

            CompanyUser companyUser = new CompanyUser();
            companyUser.setUser(user);
            companyUser.setCompany(company);
            companyUser.setRole(CompanyUserRole.COMPANY_ADMIN);
            em.persist(companyUser);

            ProductType productType = new ProductType();
            productType.setCode("GEOE2E");
            productType.setName("Geodata E2E Test Crop " + runId);
            em.persist(productType);

            ValueChain valueChain = new ValueChain();
            valueChain.setName("Geodata E2E Test Value Chain " + runId);
            valueChain.setDescription("Created by FarmerImportGeoDataEndToEndTest");
            valueChain.setValueChainStatus(ValueChainStatus.ENABLED);
            valueChain.setCreatedBy(user);
            valueChain.setProductType(productType);
            em.persist(valueChain);

            CompanyValueChain companyValueChain = new CompanyValueChain();
            companyValueChain.setCompany(company);
            companyValueChain.setValueChain(valueChain);
            em.persist(companyValueChain);

            em.flush();

            countryId = country.getId();
            userId = user.getId();
            companyId = company.getId();
            productTypeId = productType.getId();
            valueChainId = valueChain.getId();
            companyValueChainId = companyValueChain.getId();
            companyUserId = companyUser.getId();

            accessToken = tokenService.createAccessToken(user);
        });
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            // Remove any farmers the import created (cascades Plot/PlotCoordinate/etc.), plus
            // their UserCustomerLocation, which isn't cascaded from UserCustomer.
            List<UserCustomer> farmers = em.createQuery(
                            "SELECT uc FROM UserCustomer uc WHERE uc.farmerCompanyInternalId LIKE :prefix",
                            UserCustomer.class)
                    .setParameter("prefix", runId + "%")
                    .getResultList();
            for (UserCustomer farmer : farmers) {
                var location = farmer.getUserCustomerLocation();
                em.remove(farmer);
                if (location != null) {
                    em.remove(em.merge(location));
                }
            }
            em.flush();

            for (Long documentId : createdDocumentIds) {
                deleteIfPresent(com.abelium.inatrace.db.entities.common.Document.class, documentId);
            }

            deleteIfPresent(com.abelium.inatrace.db.entities.value_chain.CompanyValueChain.class, companyValueChainId);
            deleteIfPresent(ValueChain.class, valueChainId);
            deleteIfPresent(CompanyUser.class, companyUserId);
            deleteIfPresent(Company.class, companyId);
            deleteIfPresent(ProductType.class, productTypeId);
            deleteIfPresent(Country.class, countryId);
            deleteIfPresent(User.class, userId);
        });

        // Best-effort: remove the physical files the uploads wrote under Upload/GENERAL/*.
        File generalDir = Paths.get(fileStorageRoot, DocumentType.GENERAL.toString()).toFile();
        File[] files = generalDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.lastModified() >= testStartMillis) {
                    f.delete();
                }
            }
        }
    }

    private final long testStartMillis = System.currentTimeMillis();

    private <T> void deleteIfPresent(Class<T> type, Object id) {
        if (id == null) {
            return;
        }
        T entity = em.find(type, id);
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Test
    void validPolygon_isAcceptedAndPersistedWithCorrectCoordinates() throws Exception {
        String internalId = runId + "-valid";
        byte[] xlsx = buildWorkbook(internalId,
                "POLYGON((5.1717367 10.2352433, 5.1718067 10.235235, 5.1719302 10.2352027))");

        Long documentId = uploadDocument(xlsx);
        JsonNode response = callImportEndpoint(documentId);

        assertEquals(1, response.get("successful").asInt(), "expected exactly 1 farmer imported: " + response);
        assertTrue(response.get("validationErrors").isEmpty(), "expected no validation errors: " + response);

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            UserCustomer farmer = em.createQuery(
                            "SELECT uc FROM UserCustomer uc WHERE uc.farmerCompanyInternalId = :id", UserCustomer.class)
                    .setParameter("id", internalId)
                    .getSingleResult();

            assertEquals(1, farmer.getPlots().size());
            var plot = farmer.getPlots().iterator().next();
            List<PlotCoordinate> coordinates = plot.getCoordinates().stream()
                    .sorted((a, b) -> a.getCoordinateOrder().compareTo(b.getCoordinateOrder()))
                    .toList();

            // 3 distinct vertices sent -> stored as a closed ring (first point repeated as last).
            assertEquals(4, coordinates.size());
            assertEquals(coordinates.get(0).getLatitude(), coordinates.get(3).getLatitude());
            assertEquals(coordinates.get(0).getLongitude(), coordinates.get(3).getLongitude());
            assertEquals(5.1717367, coordinates.get(0).getLatitude());
            assertEquals(10.2352433, coordinates.get(0).getLongitude());
        });
    }

    @Test
    void invalidGeodata_rejectsTheWholeRowAndCreatesNoFarmer() throws Exception {
        String internalId = runId + "-invalid";
        byte[] xlsx = buildWorkbook(internalId,
                "POLYGON((95.17 10.23, 96.18 10.24, 97.19 10.25))"); // out-of-range latitude

        Long documentId = uploadDocument(xlsx);
        JsonNode response = callImportEndpoint(documentId);

        assertEquals(0, response.get("successful").asInt(), "expected no farmers imported: " + response);
        assertEquals(1, response.get("validationErrors").size(), "expected exactly 1 row validation error: " + response);

        JsonNode rowError = response.get("validationErrors").get(0);
        JsonNode columnErrors = rowError.get("columnValidationErrors");
        assertEquals(1, columnErrors.size());
        assertEquals("INVALID_GEODATA", columnErrors.get(0).get("errorType").asText());
        assertEquals("AH6", columnErrors.get(0).get("cellAddress").asText());

        long farmerCount = em.createQuery(
                        "SELECT COUNT(uc) FROM UserCustomer uc WHERE uc.farmerCompanyInternalId = :id", Long.class)
                .setParameter("id", internalId)
                .getSingleResult();
        assertEquals(0L, farmerCount, "no farmer should have been persisted for a rejected row");
    }

    /** Builds a single-data-row copy of the real shipped template, matching a realistic full farmer entry. */
    private byte[] buildWorkbook(String internalId, String geoData) throws Exception {
        try (var in = new FileInputStream(TEMPLATE_PATH);
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {

            XSSFSheet sheet = workbook.getSheetAt(0);
            XSSFRow row = sheet.createRow(5); // first data row, per UserCustomerImportService.rowIndex = 5

            row.createCell(0).setCellValue(internalId);
            row.createCell(1).setCellValue("Tester");
            row.createCell(2).setCellValue("Geo");
            row.createCell(3).setCellValue("Test Village");
            row.createCell(4).setCellValue("Test Cell");
            row.createCell(5).setCellValue("Test Sector");
            // 6 Caserio, 7 Aldea, 8 Municipio, 9 Departamento - Honduras-only, left blank
            row.createCell(10).setCellValue("123 Test Street");
            row.createCell(11).setCellValue("Testville");
            row.createCell(12).setCellValue("Test Region");
            row.createCell(13).setCellValue("00000");
            // 14 additional address - left blank
            row.createCell(15).setCellValue("ZZ");
            row.createCell(16).setCellValue("F");
            row.createCell(17).setCellValue("555123456");
            row.createCell(18).setCellValue(runId + "@farmer.test");
            row.createCell(19).setCellValue("Y");
            row.createCell(20).setCellValue("ha");
            row.createCell(21).setCellValue(2.5);
            row.createCell(22).setCellValue(2.5);
            row.createCell(23).setCellValue(1000);
            // 24/25 second product type - left blank (template has no second product type column)
            row.createCell(26).setCellValue("N");
            // 27 area organic certified, 28 start of transition - left blank
            row.createCell(29).setCellValue("00123456789");
            row.createCell(30).setCellValue("Geo Tester");
            row.createCell(31).setCellValue("Test Bank");
            row.createCell(32).setCellValue("none");
            row.createCell(33).setCellValue(geoData);

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                workbook.write(out);
                return out.toByteArray();
            }
        }
    }

    private Long uploadDocument(byte[] xlsx) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, accessTokenCookieName + "=" + accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(xlsx) {
            @Override
            public String getFilename() {
                return "farmers.xlsx";
            }
        });

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/common/document?type=GENERAL", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "document upload failed: " + response.getBody());
        try {
            JsonNode json = objectMapper.readTree(response.getBody());
            Long documentId = json.get("data").get("id").asLong();
            createdDocumentIds.add(documentId);
            return documentId;
        } catch (Exception e) {
            throw new RuntimeException("Could not parse upload response: " + response.getBody(), e);
        }
    }

    private JsonNode callImportEndpoint(Long documentId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, accessTokenCookieName + "=" + accessToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/company/userCustomers/import/farmers/" + companyId + "/" + documentId,
                HttpMethod.POST, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "import call failed: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }
}
