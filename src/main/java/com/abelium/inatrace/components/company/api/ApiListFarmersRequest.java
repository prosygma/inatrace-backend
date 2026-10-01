package com.abelium.inatrace.components.company.api;

import com.abelium.inatrace.api.ApiPaginatedRequest;
import com.abelium.inatrace.types.FarmerValidationStatus;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.validation.annotation.Validated;

@Validated
@ParameterObject
public class ApiListFarmersRequest extends ApiPaginatedRequest {

    @Parameter(description = "Name or surname")
    private String query;

    @Parameter(description = "Search by parameter")
    private String searchBy;

    @Parameter(description = "Only farmers in this supervisor review state")
    private FarmerValidationStatus validationStatus;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getSearchBy() {
        return searchBy;
    }

    public void setSearchBy(String searchBy) {
        this.searchBy = searchBy;
    }

    public FarmerValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(FarmerValidationStatus validationStatus) {
        this.validationStatus = validationStatus;
    }
}
