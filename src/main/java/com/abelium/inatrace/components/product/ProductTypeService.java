package com.abelium.inatrace.components.product;

import com.abelium.inatrace.api.*;
import com.abelium.inatrace.api.errors.ApiException;
import com.abelium.inatrace.components.common.BaseService;
import com.abelium.inatrace.components.product.api.ApiProductType;
import com.abelium.inatrace.db.entities.codebook.ProductType;
import com.abelium.inatrace.db.entities.codebook.ProductTypeTranslation;
import com.abelium.inatrace.types.Language;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Lazy
@Service
public class ProductTypeService extends BaseService {

    private static final String PRODUCT_TYPE_DOES_NOT_EXIST = "Product type does not exist!";

    @Autowired
    private MessageSource messageSource;

    @Transactional
    public ApiResponse<ApiBaseEntity> createProductType(ApiProductType apiProductType) throws ApiException{

        ProductType productType = ProductTypeMapper.toProductType(apiProductType);

        productType.setCode(apiProductType.getCode());
        em.persist(productType);

        if (apiProductType.getTranslations() == null || apiProductType.getTranslations().isEmpty()) {
            throw new ApiException(ApiStatus.INVALID_REQUEST, "English translation is required!");
        }

        apiProductType.getTranslations().stream().filter(productTypeTranslation -> productTypeTranslation != null &&
                Language.EN.equals(productTypeTranslation.getLanguage()) &&
                productTypeTranslation.getName() != null &&
                productTypeTranslation.getDescription() != null)
                .findFirst()
                .orElseThrow(() -> new ApiException(ApiStatus.INVALID_REQUEST, "English translation is required!"));

        apiProductType.getTranslations().forEach(apiProductTypeTranslation -> {

            if (Language.EN.equals(apiProductTypeTranslation.getLanguage())) {
                // update the name from EN translation
                productType.setName(apiProductTypeTranslation.getName());
                productType.setDescription(apiProductTypeTranslation.getDescription());
            }

            ProductTypeTranslation translation = new ProductTypeTranslation();
            translation.setName(apiProductTypeTranslation.getName());
            translation.setDescription(apiProductTypeTranslation.getDescription());
            translation.setLanguage(apiProductTypeTranslation.getLanguage());
            translation.setProductType(productType);
            productType.getProductTypeTranslations().add(translation);
        });

        return new ApiResponse<>(new ApiBaseEntity(productType));
    }

    public ApiPaginatedResponse<ApiProductType> getProductTypes(Language language) {

        List<ApiProductType> productTypeList = em.createQuery("SELECT pt FROM ProductType pt", ProductType.class)
                .getResultList()
                .stream()
                .map(pt -> ProductTypeMapper.toApiProductTypeDetailed(pt, language))
                .collect(Collectors.toList());

        return new ApiPaginatedResponse<>(new ApiPaginatedList<>(productTypeList, productTypeList.size()));
    }

    public ApiResponse<ApiProductType> getProductType(Long id, Language language) throws ApiException {

        ProductType productType = em.find(ProductType.class, id);

        if (productType == null) {
            throw new ApiException(ApiStatus.NOT_FOUND, PRODUCT_TYPE_DOES_NOT_EXIST);
        }

        return new ApiResponse<>(ProductTypeMapper.toApiProductType(productType, language));
    }

    @Transactional
    public ApiResponse<ApiBaseEntity> updateProductType(ApiProductType apiProductType) throws ApiException {

        if (apiProductType.getId() == null) {
            throw new ApiException(ApiStatus.INVALID_REQUEST, "Id must be provided!");
        }

        ProductType productType = em.find(ProductType.class, apiProductType.getId());

        if (productType == null) {
            throw new ApiException(ApiStatus.NOT_FOUND, PRODUCT_TYPE_DOES_NOT_EXIST);
        }

        productType.setCode(apiProductType.getCode());

        if (apiProductType.getTranslations() == null || apiProductType.getTranslations().isEmpty()) {
            throw new ApiException(ApiStatus.INVALID_REQUEST, "English translation is required!");
        }

        apiProductType.getTranslations().stream().filter(productTypeTranslation -> productTypeTranslation != null &&
                        Language.EN.equals(productTypeTranslation.getLanguage()) &&
                        productTypeTranslation.getName() != null &&
                        productTypeTranslation.getDescription() != null)
                .findFirst()
                .orElseThrow(() -> new ApiException(ApiStatus.INVALID_REQUEST, "English translation is required!"));

        productType.getProductTypeTranslations().removeIf(
                productTypeTranslation -> apiProductType.getTranslations().stream().noneMatch(
                        apiProductTypeTranslation -> productTypeTranslation.getLanguage()
                                .equals(apiProductTypeTranslation.getLanguage())));

        apiProductType.getTranslations().forEach(apiProductTypeTranslation -> {
            ProductTypeTranslation translation = productType.getProductTypeTranslations().stream()
                    .filter(productTypeTranslation ->
                            productTypeTranslation.getLanguage().equals(apiProductTypeTranslation.getLanguage()))
                    .findFirst()
                    .orElse(new ProductTypeTranslation());

            if (Language.EN.equals(apiProductTypeTranslation.getLanguage())) {
                // update the name from EN translation
                productType.setName(apiProductTypeTranslation.getName());
                productType.setDescription(apiProductTypeTranslation.getDescription());
            }

            translation.setName(apiProductTypeTranslation.getName());
            translation.setDescription(apiProductTypeTranslation.getDescription());
            translation.setLanguage(apiProductTypeTranslation.getLanguage());
            translation.setProductType(productType);
            productType.getProductTypeTranslations().add(translation);
        });

        return new ApiResponse<>(new ApiBaseEntity(productType));
    }

    @Transactional
    public ApiDefaultResponse deleteProductType(Long id, Language language) throws ApiException {

        if (id == null) {
            throw new ApiException(ApiStatus.INVALID_REQUEST, "Id must be provided!");
        }

        ProductType productType = em.find(ProductType.class, id);

        if (productType == null) {
            throw new ApiException(ApiStatus.NOT_FOUND, PRODUCT_TYPE_DOES_NOT_EXIST);
        }

        // A product type still referenced by a value chain, a farmer or a plot cannot go: the delete would
        // fail on a foreign key, and those records would lose their product type.
        String usages = describeUsages(productType, language);
        if (usages != null) {
            throw new ApiException(ApiStatus.VALIDATION_ERROR, messageSource.getMessage(
                    "productType.delete.inUse", new Object[] { productType.getName(), usages }, locale(language)));
        }

        em.remove(productType);

        return new ApiDefaultResponse();
    }

    /**
     * Lists what still references the product type, e.g. "2 value chain(s), 15 farmer(s)", or returns null when
     * nothing does. Covers every foreign key to ProductType except its own translations.
     */
    private String describeUsages(ProductType productType, Language language) {

        long valueChains = em.createQuery(
                        "SELECT COUNT(vc) FROM ValueChain vc WHERE vc.productType = :productType", Long.class)
                .setParameter("productType", productType)
                .getSingleResult();

        long farmers = em.createQuery(
                        "SELECT COUNT(uc) FROM UserCustomer uc WHERE " +
                                "EXISTS (SELECT ucpt FROM UserCustomerProductType ucpt " +
                                "WHERE ucpt.userCustomer = uc AND ucpt.productType = :productType) OR " +
                                "EXISTS (SELECT fpi FROM FarmPlantInformation fpi " +
                                "WHERE fpi.userCustomer = uc AND fpi.productType = :productType)", Long.class)
                .setParameter("productType", productType)
                .getSingleResult();

        long plots = em.createQuery(
                        "SELECT COUNT(p) FROM Plot p WHERE p.crop = :productType", Long.class)
                .setParameter("productType", productType)
                .getSingleResult();

        List<String> parts = new ArrayList<>();
        if (valueChains > 0) {
            parts.add(messageSource.getMessage("productType.delete.inUse.valueChains", new Object[] { valueChains }, locale(language)));
        }
        if (farmers > 0) {
            parts.add(messageSource.getMessage("productType.delete.inUse.farmers", new Object[] { farmers }, locale(language)));
        }
        if (plots > 0) {
            parts.add(messageSource.getMessage("productType.delete.inUse.plots", new Object[] { plots }, locale(language)));
        }

        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static Locale locale(Language language) {
        return Locale.forLanguageTag((language != null ? language : Language.EN).name());
    }

}
