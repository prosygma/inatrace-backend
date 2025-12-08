package com.abelium.inatrace.components.company;

import com.abelium.inatrace.api.*;
import com.abelium.inatrace.api.errors.ApiException;
import com.abelium.inatrace.components.agstack.AgStackClientService;
import com.abelium.inatrace.components.agstack.api.ApiRegisterFieldBoundaryResponse;
import com.abelium.inatrace.components.common.BaseService;
import com.abelium.inatrace.components.common.CommonService;
import com.abelium.inatrace.components.common.api.ApiCertification;
import com.abelium.inatrace.components.company.api.*;
import com.abelium.inatrace.components.company.mappers.CompanyCustomerMapper;
import com.abelium.inatrace.components.company.mappers.PlotMapper;
import com.abelium.inatrace.components.company.types.CompanyAction;
import com.abelium.inatrace.components.product.ProductTypeMapper;
import com.abelium.inatrace.components.product.api.ApiFarmPlantInformation;
import com.abelium.inatrace.components.product.api.ApiListCustomersRequest;
import com.abelium.inatrace.components.product.api.ApiProductType;
import com.abelium.inatrace.components.user.UserQueries;
import com.abelium.inatrace.components.value_chain.ValueChainMapper;
import com.abelium.inatrace.components.value_chain.api.ApiValueChain;
import com.abelium.inatrace.db.entities.codebook.ProductType;
import com.abelium.inatrace.db.entities.common.*;
import com.abelium.inatrace.db.entities.company.Company;
import com.abelium.inatrace.db.entities.company.CompanyCustomer;
import com.abelium.inatrace.db.entities.company.CompanyUser;
import com.abelium.inatrace.db.entities.product.ProductCompany;
import com.abelium.inatrace.db.entities.value_chain.CompanyValueChain;
import com.abelium.inatrace.db.entities.value_chain.ValueChain;
import com.abelium.inatrace.security.service.CustomUserDetails;
import com.abelium.inatrace.security.utils.PermissionsUtil;
import com.abelium.inatrace.tools.*;
import com.abelium.inatrace.types.*;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.geojson.Point;
import com.mapbox.geojson.Polygon;
import com.mapbox.turf.TurfMeasurement;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;
import org.torpedoquery.jakarta.jpa.OnGoingLogicalCondition;
import org.torpedoquery.jakarta.jpa.Torpedo;
import org.torpedoquery.jakarta.jpa.Function;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Lazy
@Service
public class CompanyService extends BaseService {

	@Autowired
	private CompanyApiTools companyApiTools;

	@Autowired
	private CompanyQueries companyQueries;

	@Autowired
	private UserQueries userQueries;

	@Autowired
	private CommonService commonService;

	@Autowired
	private AgStackClientService agStackClientService;

	@Autowired
	private MessageSource messageSource;

	private Company companyListQueryObject(ApiListCompaniesRequest request) {
		Company cProxy = Torpedo.from(Company.class);

		OnGoingLogicalCondition condition = Torpedo.condition();
		if (StringUtils.isNotBlank(request.name)) {
			condition = condition.and(cProxy.getName()).like().startsWith(request.name);
		}
		if (request.status != null) {
			condition = condition.and(cProxy.getStatus()).eq(request.status);
		}
		Torpedo.where(condition);
		switch (request.sortBy) {
			case "name":
				QueryTools.orderBy(request.sort, cProxy.getName());
				break;
			case "status":
				QueryTools.orderBy(request.sort, cProxy.getStatus());
				break;
			default:
				QueryTools.orderBy(request.sort, cProxy.getId());
		}
		return cProxy;
	}

	@Transactional
	public ApiPaginatedList<ApiCompanyListResponse> listCompanies(ApiListCompaniesRequest request) {
		return PaginationTools.createPaginatedResponse(em, request, () -> companyListQueryObject(request),
				CompanyApiTools::toApiCompanyListResponse);
	}

	private Company userCompanyListQueryObject(Long userId,
			ApiListCompaniesRequest request) {

		CompanyUser cuProxy = Torpedo.from(CompanyUser.class);

		Company cProxy = Torpedo.innerJoin(cuProxy.getCompany());
		OnGoingLogicalCondition condition = Torpedo.condition();
		condition = condition.and(cuProxy.getUser().getId()).eq(userId);
		if (StringUtils.isNotBlank(request.name)) {
			condition = condition.and(cProxy.getName()).like().startsWith(request.name);
		}
		if (request.status != null) {
			condition = condition.and(cProxy.getStatus()).eq(request.status);
		}

		Torpedo.where(condition);

		switch (request.sortBy) {
			case "name":
				QueryTools.orderBy(request.sort, cProxy.getName());
				break;
			case "status":
				QueryTools.orderBy(request.sort, cProxy.getStatus());
				break;
			default:
				QueryTools.orderBy(request.sort, cProxy.getId());
		}

		return cProxy;
	}

	public ApiPaginatedList<ApiCompanyListResponse> listUserCompanies(Long userId, ApiListCompaniesRequest request) {
		return PaginationTools.createPaginatedResponse(em, request, () -> userCompanyListQueryObject(userId, request),
				CompanyApiTools::toApiCompanyListResponse);
	}

	@Transactional
	public ApiBaseEntity createCompany(Long userId, ApiCompany request) throws ApiException {

		User user = Queries.get(em, User.class, userId);
		Company company = new Company();
		CompanyUser companyUser = new CompanyUser();

		companyApiTools.updateCompany(userId, company, request, null);
		em.persist(company);

		companyUser.setUser(user);
		companyUser.setCompany(company);
		companyUser.setRole(CompanyUserRole.COMPANY_ADMIN);
		em.persist(companyUser);

		if (request.valueChains != null) {
			// update value chains
			companyApiTools.updateCompanyValueChains(request, company);
		}

		return new ApiBaseEntity(company);
	}

	@Transactional
	public ApiCompanyGet getCompany(CustomUserDetails authUser, long id, Language language) throws ApiException {

		Company c = companyQueries.fetchCompany(id);
		List<ApiCompanyUser> users = companyQueries.fetchUsersForCompany(id);

		PermissionsUtil.checkUserIfCompanyEnrolledOrSystemAdmin(c.getUsers().stream().toList(), authUser);

		List<ApiValueChain> valueChains = companyQueries.fetchCompanyValueChains(id);

		List<CompanyAction> actions = new ArrayList<>();
		actions.add(CompanyAction.VIEW_COMPANY_PROFILE);
		actions.add(CompanyAction.UPDATE_COMPANY_PROFILE);

		if (authUser.getUserRole() == UserRole.SYSTEM_ADMIN) {
			switch (c.getStatus()) {
				case REGISTERED:
					actions.addAll(Arrays.asList(CompanyAction.ACTIVATE_COMPANY, CompanyAction.DEACTIVATE_COMPANY));
					break;
				case ACTIVE:
					actions.add(CompanyAction.DEACTIVATE_COMPANY);
					break;
				case DEACTIVATED:
					actions.add(CompanyAction.ACTIVATE_COMPANY);
					break;
			}
			actions.add(CompanyAction.ADD_USER_TO_COMPANY);
			if (!users.isEmpty()) {
				actions.add(CompanyAction.REMOVE_USER_FROM_COMPANY);
				actions.add(CompanyAction.SET_USER_COMPANY_ROLE);
			}
			actions.add(CompanyAction.MERGE_TO_COMPANY);
		}

		return companyApiTools.toApiCompanyGet(authUser.getUserId(), c, language, actions, users, valueChains);
	}

	public ApiCompanyName getCompanyName(CustomUserDetails authUser, long id) throws ApiException {

		Company c = companyQueries.fetchCompany(id);

		PermissionsUtil.checkUserIfCompanyEnrolledOrSystemAdmin(c.getUsers().stream().toList(), authUser);

		return companyApiTools.toApiCompanyName(c);
	}

	public List<ApiCompanyUser> getCompanyUsers(Long id, CustomUserDetails user) throws ApiException {

		// Validate that company exists with the provided ID and that request user is enrolled in this company
		Company company = companyQueries.fetchCompany(id);
		PermissionsUtil.checkUserIfCompanyEnrolledOrSystemAdmin(company.getUsers().stream().toList(), user);

		return companyQueries.fetchUsersForCompany(id);
	}

	@Transactional
	public void updateCompany(CustomUserDetails authUser, ApiCompanyUpdate ac) throws ApiException {
		Company c = companyQueries.fetchCompany(authUser, ac.id);

		// Check that the user is company enrolled and Company admin
		PermissionsUtil.checkUserIfCompanyEnrolledAndAdminOrSystemAdmin(c.getUsers().stream().toList(), authUser);

		companyApiTools.updateCompanyWithUsers(authUser.getUserId(), c, ac);

		if (ac.valueChains != null) {
			// update value chains
			companyApiTools.updateCompanyValueChains(ac, c);
		}
	}

	@Transactional
	public void executeAction(CustomUserDetails authUser, ApiCompanyActionRequest request, CompanyAction action) throws ApiException {

		Company c = companyQueries.fetchCompany(request.companyId);

		// Check if requesting user is authorized for the company
		if (authUser.getUserRole() == UserRole.REGIONAL_ADMIN) {
			PermissionsUtil.checkUserIfCompanyEnrolled(c.getUsers().stream().toList(), authUser);

			// Check if action is 'DEACTIVATE_COMPANY' or 'MERGE_TO_COMPANY' - this is not allowed by the Regional admin
			if (action == CompanyAction.DEACTIVATE_COMPANY || action == CompanyAction.MERGE_TO_COMPANY) {
				throw new ApiException(ApiStatus.UNAUTHORIZED, "Regional admin not authorized!");
			}

		} else if (authUser.getUserRole() != UserRole.SYSTEM_ADMIN) {
			isCompanyAdmin(authUser, c.getId());
		}

		switch (action) {
			case ACTIVATE_COMPANY:
				activateCompany(c);
				break;
			case DEACTIVATE_COMPANY:
				deactivateCompany(c);
				break;
			case ADD_USER_TO_COMPANY:
				addUserToCompany(request.userId, c, request.companyUserRole);
				break;
			case SET_USER_COMPANY_ROLE:
				setUserCompanyRole(request.userId, c, request.companyUserRole);
				break;
			case REMOVE_USER_FROM_COMPANY:
				removeUserFromCompany(request.userId, c);
				break;
			case MERGE_TO_COMPANY:
				mergeToCompany(c, request.otherCompanyId);
				break;
			default:
				throw new ApiException(ApiStatus.INVALID_REQUEST, "Invalid action");
		}
	}

	public ApiUserCustomer getUserCustomer(Long id, CustomUserDetails user, Language language) throws ApiException {

		UserCustomer userCustomer = fetchUserCustomer(id);
		PermissionsUtil.checkUserIfCompanyEnrolled(userCustomer.getCompany().getUsers().stream().toList(), user);

		return companyApiTools.toApiUserCustomer(userCustomer, user.getUserId(), language);
	}

	public boolean existsUserCustomer_old(ApiUserCustomer apiUserCustomer) {
		List<UserCustomer> userCustomerList = em.createNamedQuery("UserCustomer.getUserCustomerByNameSurnameAndCity", UserCustomer.class)
				.setParameter("name", apiUserCustomer.getName())
				.setParameter("surname", apiUserCustomer.getSurname())
				.setParameter("city", apiUserCustomer.getLocation().getAddress().getCity()) //  getVillage())
				.getResultList();
		return !userCustomerList.isEmpty();
	}

	public boolean existsUserCustomer(ApiUserCustomer apiUserCustomer) {
		// Vérifier d'abord par internalId si présent
		if (apiUserCustomer.getFarmerCompanyInternalId() != null) {
			List<UserCustomer> byInternalId = em.createQuery(
							"SELECT uc FROM UserCustomer uc WHERE uc.farmerCompanyInternalId = :internalId",
							UserCustomer.class)
					.setParameter("internalId", apiUserCustomer.getFarmerCompanyInternalId())
					.getResultList();

			if (!byInternalId.isEmpty()) return true;
		}

		// Fallback sur la vérification par nom/prénom/ville
		return !em.createNamedQuery("UserCustomer.getUserCustomerByNameSurnameAndCity",
						UserCustomer.class)
				.setParameter("name", apiUserCustomer.getName())
				.setParameter("surname", apiUserCustomer.getSurname())
				.setParameter("city", apiUserCustomer.getLocation().getAddress().getCity())
				.getResultList()
				.isEmpty();
	}

	public void addPlotsToExistingFarmer_old(ApiUserCustomer newFarmerData) {
		UserCustomer existingFarmer = em.createQuery(
						"SELECT uc FROM UserCustomer uc WHERE uc.farmerCompanyInternalId = :internalId",
						UserCustomer.class)
				.setParameter("internalId", newFarmerData.getFarmerCompanyInternalId())
				.getSingleResult();
//
//		// Convertir les nouvelles parcelles en entités
//		List<Plot> newPlots = newFarmerData.getPlots().stream()
//				.map(apiPlot -> {
//					Plot plot = new Plot();
//					plot.setPlotName(apiPlot.getPlotName());
//					plot.setSize(apiPlot.getSize());
//					plot.setUnit(apiPlot.getUnit());
//
//					// Convertir les coordonnées
//					List<PlotCoordinate> coordinates = apiPlot.getCoordinates().stream()
//							.map(apiCoord -> {
//								PlotCoordinate coord = new PlotCoordinate();
//								coord.setLatitude(apiCoord.getLatitude());
//								coord.setLongitude(apiCoord.getLongitude());
//								coord.setPlot(plot); // Lier à la parcelle
//								return coord;
//							})
//							.collect(Collectors.toList());
//
//					plot.getCoordinates().add(coordinates);
//					plot.setCoordinates(coordinates);
//					plot.setUserCustomer(existingFarmer); // Lier à l'agriculteur
//					return plot;
//				})
//				.collect(Collectors.toList());
		em.merge(existingFarmer);
	}

	public void addPlotsToExistingFarmer(ApiUserCustomer newFarmerData) {
		UserCustomer existingFarmer = em.createQuery(
						"SELECT uc FROM UserCustomer uc WHERE uc.farmerCompanyInternalId = :internalId",
						UserCustomer.class)
				.setParameter("internalId", newFarmerData.getFarmerCompanyInternalId())
				.getSingleResult();

		for (ApiPlot apiPlot : newFarmerData.getPlots()) {
			Plot plot = new Plot();
			plot.setPlotName(apiPlot.getPlotName());
			plot.setUnit(apiPlot.getUnit());
			plot.setSize(apiPlot.getSize());
			plot.setFarmer(existingFarmer);
            double[] centroid = MapTools.calculatePolygonCentroid(apiPlot.getCoordinates());
            BigDecimal latCenter = BigDecimal.valueOf(centroid[0])
                    .setScale(6, RoundingMode.HALF_UP);
            BigDecimal lonCenter = BigDecimal.valueOf(centroid[1])
                    .setScale(6, RoundingMode.HALF_UP);
            plot.setCenterLatitude(latCenter.doubleValue());
            plot.setCenterLongitude(lonCenter.doubleValue());
			plot.setLastUpdated(new Date());

			for (ApiPlotCoordinate apiPlotCoordinate : apiPlot.getCoordinates()) {
				PlotCoordinate plotCoordinate = new PlotCoordinate();
                BigDecimal lat = BigDecimal.valueOf(centroid[0])
                        .setScale(6, RoundingMode.HALF_UP);
                BigDecimal lon = BigDecimal.valueOf(centroid[1])
                        .setScale(6, RoundingMode.HALF_UP);
                plotCoordinate.setLatitude(lat.doubleValue());
                plotCoordinate.setLongitude(lon.doubleValue());

				plotCoordinate.setPlot(plot);
				plot.getCoordinates().add(plotCoordinate);
			}

			// Generate Plot GeoID
			plot.setGeoId(generatePlotGeoID(plot.getCoordinates()));

			existingFarmer.getPlots().add(plot);
		}

//		// Convertir les nouvelles parcelles en entités
//		List<Plot> newPlots = newFarmerData.getPlots().stream()
//				.map(apiPlot -> {
//					Plot plot = new Plot();
//					plot.setPlotName(apiPlot.getPlotName());
//					plot.setSize(apiPlot.getSize());
//					plot.setUnit(apiPlot.getUnit());
//
//					// Convertir les coordonnées
//					List<PlotCoordinate> coordinates = apiPlot.getCoordinates().stream()
//							.map(apiCoord -> {
//								PlotCoordinate coord = new PlotCoordinate();
//								coord.setLatitude(apiCoord.getLatitude());
//								coord.setLongitude(apiCoord.getLongitude());
//								coord.setPlot(plot); // Lier à la parcelle
//								return coord;
//							})
//							.collect(Collectors.toList());
//
//					plot.getCoordinates().add(coordinates);
//					plot.setCoordinates(coordinates);
//					plot.setUserCustomer(existingFarmer); // Lier à l'agriculteur
//					return plot;
//				})
//				.collect(Collectors.toList());
		em.merge(existingFarmer);
	}

	public ApiPaginatedList<ApiUserCustomer> getUserCustomersForCompanyAndType(Long companyId,
	                                                                           UserCustomerType type,
	                                                                           ApiListFarmersRequest request,
	                                                                           CustomUserDetails user,
	                                                                           Language language) throws ApiException {

		Company company = companyQueries.fetchCompany(companyId);
		PermissionsUtil.checkUserIfCompanyEnrolled(company.getUsers().stream().toList(), user);

		return PaginationTools.createPaginatedResponse(em, request,
				() -> userCustomerListQueryObject(companyId, type, request),
				uc -> companyApiTools.toApiUserCustomer(uc, user.getUserId(), language));
	}

	public ApiResponse<List<ApiPlot>> getUserCustomersPlotsForCompany(CustomUserDetails authUser,
	                                                                  Long companyId,
	                                                                  Language language) throws ApiException {

		ApiListFarmersRequest request = new ApiListFarmersRequest();
		request.setLimit(10000);

		// First get the user customers of type FARMER
		List<ApiUserCustomer> farmers = getUserCustomersForCompanyAndType(
				companyId,
				UserCustomerType.FARMER,
				request,
				authUser,
				language).items;

		List<ApiPlot> companyFarmersPlots = new ArrayList<>();
		for (ApiUserCustomer farmer: farmers) {
			if (!CollectionUtils.isEmpty(farmer.getPlots())) {
				for (ApiPlot plot: farmer.getPlots()) {

					// Set the farmer ID in the Plot object and add it to the combined collection of plots
					plot.setFarmerId(farmer.getId());
					companyFarmersPlots.add(plot);
				}
			}
		}

		return new ApiResponse<>(companyFarmersPlots);
	}

	public byte[] exportFarmerDataByCompany(CustomUserDetails authUser, Long companyId, Language language) throws IOException, ApiException {

		ApiListFarmersRequest request = new ApiListFarmersRequest();
		request.setLimit(10000);

		List<ApiUserCustomer> farmers = getUserCustomersForCompanyAndType(
				companyId,
				UserCustomerType.FARMER,
				request,
				authUser,
				language).items;

		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream);

		// Prepare the farmers Excel file and add it to zip
		zipOutputStream.putNextEntry(new ZipEntry("farmers.xlsx"));
		zipOutputStream.write(prepareFarmersExcelFile(farmers, language));
		zipOutputStream.closeEntry();

		// Prepare the Geo-data JSON and add it to zip
		zipOutputStream.putNextEntry(new ZipEntry("geodata.geojson"));
		zipOutputStream.write(prepareFarmersGeoDataFile(farmers));
		zipOutputStream.closeEntry();

		zipOutputStream.close();

		return byteArrayOutputStream.toByteArray();
	}

	private byte[] prepareFarmersExcelFile(List<ApiUserCustomer> apiUserCustomers, Language language) throws IOException {

		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {

			// Create date cell style
			CellStyle dateCellStyle = workbook.createCellStyle();
			dateCellStyle.setDataFormat((short) 14);

			// Create the Farmers Excel sheet and the Plots Excel sheet
			XSSFSheet farmersSheet = workbook.createSheet(TranslateTools.getTranslatedValue(messageSource, "export.farmers.sheet.name", language));
			XSSFSheet plotsSheet = workbook.createSheet(TranslateTools.getTranslatedValue(messageSource, "export.plots.sheet.name", language));

			// Prepare the headers
			prepareFarmersSheetHeader(farmersSheet, language);
			preparePlotsSheetHeader(plotsSheet, language);

			// For each farmer add the farmer data and the farmer's plots data
			int farmersSheetRowNum = 1;
			int plotsSheetRowNum = 1;
			for (ApiUserCustomer apiUserCustomer : apiUserCustomers) {
				int nextPlotsSheetRowNum = fillFarmersExcelData(
						apiUserCustomer,
						farmersSheet,
						plotsSheet,
						dateCellStyle,
						farmersSheetRowNum,
						plotsSheetRowNum);
				farmersSheetRowNum++;
				if (nextPlotsSheetRowNum > plotsSheetRowNum) {
					plotsSheetRowNum = nextPlotsSheetRowNum;
				}
			}

			workbook.write(byteArrayOutputStream);
		}

		return byteArrayOutputStream.toByteArray();
	}

	private void prepareFarmersSheetHeader(XSSFSheet farmersSheet, Language language) {

		// Prepare the header row for the Farmers sheet
		Row farmersHeaderRow = farmersSheet.createRow(0);
		farmersHeaderRow.createCell(0, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.farmerId.label", language
		));
		farmersHeaderRow.createCell(1, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.companyInternalId.label", language
		));
		farmersHeaderRow.createCell(2, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.lastName.label", language
		));
		farmersHeaderRow.createCell(3, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.firstName.label", language
		));
		farmersHeaderRow.createCell(4, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.village.label", language
		));
		farmersHeaderRow.createCell(5, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.cell.label", language
		));
		farmersHeaderRow.createCell(6, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.sector.label", language
		));
		farmersHeaderRow.createCell(7, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.hondurasFarm.label", language
		));
		farmersHeaderRow.createCell(8, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.hondurasVillage.label", language
		));
		farmersHeaderRow.createCell(9, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.hondurasMunicipality.label", language
		));
		farmersHeaderRow.createCell(10, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.hondurasDepartment.label", language
		));
		farmersHeaderRow.createCell(11, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.streetAddress.label", language
		));
		farmersHeaderRow.createCell(12, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.cityTownVillage.label", language
		));
		farmersHeaderRow.createCell(13, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.stateProvinceRegion.label", language
		));
		farmersHeaderRow.createCell(14, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.zipPostalCode.label", language
		));
		farmersHeaderRow.createCell(15, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.additionalAddress.label", language
		));
		farmersHeaderRow.createCell(16, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.country.label", language
		));
		farmersHeaderRow.createCell(17, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.gender.label", language
		));
		farmersHeaderRow.createCell(18, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.phoneNumber.label", language
		));
		farmersHeaderRow.createCell(19, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.email.label", language
		));
		farmersHeaderRow.createCell(20, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.areaUnit.label", language
		));
		farmersHeaderRow.createCell(21, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.totalCultivatedArea.label", language
		));
		farmersHeaderRow.createCell(22, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.organicProduction.label", language
		));
		farmersHeaderRow.createCell(23, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.areaOrganicCertified.label", language
		));
		farmersHeaderRow.createCell(24, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.startDateOfTransitionToOrganic.label", language
		));
		farmersHeaderRow.createCell(25, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.accountNumber.label", language
		));
		farmersHeaderRow.createCell(26, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.accountHoldersName.label", language
		));
		farmersHeaderRow.createCell(27, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.bankName.label", language
		));
		farmersHeaderRow.createCell(28, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.additionalInformation.label", language
		));
	}

	private void preparePlotsSheetHeader(XSSFSheet plotsSheet, Language language) {

		// Prepare the header row for the Plots sheet
		Row plotsHeaderRow = plotsSheet.createRow(0);
		plotsHeaderRow.createCell(0, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.farmerId.label", language
		));
		plotsHeaderRow.createCell(1, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.plots.column.plotId.label", language
		));
		plotsHeaderRow.createCell(2, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.plots.column.plotName.label", language
		));
		plotsHeaderRow.createCell(3, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.plots.column.crop.label", language
		));
		plotsHeaderRow.createCell(4, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.plots.column.numberOfPlants.label", language
		));
		plotsHeaderRow.createCell(5, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.plots.column.unit.label", language
		));
		plotsHeaderRow.createCell(6, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.plots.column.size.label", language
		));
		plotsHeaderRow.createCell(7, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.plots.column.geoId.label", language
		));
		plotsHeaderRow.createCell(8, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.plots.column.dateOfTransitionToOrganic.label", language
		));
        plotsHeaderRow.createCell(9, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
                messageSource, "export.plots.column.centralLatitude.label", language
        ));
        plotsHeaderRow.createCell(10, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
                messageSource, "export.plots.column.centralLongitude.label", language
        ));
        plotsHeaderRow.createCell(11, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
                messageSource, "export.plots.column.synchroDate.label", language
        ));
        plotsHeaderRow.createCell(12, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
                messageSource, "export.plots.column.collector.label", language
        ));
	}

	private int fillFarmersExcelData(ApiUserCustomer apiUserCustomer,
	                                 XSSFSheet farmersSheet,
	                                 XSSFSheet plotsSheet,
	                                 CellStyle dateCellStyle,
	                                 int farmersSheetRowNum,
	                                 int plotsSheetRowNum) {

		Row farmerRow = farmersSheet.createRow(farmersSheetRowNum);

		// Create farmer ID column
		farmerRow.createCell(0, CellType.STRING).setCellValue(apiUserCustomer.getId());
		// farmersSheet.autoSizeColumn(0);

		// Create company internal ID column
		farmerRow.createCell(1, CellType.STRING).setCellValue(apiUserCustomer.getFarmerCompanyInternalId());
		// farmersSheet.autoSizeColumn(1);

		// Create last name column
		farmerRow.createCell(2, CellType.STRING).setCellValue(apiUserCustomer.getSurname());
		// farmersSheet.autoSizeColumn(2);

		// Create first name column
		farmerRow.createCell(3, CellType.STRING).setCellValue(apiUserCustomer.getName());
		// farmersSheet.autoSizeColumn(3);

		// Create village column
		farmerRow.createCell(4, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getVillage());
		// farmersSheet.autoSizeColumn(4);

		// Create cell column
		farmerRow.createCell(5, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getCell());
		// farmersSheet.autoSizeColumn(5);

		// Create sector column
		farmerRow.createCell(6, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getSector());
		// farmersSheet.autoSizeColumn(6);

		// Create Honduras farm column
		farmerRow.createCell(7, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getHondurasFarm());
		// farmersSheet.autoSizeColumn(7);

		// Create Honduras village column
		farmerRow.createCell(8, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getHondurasVillage());
		// farmersSheet.autoSizeColumn(8);

		// Create Honduras municipality column
		farmerRow.createCell(9, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getHondurasMunicipality());
		// farmersSheet.autoSizeColumn(9);

		// Create Honduras department column
		farmerRow.createCell(10, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getHondurasDepartment());
		// farmersSheet.autoSizeColumn(10);

		// Create street address column
		farmerRow.createCell(11, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getAddress());
		// farmersSheet.autoSizeColumn(11);

		// Create city/town/village column
		farmerRow.createCell(12, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getCity());
		// farmersSheet.autoSizeColumn(12);

		// Create state/province/region column
		farmerRow.createCell(13, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getState());
		// farmersSheet.autoSizeColumn(13);

		// Create ZIP/postal code column
		farmerRow.createCell(14, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getZip());
		// farmersSheet.autoSizeColumn(14);

		// Create additional address column
		farmerRow.createCell(15, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getOtherAddress());
		// farmersSheet.autoSizeColumn(15);

		// Create country column
		farmerRow.createCell(16, CellType.STRING).setCellValue(apiUserCustomer.getLocation().getAddress().getCountry().getName());
		// farmersSheet.autoSizeColumn(16);

		// Create gender column
		farmerRow.createCell(17, CellType.STRING).setCellValue(TranslateTools.getTranslatedValue(
				messageSource, "export.farmers.column.gender.value." + apiUserCustomer.getGender().toString(), Language.EN
		));
		// farmersSheet.autoSizeColumn(17);

		// Create phone number column
		farmerRow.createCell(18, CellType.STRING).setCellValue(apiUserCustomer.getPhone());
		// farmersSheet.autoSizeColumn(18);

		// Create email column
		farmerRow.createCell(19, CellType.STRING).setCellValue(apiUserCustomer.getEmail());
		// farmersSheet.autoSizeColumn(19);

		// Create area unit column
		farmerRow.createCell(20, CellType.STRING);

		// Create total cultivated area column
		farmerRow.createCell(21, CellType.NUMERIC);

		// Create organic production column
		farmerRow.createCell(22, CellType.STRING);

		// Create area organic certified
		farmerRow.createCell(23, CellType.NUMERIC);

		// Create start date of transition to organic column
		farmerRow.createCell(24, CellType.NUMERIC);
		farmerRow.getCell(24).setCellStyle(dateCellStyle);

		// If farm info is present set column values for farm info columns
		if (apiUserCustomer.getFarm() != null) {

			farmerRow.getCell(20).setCellValue(apiUserCustomer.getFarm().getAreaUnit());
			// farmersSheet.autoSizeColumn(20);

			if (apiUserCustomer.getFarm().getTotalCultivatedArea() != null) {
				farmerRow.getCell(21).setCellValue(apiUserCustomer.getFarm().getTotalCultivatedArea().doubleValue());
				// farmersSheet.autoSizeColumn(21);
			}

			farmerRow.getCell(22).setCellValue(BooleanUtils.isTrue(apiUserCustomer.getFarm().getOrganic()) ? "Y" : "N");
			// farmersSheet.autoSizeColumn(22);

			if (apiUserCustomer.getFarm().getAreaOrganicCertified() != null) {
				farmerRow.getCell(23).setCellValue(apiUserCustomer.getFarm().getAreaOrganicCertified().doubleValue());
				// farmersSheet.autoSizeColumn(23);
			}

			farmerRow.getCell(24).setCellValue(apiUserCustomer.getFarm().getStartTransitionToOrganic());
			// farmersSheet.autoSizeColumn(24);
		}

		// Create account number column
		farmerRow.createCell(25, CellType.STRING);

		// Create account holder's column
		farmerRow.createCell(26, CellType.STRING);

		// Create bank name column
		farmerRow.createCell(27, CellType.STRING);

		// Create additional information column
		farmerRow.createCell(28, CellType.STRING);

		// If bank information is present set values for bank info columns
		if (apiUserCustomer.getBank() != null) {

			farmerRow.getCell(25).setCellValue(apiUserCustomer.getBank().getAccountNumber());
			// farmersSheet.autoSizeColumn(25);

			farmerRow.getCell(26).setCellValue(apiUserCustomer.getBank().getAccountHolderName());
			// farmersSheet.autoSizeColumn(26);

			farmerRow.getCell(27).setCellValue(apiUserCustomer.getBank().getBankName());
			// farmersSheet.autoSizeColumn(27);

			farmerRow.getCell(28).setCellValue(apiUserCustomer.getBank().getAdditionalInformation());
			// farmersSheet.autoSizeColumn(28);
		}

		// Fill farmer's plots data
		for (ApiPlot apiPlot : apiUserCustomer.getPlots()) {

			Row plotRow = plotsSheet.createRow(plotsSheetRowNum++);

			// Create farmer ID column (used to connect the farmer data from the Farmers sheet and the plot data in the Plots sheet)
			plotRow.createCell(0, CellType.STRING).setCellValue(apiUserCustomer.getFarmerCompanyInternalId());
			// plotsSheet.autoSizeColumn(0);

			// Create plot ID column
			plotRow.createCell(1, CellType.STRING).setCellValue(apiPlot.getId());
			// plotsSheet.autoSizeColumn(1);

			// Create plot name column
			plotRow.createCell(2, CellType.STRING).setCellValue(apiPlot.getPlotName());
			// plotsSheet.autoSizeColumn(2);

			// Create plot crop column
			plotRow.createCell(3, CellType.STRING);
			if (apiPlot.getCrop() != null) {
				plotRow.getCell(3).setCellValue(apiPlot.getCrop().getName());
				// plotsSheet.autoSizeColumn(3);
			}

			// Create number of plants column
			plotRow.createCell(4, CellType.NUMERIC);
			if (apiPlot.getNumberOfPlants() != null) {
				plotRow.getCell(4).setCellValue(apiPlot.getNumberOfPlants());
				// plotsSheet.autoSizeColumn(4);
			}

			// Create unit column
			plotRow.createCell(5, CellType.STRING).setCellValue(apiPlot.getUnit());
			// plotsSheet.autoSizeColumn(5);

			// Create size column
			plotRow.createCell(6, CellType.NUMERIC);
			if (apiPlot.getSize() != null) {
				plotRow.getCell(6).setCellValue(apiPlot.getSize());
				// plotsSheet.autoSizeColumn(6);
			}

			// Create Geo-ID column
			plotRow.createCell(7, CellType.STRING).setCellValue(apiPlot.getGeoId());
			// plotsSheet.autoSizeColumn(7);

			// Create date of transition to organic
			plotRow.createCell(8, CellType.NUMERIC).setCellValue(apiPlot.getOrganicStartOfTransition());
			plotRow.getCell(8).setCellStyle(dateCellStyle);
			// plotsSheet.autoSizeColumn(8);

            // Create center latitude point
            plotRow.createCell(9, CellType.NUMERIC);
            if (apiPlot.getCenterLatitude() != null) {
                plotRow.getCell(9).setCellValue(formatCoordinate6Decimals(apiPlot.getCenterLatitude()));
            }
            // Create center latitude point
            plotRow.createCell(10, CellType.NUMERIC);
            if (apiPlot.getCenterLongitude() != null) {
                plotRow.getCell(10).setCellValue(formatCoordinate6Decimals(apiPlot.getCenterLongitude()));
            }

            plotRow.createCell(11, CellType.STRING);
            if (apiPlot.getSynchronisationDate() != null) {
                SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
                String formattedDate = formatter.format(apiPlot.getSynchronisationDate());
                plotRow.getCell(11).setCellValue(formattedDate);
            }

            plotRow.createCell(12, CellType.STRING);
            if (apiPlot.getCollectorId() != null) {
                String the_name = null;
                try {
                    User user = userQueries.fetchUser(apiPlot.getCollectorId());
                    if (user != null && user.getName() != null) {
                        the_name = String.valueOf(user.getName());
                    } else {
                        the_name = ""; // ou une valeur par défaut
                    }
                } catch (ApiException e) {
                    the_name = "";
                }
                plotRow.getCell(12).setCellValue(the_name);
            }
		}

		return plotsSheetRowNum;
	}

	private byte[] prepareFarmersGeoDataFile(List<ApiUserCustomer> apiUserCustomers) throws ApiException {
		List<Feature> features = new ArrayList<>(apiUserCustomers.size() * 2); // Estimation de capacité initiale

		for (ApiUserCustomer customer : apiUserCustomers) {
			long farmerId = customer.getId();
            String farmerInternalId= customer.getFarmerCompanyInternalId();

			for (ApiPlot plot : customer.getPlots()) {
				List<ApiPlotCoordinate> coordinates = plot.getCoordinates();
				if (coordinates.isEmpty()) {
					continue;
				}

				Feature feature = createFeatureFromPlot(coordinates);
				if (feature != null) {
					enrichFeatureWithProperties(feature,farmerId, farmerInternalId,  plot);
					features.add(feature);
				}
			}
		}

		return FeatureCollection.fromFeatures(features).toJson().getBytes();
	}

	private Feature createFeatureFromPlot(List<ApiPlotCoordinate> coordinates) {
		if (coordinates.size() < 3) {
			// Cas Point
			ApiPlotCoordinate coord = coordinates.get(0);
			return Feature.fromGeometry(Point.fromLngLat(coord.getLongitude(), coord.getLatitude()));
		} else {
			// Cas Polygon
			List<Point> polygonPoints = new ArrayList<>(coordinates.size() + 1);

			// Convertir toutes les coordonnées
			for (ApiPlotCoordinate coord : coordinates) {
                String lato = formatCoordinate6Decimals(coord.getLatitude());
                String longo = formatCoordinate6Decimals(coord.getLongitude());
				polygonPoints.add(Point.fromLngLat(Double.parseDouble(longo), Double.parseDouble(lato)));
			}

			// Fermer le polygone en ajoutant le premier point à la fin
			//polygonPoints.add(polygonPoints.get(0));

			return Feature.fromGeometry(Polygon.fromLngLats(List.of(polygonPoints)));
		}
	}

	private void enrichFeatureWithProperties(Feature feature, long farmerId, String farmerInternalId, ApiPlot plot) {
		feature.addNumberProperty("farmerID", farmerId);
        feature.addStringProperty("farmerInternalID", farmerInternalId);
		feature.addNumberProperty("plotID", plot.getId());
        feature.addNumberProperty("area", plot.getSize());
        feature.addStringProperty("unit", plot.getUnit());
		feature.addStringProperty("geoID", Optional.ofNullable(plot.getGeoId()).orElse(""));
	}

	@Transactional
	public ApiUserCustomer addUserCustomer(Long companyId, ApiUserCustomer apiUserCustomer, CustomUserDetails user, Language language) throws ApiException {

		Company company = companyQueries.fetchCompany(companyId);
		PermissionsUtil.checkUserIfCompanyEnrolled(company.getUsers().stream().toList(), user);

		UserCustomer userCustomer = new UserCustomer();
		userCustomer.setCompany(company);
		userCustomer.setFarmerCompanyInternalId(apiUserCustomer.getFarmerCompanyInternalId());
		userCustomer.setGender(apiUserCustomer.getGender());
		userCustomer.setType(apiUserCustomer.getType());
		userCustomer.setEmail(apiUserCustomer.getEmail());
		userCustomer.setName(apiUserCustomer.getName());
		userCustomer.setSurname(apiUserCustomer.getSurname());
		userCustomer.setPhone(apiUserCustomer.getPhone());
		userCustomer.setHasSmartphone(apiUserCustomer.getHasSmartphone());

		if (apiUserCustomer.getBank() != null) {
			userCustomer.setBank(new BankInformation());
			userCustomer.getBank().setAccountHolderName(apiUserCustomer.getBank().getAccountHolderName());
			userCustomer.getBank().setAccountNumber(apiUserCustomer.getBank().getAccountNumber());
			userCustomer.getBank().setAdditionalInformation(apiUserCustomer.getBank().getAdditionalInformation());
			userCustomer.getBank().setBankName(apiUserCustomer.getBank().getBankName());
		}

		if (apiUserCustomer.getFarm() != null) {
			userCustomer.setFarm(new FarmInformation());
			userCustomer.getFarm().setAreaUnit(apiUserCustomer.getFarm().getAreaUnit());
			userCustomer.getFarm().setAreaOrganicCertified(apiUserCustomer.getFarm().getAreaOrganicCertified());
			userCustomer.getFarm().setOrganic(apiUserCustomer.getFarm().getOrganic());
			userCustomer.getFarm().setStartTransitionToOrganic(apiUserCustomer.getFarm().getStartTransitionToOrganic());
			userCustomer.getFarm().setTotalCultivatedArea(apiUserCustomer.getFarm().getTotalCultivatedArea());
		}

		UserCustomerLocation userCustomerLocation = new UserCustomerLocation();
		if (apiUserCustomer.getLocation() != null) {
            BigDecimal lat = BigDecimal.valueOf(apiUserCustomer.getLocation().getLatitude())
                    .setScale(6, RoundingMode.HALF_UP);
            BigDecimal lon = BigDecimal.valueOf(apiUserCustomer.getLocation().getLongitude())
                    .setScale(6, RoundingMode.HALF_UP);

			userCustomerLocation.setLatitude(lat.doubleValue());
			userCustomerLocation.setLongitude(lon.doubleValue());
			userCustomerLocation.setPubliclyVisible(apiUserCustomer.getLocation().getPubliclyVisible());
			if (apiUserCustomer.getLocation().getAddress() != null) {
				userCustomerLocation.setAddress(new Address());
				userCustomerLocation.getAddress().setAddress(apiUserCustomer.getLocation().getAddress().getAddress());
				userCustomerLocation.getAddress().setCell(apiUserCustomer.getLocation().getAddress().getCell());
				userCustomerLocation.getAddress().setCity(apiUserCustomer.getLocation().getAddress().getCity());
				userCustomerLocation.getAddress().setCountry(getCountry(apiUserCustomer.getLocation().getAddress().getCountry().getId()));
				userCustomerLocation.getAddress().setSector(apiUserCustomer.getLocation().getAddress().getSector());
				userCustomerLocation.getAddress().setState(apiUserCustomer.getLocation().getAddress().getState());
				userCustomerLocation.getAddress().setVillage(apiUserCustomer.getLocation().getAddress().getVillage());
				userCustomerLocation.getAddress().setOtherAddress(apiUserCustomer.getLocation().getAddress().getOtherAddress());
				userCustomerLocation.getAddress().setZip(apiUserCustomer.getLocation().getAddress().getZip());
				userCustomerLocation.getAddress().setHondurasDepartment(apiUserCustomer.getLocation().getAddress().getHondurasDepartment());
				userCustomerLocation.getAddress().setHondurasFarm(apiUserCustomer.getLocation().getAddress().getHondurasFarm());
				userCustomerLocation.getAddress().setHondurasMunicipality(apiUserCustomer.getLocation().getAddress().getHondurasMunicipality());
				userCustomerLocation.getAddress().setHondurasVillage(apiUserCustomer.getLocation().getAddress().getHondurasVillage());
			}
		}
		em.persist(userCustomerLocation);

		userCustomer.setUserCustomerLocation(userCustomerLocation);
		em.persist(userCustomer);

		// Set product types
		if (apiUserCustomer.getProductTypes() != null) {
			for(ApiProductType apiProductType: apiUserCustomer.getProductTypes()){
				UserCustomerProductType userCustomerProductType = new UserCustomerProductType();
				userCustomerProductType.setProductType(fetchProductType(apiProductType.getId()));
				userCustomerProductType.setUserCustomer(userCustomer);
				em.persist(userCustomerProductType);
			}
		}

		// Set farm plants information
		if (apiUserCustomer.getFarm() != null && !apiUserCustomer.getFarm().getFarmPlantInformationList().isEmpty()) {
			userCustomer.setFarmPlantInformationList(new HashSet<>());

			for(ApiFarmPlantInformation apiPlantInfo: apiUserCustomer.getFarm().getFarmPlantInformationList()) {
				FarmPlantInformation farmPlantInformation = new FarmPlantInformation();
				farmPlantInformation.setNumberOfPlants(apiPlantInfo.getNumberOfPlants());
				farmPlantInformation.setPlantCultivatedArea(apiPlantInfo.getPlantCultivatedArea());
				farmPlantInformation.setProductType(fetchProductType(apiPlantInfo.getProductType().getId()));
				farmPlantInformation.setUserCustomer(userCustomer);

				userCustomer.getFarmPlantInformationList().add(farmPlantInformation);
			}
		}

		// Add the company as default cooperative
		userCustomer.setCooperatives(new HashSet<>());
		UserCustomerCooperative userCustomerCooperative = new UserCustomerCooperative();
		userCustomerCooperative.setCompany(company);
		userCustomerCooperative.setRole(apiUserCustomer.getType());
		userCustomerCooperative.setUserCustomer(userCustomer);
		userCustomer.getCooperatives().add(userCustomerCooperative);
		em.persist(userCustomerCooperative);

		// Set associations
		userCustomer.setAssociations(new HashSet<>());
		if (apiUserCustomer.getAssociations() != null) {
			for (ApiUserCustomerAssociation apiUserCustomerAssociation : apiUserCustomer.getAssociations()) {
				UserCustomerAssociation userCustomerAssociation = new UserCustomerAssociation();
				userCustomerAssociation.setCompany(em.find(Company.class, apiUserCustomerAssociation.getCompany().getId()));
				userCustomerAssociation.setUserCustomer(userCustomer);
				userCustomer.getAssociations().add(userCustomerAssociation);
				em.persist(userCustomerAssociation);
			}
		}

		// Set certifications
		if (!CollectionUtils.isEmpty(apiUserCustomer.getCertifications())) {
			for (ApiCertification apiCertification : apiUserCustomer.getCertifications()) {
				UserCustomerCertification certification = new UserCustomerCertification();
				certification.setUserCustomer(userCustomer);
				certification.setDescription(apiCertification.getDescription());
				certification.setType(apiCertification.getType());
				certification.setValidity(apiCertification.getValidity());
				certification.setCertificate(commonService.fetchDocument(user.getUserId(), apiCertification.getCertificate()));
				userCustomer.getCertifications().add(certification);
			}
		}

		// If user customer is of type add plots
		if (apiUserCustomer.getType().equals(UserCustomerType.FARMER) && !CollectionUtils.isEmpty(apiUserCustomer.getPlots())) {
			for (ApiPlot apiPlot : apiUserCustomer.getPlots()) {
				Plot plot = new Plot();
				plot.setPlotName(apiPlot.getPlotName());
				plot.setCrop(fetchProductType(apiPlot.getCrop().getId()));
				plot.setNumberOfPlants(apiPlot.getNumberOfPlants());
				plot.setUnit(apiPlot.getUnit());
				plot.setSize(apiPlot.getSize());
				plot.setOrganicStartOfTransition(apiPlot.getOrganicStartOfTransition());
				plot.setFarmer(userCustomer);

                double[] centroid = MapTools.calculatePolygonCentroid(apiPlot.getCoordinates());
                BigDecimal latCenter = BigDecimal.valueOf(centroid[0])
                        .setScale(6, RoundingMode.HALF_UP);
                BigDecimal lonCenter = BigDecimal.valueOf(centroid[1])
                        .setScale(6, RoundingMode.HALF_UP);
                plot.setCenterLatitude(latCenter.doubleValue());
                plot.setCenterLongitude(lonCenter.doubleValue());
                plot.setSynchronisationDate(new Date());
                plot.setCollectorId(getCurrentUserId());
				plot.setLastUpdated(new Date());

                // Initialiser avec LinkedHashSet pour préserver l'ordre
                plot.setCoordinates(new LinkedHashSet<>());

				for (ApiPlotCoordinate apiPlotCoordinate : apiPlot.getCoordinates()) {
					PlotCoordinate plotCoordinate = new PlotCoordinate();
                    BigDecimal lat = BigDecimal.valueOf(apiPlotCoordinate.getLatitude())
                            .setScale(6, RoundingMode.HALF_UP);
                    BigDecimal lon = BigDecimal.valueOf(apiPlotCoordinate.getLongitude())
                            .setScale(6, RoundingMode.HALF_UP);
                    plotCoordinate.setLatitude(lat.doubleValue());
                    plotCoordinate.setLongitude(lon.doubleValue());
					plotCoordinate.setPlot(plot);
					plot.getCoordinates().add(plotCoordinate);

				}

				// Generate Plot GeoID
				plot.setGeoId(generatePlotGeoID(plot.getCoordinates()));

				userCustomer.getPlots().add(plot);
			}
		}

		return companyApiTools.toApiUserCustomer(userCustomer, user.getUserId(), language);
	}

	@Transactional
	public ApiUserCustomer updateUserCustomer(ApiUserCustomer apiUserCustomer, CustomUserDetails user, Language language, Long userId) throws ApiException {

		if (apiUserCustomer == null) {
			return null;
		}

		UserCustomer userCustomer = fetchUserCustomer(apiUserCustomer.getId());
		PermissionsUtil.checkUserIfCompanyEnrolled(userCustomer.getCompany().getUsers().stream().toList(), user);

		userCustomer.setName(apiUserCustomer.getName());
		userCustomer.setSurname(apiUserCustomer.getSurname());
		userCustomer.setEmail(apiUserCustomer.getEmail());
		userCustomer.setFarmerCompanyInternalId(apiUserCustomer.getFarmerCompanyInternalId());
		userCustomer.setPhone(apiUserCustomer.getPhone());
		userCustomer.setHasSmartphone(apiUserCustomer.getHasSmartphone());
		userCustomer.setGender(apiUserCustomer.getGender());
		userCustomer.setType(apiUserCustomer.getType());

		if (userCustomer.getBank() == null) {
			userCustomer.setBank(new BankInformation());
		}
		userCustomer.getBank().setAccountHolderName(apiUserCustomer.getBank().getAccountHolderName());
		userCustomer.getBank().setAccountNumber(apiUserCustomer.getBank().getAccountNumber());
		userCustomer.getBank().setAdditionalInformation(apiUserCustomer.getBank().getAdditionalInformation());
		userCustomer.getBank().setBankName(apiUserCustomer.getBank().getBankName());

		if (userCustomer.getFarm() == null) {
			userCustomer.setFarm(new FarmInformation());
		}
		userCustomer.getFarm().setAreaUnit(apiUserCustomer.getFarm().getAreaUnit());
		userCustomer.getFarm().setAreaOrganicCertified(apiUserCustomer.getFarm().getAreaOrganicCertified());
		userCustomer.getFarm().setOrganic(apiUserCustomer.getFarm().getOrganic());
		userCustomer.getFarm().setStartTransitionToOrganic(apiUserCustomer.getFarm().getStartTransitionToOrganic());
		userCustomer.getFarm().setTotalCultivatedArea(apiUserCustomer.getFarm().getTotalCultivatedArea());

		if (userCustomer.getUserCustomerLocation() == null) {
			userCustomer.setUserCustomerLocation(new UserCustomerLocation());
		}
		if (userCustomer.getUserCustomerLocation().getAddress() == null) {
			userCustomer.getUserCustomerLocation().setAddress(new Address());
		}

		// Set generic address fields
		userCustomer.getUserCustomerLocation().getAddress().setAddress(apiUserCustomer.getLocation().getAddress().getAddress());
		userCustomer.getUserCustomerLocation().getAddress().setCity(apiUserCustomer.getLocation().getAddress().getCity());
		userCustomer.getUserCustomerLocation().getAddress().setState(apiUserCustomer.getLocation().getAddress().getState());
		userCustomer.getUserCustomerLocation().getAddress().setOtherAddress(apiUserCustomer.getLocation().getAddress().getOtherAddress());

		// Set Rwanda specific address fields
		userCustomer.getUserCustomerLocation().getAddress().setVillage(apiUserCustomer.getLocation().getAddress().getVillage());
		userCustomer.getUserCustomerLocation().getAddress().setCell(apiUserCustomer.getLocation().getAddress().getCell());
		userCustomer.getUserCustomerLocation().getAddress().setSector(apiUserCustomer.getLocation().getAddress().getSector());

		// Set Honduras specific address fields
		userCustomer.getUserCustomerLocation().getAddress().setHondurasDepartment(apiUserCustomer.getLocation().getAddress().getHondurasDepartment());
		userCustomer.getUserCustomerLocation().getAddress().setHondurasFarm(apiUserCustomer.getLocation().getAddress().getHondurasFarm());
		userCustomer.getUserCustomerLocation().getAddress().setHondurasMunicipality(apiUserCustomer.getLocation().getAddress().getHondurasMunicipality());
		userCustomer.getUserCustomerLocation().getAddress().setHondurasVillage(apiUserCustomer.getLocation().getAddress().getHondurasVillage());

		// Set the address country
		Country country = getCountry(apiUserCustomer.getLocation().getAddress().getCountry().getId());
		userCustomer.getUserCustomerLocation().getAddress().setCountry(country);

		userCustomer.getUserCustomerLocation().setLatitude(apiUserCustomer.getLocation().getLatitude());
		userCustomer.getUserCustomerLocation().setLongitude(apiUserCustomer.getLocation().getLongitude());
		userCustomer.getUserCustomerLocation().setPubliclyVisible(apiUserCustomer.getLocation().getPubliclyVisible());

		// Set product types
		if (apiUserCustomer.getProductTypes() != null) {
			updateUserCustomerProductTypes(apiUserCustomer, userCustomer);
		}

		// Update farm plant information
		if (apiUserCustomer.getFarm() != null && !apiUserCustomer.getFarm().getFarmPlantInformationList().isEmpty()) {
			updateUserCustomerPlantInformation(apiUserCustomer, userCustomer);
		}

		if (userCustomer.getAssociations() == null) {
			userCustomer.setAssociations(new HashSet<>());
		}

		// Update user customer associations
		userCustomer.getAssociations().removeIf(userCustomerAssociation -> apiUserCustomer.getAssociations().stream().noneMatch(apiUserCustomerAssociation -> userCustomerAssociation.getId().equals(apiUserCustomerAssociation.getId())));
		for (ApiUserCustomerAssociation apiUserCustomerAssociation : apiUserCustomer.getAssociations()) {
			if (userCustomer.getAssociations().stream().noneMatch(userCustomerAssociation -> userCustomerAssociation.getId().equals(apiUserCustomerAssociation.getId()))) {
				UserCustomerAssociation userCustomerAssociation = new UserCustomerAssociation();
				userCustomerAssociation.setCompany(em.find(Company.class, apiUserCustomerAssociation.getCompany().getId()));
				userCustomerAssociation.setUserCustomer(userCustomer);
				userCustomer.getAssociations().add(userCustomerAssociation);
				em.persist(userCustomerAssociation);
			}
		}

		if (userCustomer.getCooperatives() == null) {
			userCustomer.setCooperatives(new HashSet<>());
		}

		// Update user customer cooperatives
		userCustomer.getCooperatives().removeIf(userCustomerCooperative -> apiUserCustomer.getCooperatives().stream().noneMatch(apiUserCustomerCooperative -> userCustomerCooperative.getId().equals(apiUserCustomerCooperative.getId())));
		for (ApiUserCustomerCooperative apiUserCustomerCooperative : apiUserCustomer.getCooperatives()) {
			if (userCustomer.getCooperatives().stream().noneMatch(userCustomerCooperative -> userCustomerCooperative.getId().equals(apiUserCustomerCooperative.getId()))) {
				UserCustomerCooperative userCustomerCooperative = new UserCustomerCooperative();
				userCustomerCooperative.setUserCustomer(userCustomer);
				userCustomerCooperative.setCompany(em.find(Company.class, apiUserCustomerCooperative.getCompany().getId()));
				userCustomerCooperative.setRole(apiUserCustomerCooperative.getUserCustomerType());
				userCustomer.getCooperatives().add(userCustomerCooperative);
				em.persist(userCustomerCooperative);
			}
		}

		userCustomer.getPlots().removeIf(
				plot -> apiUserCustomer.getPlots().stream().noneMatch(apiPlot -> plot.getId().equals(apiPlot.getId())));

		for (ApiPlot apiPlot: apiUserCustomer.getPlots()) {

			Plot plot = userCustomer.getPlots().stream()
					.filter(p -> p.getId() !=  null && p.getId().equals(apiPlot.getId())).findFirst()
					.orElse(new Plot());

			plot.getCoordinates().removeIf(coordinate -> apiPlot.getCoordinates().stream()
					.noneMatch(apiCoordinate -> coordinate.getId().equals(apiCoordinate.getId())));

            plot.setCoordinates(new LinkedHashSet<>());

			for (ApiPlotCoordinate apiPlotCoordinate : apiPlot.getCoordinates()) {
				PlotCoordinate plotCoordinate = plot.getCoordinates().stream()
						.filter(p -> p.getId() != null && p.getId()
								.equals(apiPlotCoordinate.getId()))
						.findFirst()
						.orElse(new PlotCoordinate());

                BigDecimal lat = BigDecimal.valueOf(apiPlotCoordinate.getLatitude())
                        .setScale(6, RoundingMode.HALF_UP);
                BigDecimal lon = BigDecimal.valueOf(apiPlotCoordinate.getLongitude())
                        .setScale(6, RoundingMode.HALF_UP);

				plotCoordinate.setLatitude(lat.doubleValue());
				plotCoordinate.setLongitude(lon.doubleValue());

				if (plotCoordinate.getId() == null) {
					plotCoordinate.setPlot(plot);
					plot.getCoordinates().add(plotCoordinate);
				}
			}

			plot.setPlotName(apiPlot.getPlotName());
			plot.setNumberOfPlants(apiPlot.getNumberOfPlants());
			plot.setSize(apiPlot.getSize());
			plot.setLastUpdated(new Date());
			plot.setOrganicStartOfTransition(apiPlot.getOrganicStartOfTransition());
			plot.setUnit(apiPlot.getUnit());

            if(plot.getSynchronisationDate() ==null){
                plot.setCollectorId(userId);
                plot.setSynchronisationDate(new Date());
            }
            // Calculer le centroid avec JTS
            if (apiPlot.getCoordinates() != null && !apiPlot.getCoordinates().isEmpty()) {
                double[] centroid = MapTools.calculatePolygonCentroid(apiPlot.getCoordinates());
                BigDecimal latCenter = BigDecimal.valueOf(centroid[0])
                        .setScale(6, RoundingMode.HALF_UP);
                BigDecimal lonCenter = BigDecimal.valueOf(centroid[1])
                        .setScale(6, RoundingMode.HALF_UP);
                plot.setCenterLatitude(latCenter.doubleValue());
                plot.setCenterLongitude(lonCenter.doubleValue());

                // Optionnel: calculer la superficie
//            double area = PlotGeometryUtils.calculateArea(request.getCoordinates());
//            plot.setCalculatedArea(area); // Ajoutez ce champ si nécessaire
            } else {
                plot.setCenterLatitude(0.0);
                plot.setCenterLongitude(0.0);
            }


			if (plot.getId() != null) {
				refreshGeoIDForUserCustomerPlot(userCustomer.getId(), plot.getId(), user, language);
			} else {
				plot.setGeoId(generatePlotGeoID(plot.getCoordinates()));
			}

			if (apiPlot.getCrop() != null) {
				plot.setCrop(fetchProductType(apiPlot.getCrop().getId()));
			}

			if (plot.getId() == null) {
				plot.setFarmer(userCustomer);
				userCustomer.getPlots().add(plot);
			}
		}

		// Update user customer certifications
		userCustomer.getCertifications().removeIf(ucc -> apiUserCustomer.getCertifications().stream().noneMatch(apiUCC -> ucc.getId().equals(apiUCC.getId())));
		for (ApiCertification apiCertification : apiUserCustomer.getCertifications()) {

			UserCustomerCertification certification;
			if (apiCertification.getId() == null) {
				certification = new UserCustomerCertification();
				certification.setUserCustomer(userCustomer);
				userCustomer.getCertifications().add(certification);
			} else {
				certification = userCustomer.getCertifications().stream()
						.filter(ucc -> ucc.getId().equals(apiCertification.getId())).findAny().orElseThrow();
			}

			certification.setCertificate(commonService.fetchDocument(user.getUserId(), apiCertification.getCertificate()));
			certification.setType(apiCertification.getType());
			certification.setDescription(apiCertification.getDescription());
			certification.setValidity(apiCertification.getValidity());
		}

		return companyApiTools.toApiUserCustomer(userCustomer, user.getUserId(), language);
	}

	private void updateUserCustomerProductTypes(ApiUserCustomer apiUserCustomer, UserCustomer userCustomer) throws ApiException {

		userCustomer.getProductTypes().clear();

		for (ApiProductType apiProductType : apiUserCustomer.getProductTypes()) {
			UserCustomerProductType userCustomerProductType = new UserCustomerProductType();
			userCustomerProductType.setProductType(fetchProductType(apiProductType.getId()));
			userCustomerProductType.setUserCustomer(userCustomer);

			userCustomer.getProductTypes().add(userCustomerProductType);
		}
	}

	private void updateUserCustomerPlantInformation(ApiUserCustomer apiUserCustomer, UserCustomer userCustomer) throws ApiException {

		// remove all old data
		userCustomer.getFarmPlantInformationList().clear();

		// add new
		for (ApiFarmPlantInformation apiPlantInfo: apiUserCustomer.getFarm().getFarmPlantInformationList()) {
			FarmPlantInformation farmPlantInformation = new FarmPlantInformation();
			farmPlantInformation.setNumberOfPlants(apiPlantInfo.getNumberOfPlants());
			farmPlantInformation.setPlantCultivatedArea(apiPlantInfo.getPlantCultivatedArea());
			farmPlantInformation.setProductType(fetchProductType(apiPlantInfo.getProductType().getId()));
			farmPlantInformation.setUserCustomer(userCustomer);

			userCustomer.getFarmPlantInformationList().add(farmPlantInformation);
		}
	}

	public byte[] exportUserCustomerGeoData(CustomUserDetails authUser, Long id) throws ApiException {

		UserCustomer userCustomer = fetchUserCustomer(id);
		PermissionsUtil.checkUserIfCompanyEnrolled(userCustomer.getCompany().getUsers().stream().toList(), authUser);

		// Prepare the GeoJSON object
		List<Feature> features = new ArrayList<>();

		for (Plot plot : userCustomer.getPlots()) {

			Feature feature;
            // Convertir le Set en List pour préserver l'ordre
            List<PlotCoordinate> orderedCoordinates = new ArrayList<>(plot.getCoordinates());

            if (orderedCoordinates.size() < 3) {
                feature = Feature.fromGeometry(Point.fromLngLat(
                        orderedCoordinates.get(0).getLongitude(),
                        orderedCoordinates.get(0).getLatitude()
                ));
            } else {
                List<Point> polygonCoordinates = orderedCoordinates
                        .stream()
                        .map(plotCoordinate -> Point.fromLngLat(
                                plotCoordinate.getLongitude(),
                                plotCoordinate.getLatitude()
                        ))
                        .collect(Collectors.toList());

//				polygonCoordinates.add(Point.fromLngLat(
//						plot.getCoordinates().stream().toList().get(0).getLongitude(),
//						plot.getCoordinates().stream().toList().get(0).getLatitude()));

				feature = Feature.fromGeometry(Polygon.fromLngLats(List.of(polygonCoordinates)));
			}
			feature.addStringProperty("geoID", Optional.ofNullable(plot.getGeoId()).orElse(""));
            feature.addStringProperty("FarmerID", Optional.ofNullable(userCustomer.getFarmerCompanyInternalId()).orElse(""));
			features.add(feature);
		}

		return FeatureCollection.fromFeatures(features).toJson().getBytes();
	}

	@Transactional
	public void uploadUserCustomerGeoData(CustomUserDetails authUser, Long id, MultipartFile file, Long userId) throws ApiException {

		UserCustomer userCustomer = fetchUserCustomer(id);
		PermissionsUtil.checkUserIfCompanyEnrolled(userCustomer.getCompany().getUsers().stream().toList(), authUser);

		// Try to read the GeoJSON into Feature collection
        try {

	        FeatureCollection featureCollection = FeatureCollection.fromJson(new String(file.getBytes()));

			if (!CollectionUtils.isEmpty(featureCollection.features())) {
				int plotIndex = 1;
				int pointIndex = 1;
				for (Feature feature : featureCollection.features()) {
					if (feature.geometry() instanceof Polygon) {

						Polygon polygon = (Polygon) feature.geometry();

						ApiPlot apiPlot = new ApiPlot();
						apiPlot.setPlotName("Plot " + plotIndex++);

						double polygonSizeInHa = TurfMeasurement.area(feature) / 1000;
						apiPlot.setSize(Math.floor(polygonSizeInHa * 100) / 100);
						apiPlot.setUnit("ha");
                        apiPlot.setCollectorId(userId);
                        apiPlot.setSynchronisationDate(new Date());

                        Feature centroidFeature = TurfMeasurement.center(feature);
                        Point centroidPoint = (Point) centroidFeature.geometry();
                        assert centroidPoint != null;
                        BigDecimal latCenter = BigDecimal.valueOf(centroidPoint.longitude())
                                .setScale(6, RoundingMode.HALF_UP);
                        BigDecimal lonCenter = BigDecimal.valueOf(centroidPoint.latitude())
                                .setScale(6, RoundingMode.HALF_UP);
                        apiPlot.setCenterLatitude(latCenter.doubleValue());
                        apiPlot.setCenterLongitude(lonCenter.doubleValue());


						List<Point> polygonCoordinates = polygon.coordinates().get(0);

						apiPlot.setCoordinates(polygonCoordinates.stream().map(lngLat -> {
							ApiPlotCoordinate coordinate = new ApiPlotCoordinate();
                            BigDecimal lattCenter = BigDecimal.valueOf(lngLat.latitude())
                                    .setScale(6, RoundingMode.HALF_UP);
                            BigDecimal lontCenter = BigDecimal.valueOf(lngLat.longitude())
                                    .setScale(6, RoundingMode.HALF_UP);
							coordinate.setLongitude(lontCenter.doubleValue());
							coordinate.setLatitude(lattCenter.doubleValue());
							return coordinate;
						}).collect(Collectors.toList()));

						ApiProductType apiProductType = new ApiProductType();
						apiProductType.setId(userCustomer.getProductTypes().stream().toList().get(0).getProductType().getId());
						apiPlot.setCrop(apiProductType);

						createUserCustomerPlot(id, authUser, Language.EN, apiPlot, userId);

					} else if (feature.geometry() instanceof Point) {

						Point point = (Point) feature.geometry();

						ApiPlot apiPlot = new ApiPlot();
						apiPlot.setPlotName("Point " + pointIndex++);
                        apiPlot.setCollectorId(userId);
                        apiPlot.setSynchronisationDate(new Date());

						ApiPlotCoordinate coordinate = new ApiPlotCoordinate();
						coordinate.setLongitude(point.longitude());
						coordinate.setLatitude(point.latitude());
						apiPlot.setCoordinates(List.of(coordinate));

						ApiProductType apiProductType = new ApiProductType();
						apiProductType.setId(userCustomer.getProductTypes().stream().toList().get(0).getProductType().getId());
						apiPlot.setCrop(apiProductType);

						createUserCustomerPlot(id, authUser, Language.EN, apiPlot, userId);
					}
				}
			}

        } catch (IOException e) {
			logger.error("Error while reading GeoJSON file", e);
			throw new ApiException(ApiStatus.ERROR, "Error while reading GeoJSON file");
        }
    }

	@Transactional
	public void deleteUserCustomer(Long id, CustomUserDetails user) throws ApiException {

		UserCustomer userCustomer = fetchUserCustomer(id);
		PermissionsUtil.checkUserIfCompanyEnrolled(userCustomer.getCompany().getUsers().stream().toList(), user);

		em.remove(userCustomer);
	}

	@Transactional
	public ApiPlot createUserCustomerPlot(Long userCustomerId,
										  CustomUserDetails user,
										  Language language,
										  ApiPlot request, Long userId) throws ApiException {

		UserCustomer userCustomer = fetchUserCustomer(userCustomerId);
		PermissionsUtil.checkUserIfCompanyEnrolled(userCustomer.getCompany().getUsers().stream().toList(), user);

		Plot plot = new Plot();
		plot.setPlotName(request.getPlotName());
		plot.setCrop(fetchProductType(request.getCrop().getId()));
		plot.setNumberOfPlants(request.getNumberOfPlants());
		plot.setUnit(request.getUnit());
		plot.setSize(request.getSize());
		plot.setOrganicStartOfTransition(request.getOrganicStartOfTransition());
		plot.setFarmer(userCustomer);
        plot.setCollectorId(userId);
        plot.setSynchronisationDate(new Date());
		plot.setLastUpdated(new Date());
        // calcul du centroid
        // Calculer le centroid avec JTS
        if (request.getCoordinates() != null && !request.getCoordinates().isEmpty()) {
            double[] centroid = MapTools.calculatePolygonCentroid(request.getCoordinates());
            BigDecimal latCenter = BigDecimal.valueOf(centroid[0])
                    .setScale(6, RoundingMode.HALF_UP);
            BigDecimal lonCenter = BigDecimal.valueOf(centroid[1])
                    .setScale(6, RoundingMode.HALF_UP);
            plot.setCenterLatitude(latCenter.doubleValue());
            plot.setCenterLongitude(lonCenter.doubleValue());

            // Optionnel: calculer la superficie
//            double area = PlotGeometryUtils.calculateArea(request.getCoordinates());
//            plot.setCalculatedArea(area); // Ajoutez ce champ si nécessaire
        } else {
            plot.setCenterLatitude(0.0);
            plot.setCenterLongitude(0.0);
        }

        // INITIALISATION EXPLICITE AVEC LinkedHashSet
        plot.setCoordinates(new LinkedHashSet<>());

        int order = 0; // pour save lordre des coordonnées
		for (ApiPlotCoordinate apiPlotCoordinate : request.getCoordinates()) {
			PlotCoordinate plotCoordinate = new PlotCoordinate();
            BigDecimal lat = BigDecimal.valueOf(apiPlotCoordinate.getLatitude())
                    .setScale(6, RoundingMode.HALF_UP);
            BigDecimal lon = BigDecimal.valueOf(apiPlotCoordinate.getLongitude())
                    .setScale(6, RoundingMode.HALF_UP);
            plotCoordinate.setLatitude(lat.doubleValue());
            plotCoordinate.setLongitude(lon.doubleValue());
            plotCoordinate.setCoordinateOrder(order++); // Définir l'ordre
			plotCoordinate.setPlot(plot);
			plot.getCoordinates().add(plotCoordinate);

		}

       // Generate Plot GeoID
		plot.setGeoId(generatePlotGeoID(plot.getCoordinates()));

		em.persist(plot);

        Plot savedPlot = em.find(Plot.class, plot.getId());
        System.out.println("Nombre de coordonnées en base: " + (savedPlot != null ? savedPlot.getCoordinates().size() : "null"));

		return PlotMapper.toApiPlot(plot, language);
	}

	@Transactional
	public ApiPlot refreshGeoIDForUserCustomerPlot(Long userCustomerId,
												   Long plotId,
												   CustomUserDetails user,
												   Language language) throws ApiException {

		UserCustomer userCustomer = fetchUserCustomer(userCustomerId);
		PermissionsUtil.checkUserIfCompanyEnrolled(userCustomer.getCompany().getUsers().stream().toList(), user);

		Plot plot = userCustomer.getPlots()
				.stream()
				.filter(p -> p.getId().equals(plotId))
				.findAny()
				.orElseThrow(() -> new ApiException(ApiStatus.INVALID_REQUEST, "Invalid Plot ID"));

		if (StringUtils.isBlank(plot.getGeoId())) {
			plot.setGeoId(generatePlotGeoID(plot.getCoordinates()));
		}

		return PlotMapper.toApiPlot(plot, language);
	}

	private String generatePlotGeoID(Set<PlotCoordinate> coordinatesSet) {

		List<PlotCoordinate> coordinates = new ArrayList<>(coordinatesSet);

		if (coordinates.isEmpty() || coordinates.size() < 3) {
			return null;
		}

		try {
			//fixCoordinatesForApiCall(coordinates);

			ApiRegisterFieldBoundaryResponse response = agStackClientService.registerFieldBoundaryResponse(coordinates);
			if (!CollectionUtils.isEmpty(response.getMatchedGeoIDs())) {
				return response.getMatchedGeoIDs().stream().findFirst().orElse(null);
			} else {
				return response.getGeoID();
			}

		} catch (Exception e) {
			logger.error("Error while generating plot geoid");
		}

		return null;
	}

	/**
	 * If first coordinate is not equal to last, add first coordinate to list, becouse of the geo API
	 * @param coordinates - coordinates list
	 */
	private void fixCoordinatesForApiCall(List<PlotCoordinate> coordinates) {
		if (coordinates != null && !coordinates.isEmpty() && coordinates.size() > 2) {
			int lastIndex = coordinates.size() - 1;
			if (coordinates.get(0).getLatitude() != null && coordinates.get(lastIndex).getLatitude() != null &&
					coordinates.get(0).getLongitude() != null && coordinates.get(lastIndex).getLongitude() != null) {
				if (!coordinates.get(0).getLatitude().equals(coordinates.get(lastIndex).getLatitude()) ||
						!coordinates.get(0).getLongitude().equals(coordinates.get(lastIndex).getLongitude())
				) {
					// if coordinates not equal, add first as last
					coordinates.add(coordinates.get(0));
				}
			}
		}
	}

	public ApiPaginatedList<ApiCompanyCustomer> listCompanyCustomers(CustomUserDetails authUser, Long companyId, ApiListCustomersRequest request) throws ApiException {

		Company company = companyQueries.fetchCompany(companyId);
		PermissionsUtil.checkUserIfCompanyEnrolled(company.getUsers().stream().toList(), authUser);

		return PaginationTools.createPaginatedResponse(em, request, () -> customerListQueryObject(companyId, request),
				CompanyCustomerMapper::toApiCompanyCustomer);
	}

	private TorpedoProjector<ProductCompany, ApiCompanyListResponse> associationsCompanyListQueryObject(Long companyId) {

		ProductCompany productCompanyCompany = Torpedo.from(ProductCompany.class);
		OnGoingLogicalCondition companyCondition = Torpedo.condition(productCompanyCompany.getCompany().getId()).eq(companyId);
		Torpedo.where(companyCondition);
		List<Long> associatedProductIds = Torpedo.select(productCompanyCompany.getProduct().getId()).list(em);

		ProductCompany productCompanyProduct = Torpedo.from(ProductCompany.class);
		OnGoingLogicalCondition productCondition = Torpedo.condition()
				.and(productCompanyProduct.getProduct().getId()).in(associatedProductIds)
				.and(productCompanyProduct.getType()).eq(ProductCompanyType.ASSOCIATION);
		Torpedo.where(productCondition);

		return new TorpedoProjector<>(productCompanyProduct, ApiCompanyListResponse.class)
				.add(productCompanyProduct.getCompany().getId(), ApiCompanyListResponse::setId)
				.add(productCompanyProduct.getCompany().getName(), ApiCompanyListResponse::setName)
				.add(productCompanyProduct.getCompany().getStatus(), ApiCompanyListResponse::setStatus);
	}

	public ApiPaginatedList<ApiCompanyListResponse> getAssociations(Long id, ApiPaginatedRequest request, CustomUserDetails user) throws ApiException {

		Company company = companyQueries.fetchCompany(id);
		PermissionsUtil.checkUserIfCompanyEnrolled(company.getUsers().stream().toList(), user);

		return PaginationTools.createPaginatedResponse(em, request, () -> associationsCompanyListQueryObject(id));
	}

	private Function<Company> connectedCompanyListQueryObject(Long companyId) {

		ProductCompany productsProxy = Torpedo.from(ProductCompany.class);
		OnGoingLogicalCondition companyCondition = Torpedo.condition(productsProxy.getCompany().getId()).eq(companyId);
		Torpedo.where(companyCondition);
		List<Long> associatedProductIds = Torpedo.select(productsProxy.getProduct().getId()).list(em);

		ProductCompany companiesProxy = Torpedo.from(ProductCompany.class);
		OnGoingLogicalCondition condition = Torpedo.condition();

		condition = condition.and(companiesProxy.getProduct().getId()).in(associatedProductIds);
		condition = condition.and(companiesProxy.getCompany().getId()).neq(companyId);

		Torpedo.where(condition);

		return Torpedo.distinct(companiesProxy.getCompany());
	}

	public ApiPaginatedList<ApiCompanyListResponse> getConnectedCompanies(Long id, ApiPaginatedRequest request, CustomUserDetails user) throws ApiException {

		Company company = companyQueries.fetchCompany(id);
		PermissionsUtil.checkUserIfCompanyEnrolled(company.getUsers().stream().toList(), user);

		return PaginationTools.createPaginatedResponse1(em, request, () -> connectedCompanyListQueryObject(id),
				CompanyApiTools::toApiCompanyListResponse);
	}

	private CompanyCustomer customerListQueryObject(Long companyId, ApiListCustomersRequest request) {
		CompanyCustomer companyCustomer = Torpedo.from(CompanyCustomer.class);

		OnGoingLogicalCondition condition = Torpedo.condition();

		condition = condition.and(companyCustomer.getCompany().getId()).eq(companyId);

		if (request.getQuery() != null) {
			condition = condition.and(companyCustomer.getName()).like().any(request.getQuery());
		}

		Torpedo.where(condition);

		switch (request.sortBy) {
			case "name": QueryTools.orderBy(request.sort, companyCustomer.getName()); break;
			case "contact": QueryTools.orderBy(request.sort, companyCustomer.getContact()); break;
			case "email": QueryTools.orderBy(request.sort, companyCustomer.getEmail()); break;
		}

		return companyCustomer;
	}

	public ApiCompanyCustomer getCompanyCustomer(Long companyCustomerId, CustomUserDetails user) throws ApiException {

		CompanyCustomer companyCustomer = fetchCompanyCustomer(companyCustomerId);
		PermissionsUtil.checkUserIfCompanyEnrolled(companyCustomer.getCompany().getUsers().stream().toList(), user);

		return CompanyCustomerMapper.toApiCompanyCustomer(companyCustomer);
	}

	public CompanyCustomer fetchCompanyCustomer(Long id) throws ApiException {
		CompanyCustomer companyCustomer = em.find(CompanyCustomer.class, id);
		if (companyCustomer == null) {
			throw new ApiException(ApiStatus.INVALID_REQUEST, "Invalid Company customer ID");
		}

		return companyCustomer;
	}

	private UserCustomer fetchUserCustomer(Long id) throws ApiException {
		UserCustomer userCustomer = em.find(UserCustomer.class, id);
		if (userCustomer == null) {
			throw new ApiException(ApiStatus.INVALID_REQUEST, "Invalid Company user customer ID");
		}

		return userCustomer;
	}

	private ProductType fetchProductType(Long id) throws ApiException {

		ProductType productType = em.find(ProductType.class, id);
		if (productType == null) {
			throw new ApiException(ApiStatus.INVALID_REQUEST, "Invalid product type ID");
		}

		return productType;
	}

	@Transactional
	public ApiCompanyCustomer createCompanyCustomer(ApiCompanyCustomer apiCompanyCustomer, CustomUserDetails user) throws ApiException {

		Company company = em.find(Company.class, apiCompanyCustomer.getCompanyId());
		PermissionsUtil.checkUserIfCompanyEnrolled(company.getUsers().stream().toList(), user);

		CompanyCustomer companyCustomer = new CompanyCustomer();
		companyCustomer.setCompany(company);
		companyCustomer.setContact(apiCompanyCustomer.getContact());
		companyCustomer.setEmail(apiCompanyCustomer.getEmail());
		companyCustomer.setLocation(new GeoAddress());
		companyApiTools.updateLocation(companyCustomer.getLocation(), apiCompanyCustomer.getLocation());
		companyCustomer.setName(apiCompanyCustomer.getName());
		companyCustomer.setOfficialCompanyName(apiCompanyCustomer.getOfficialCompanyName());
		companyCustomer.setPhone(apiCompanyCustomer.getPhone());
		companyCustomer.setVatId(apiCompanyCustomer.getVatId());

		em.persist(companyCustomer);
		return CompanyCustomerMapper.toApiCompanyCustomer(companyCustomer);
	}

	@Transactional
	public ApiCompanyCustomer updateCompanyCustomer(ApiCompanyCustomer apiCompanyCustomer, CustomUserDetails user) throws ApiException {

		if (apiCompanyCustomer == null) {
			return null;
		}

		CompanyCustomer companyCustomer = fetchCompanyCustomer(apiCompanyCustomer.getId());
		PermissionsUtil.checkUserIfCompanyEnrolled(companyCustomer.getCompany().getUsers().stream().toList(), user);

		companyCustomer.setContact(apiCompanyCustomer.getContact());
		companyCustomer.setEmail(apiCompanyCustomer.getEmail());
		if (companyCustomer.getLocation() == null) {
			companyCustomer.setLocation(new GeoAddress());
		}
		companyApiTools.updateLocation(companyCustomer.getLocation(), apiCompanyCustomer.getLocation());
		companyCustomer.setName(apiCompanyCustomer.getName());
		companyCustomer.setOfficialCompanyName(apiCompanyCustomer.getOfficialCompanyName());
		companyCustomer.setPhone(apiCompanyCustomer.getPhone());
		companyCustomer.setVatId(apiCompanyCustomer.getVatId());

		return CompanyCustomerMapper.toApiCompanyCustomer(companyCustomer);
	}

	@Transactional
	public void deleteCompanyCustomer(Long id, CustomUserDetails user) throws ApiException {

		CompanyCustomer companyCustomer = em.find(CompanyCustomer.class, id);
		PermissionsUtil.checkUserIfCompanyEnrolled(companyCustomer.getCompany().getUsers().stream().toList(), user);

		em.remove(companyCustomer);
	}

	private void mergeToCompany(Company c, Long otherCompanyId) throws ApiException {
		Company other = companyQueries.fetchCompany(otherCompanyId);
		if (other.getStatus() != CompanyStatus.ACTIVE) {
			throw new ApiException(ApiStatus.INVALID_REQUEST, "Merging to non-active company is impossible");
		}

		Set<Long> otherUsers = other.getUsers().stream().map(cu -> cu.getUser().getId()).collect(Collectors.toSet());
		for (CompanyUser cu : c.getUsers()) {
			if (!otherUsers.contains(cu.getUser().getId())) {
				CompanyUser otherCu = new CompanyUser();
				otherCu.setUser(cu.getUser());
				otherCu.setCompany(other);
				other.getUsers().add(otherCu);
			}
		}
		c.setStatus(CompanyStatus.DEACTIVATED);
	}

	private void removeUserFromCompany(Long userId, Company c) {
		c.getUsers().removeIf(cu -> cu.getUser().getId().equals(userId));
	}

	private void addUserToCompany(Long userId, Company c, CompanyUserRole cur) throws ApiException {
		if (c.getUsers().stream().anyMatch(cu -> cu.getUser().getId().equals(userId))) {
			throw new ApiException(ApiStatus.INVALID_REQUEST, "User already exists");
		}
		User user = userQueries.fetchUser(userId);
		CompanyUser cu = new CompanyUser();
		cu.setUser(user);
		cu.setCompany(c);
		cu.setRole(cur);
		c.getUsers().add(cu);
	}

	private void setUserCompanyRole(Long userId, Company c, CompanyUserRole cur) throws ApiException {
		Optional<CompanyUser> optCu = c.getUsers().stream().filter(cu -> cu.getUser().getId().equals(userId)).findAny();
		if (optCu.isEmpty()) {
			throw new ApiException(ApiStatus.INVALID_REQUEST, "User does not exist or does not exist on the company");
		}
		optCu.get().setRole(cur);
	}

	private void activateCompany(Company company) throws ApiException {
		if (company.getStatus() == CompanyStatus.ACTIVE) {
			throw new ApiException(ApiStatus.INVALID_REQUEST, "Invalid status");
		}
		company.setStatus(CompanyStatus.ACTIVE);
		userQueries.activateUsersForCompany(company.getId());
	}

	private void deactivateCompany(Company company) throws ApiException {
		if (company.getStatus() == CompanyStatus.DEACTIVATED) {
			throw new ApiException(ApiStatus.INVALID_REQUEST, "Invalid status");
		}
		company.setStatus(CompanyStatus.DEACTIVATED);
	}

	private UserCustomer userCustomerListQueryObject(Long companyId, UserCustomerType type, ApiListFarmersRequest request) {
		UserCustomer userCustomer = Torpedo.from(UserCustomer.class);

		OnGoingLogicalCondition condition = Torpedo.condition();

		condition = condition.and(userCustomer.getCompany().getId()).eq(companyId);
		condition = condition.and(userCustomer.getType()).eq(type);

		if (request.getQuery() != null && !request.getQuery().isEmpty()) {
			OnGoingLogicalCondition queryCondition = Torpedo.condition();
			switch (request.getSearchBy()) {
				case "BY_NAME":
					queryCondition = Torpedo.condition(userCustomer.getName()).like().any(request.getQuery());
					break;
				case "BY_SURNAME":
					queryCondition = Torpedo.condition(userCustomer.getSurname()).like().any(request.getQuery());
					break;
				case "BY_NAME_AND_SURNAME":
					queryCondition = Torpedo.condition(userCustomer.getName()).like().any(request.getQuery())
							.or(Torpedo.condition(userCustomer.getSurname()).like().any(request.getQuery()));
					break;
			}
			condition = condition.and(queryCondition);
		}

		Torpedo.where(condition);

		switch (request.getSortBy()) {
			case "BY_ID":
				QueryTools.orderBy(request.getSort(), userCustomer.getId());
				break;
			case "BY_NAME":
				QueryTools.orderBy(request.getSort(), userCustomer.getName());
				break;
			case "BY_SURNAME":
				QueryTools.orderBy(request.getSort(), userCustomer.getSurname());
				break;
		}

		return userCustomer;
	}

	private Country getCountry(Long id) {
		return em.find(Country.class, id);
	}

	public boolean isSystemAdmin(CustomUserDetails customUserDetails) {
		return UserRole.SYSTEM_ADMIN.equals(customUserDetails.getUserRole());
	}

	public boolean isCompanyAdmin(CustomUserDetails customUserDetails, Long companyId) {
		CompanyUser companyUser = Torpedo.from(CompanyUser.class);
		Torpedo.where(companyUser.getCompany().getId()).eq(companyId).
				and(companyUser.getUser().getId()).eq(customUserDetails.getUserId()).
				and(companyUser.getRole()).eq(CompanyUserRole.COMPANY_ADMIN);
		List<CompanyUser> companyUserList = Torpedo.select(companyUser).list(em);
		return !companyUserList.isEmpty();
	}

	public ApiPaginatedList<ApiValueChain> getCompanyValueChainList(Long companyId, ApiPaginatedRequest request, CustomUserDetails authUser) throws ApiException {

		// user permissions check
		Company company = companyQueries.fetchCompany(companyId);
		PermissionsUtil.checkUserIfCompanyEnrolled(company.getUsers().stream().toList(), authUser);

		return PaginationTools.createPaginatedResponse(em, request, () -> getCompanyValueChains(companyId, request),
				ValueChainMapper::toApiValueChainBase);
	}

	public ValueChain getCompanyValueChains(Long companyId, ApiPaginatedRequest request) {

		CompanyValueChain companyValueChainProxy = Torpedo.from(CompanyValueChain.class);
		OnGoingLogicalCondition companyCondition = Torpedo.condition(companyValueChainProxy.getCompany().getId()).eq(companyId);
		Torpedo.where(companyCondition);
		List<Long> valueChainIds = Torpedo.select(companyValueChainProxy.getValueChain().getId()).list(em);

		ValueChain valueChainProxy = Torpedo.from(ValueChain.class);
		OnGoingLogicalCondition valueChainCondition = Torpedo.condition().and(valueChainProxy.getId()).in(valueChainIds);
		Torpedo.where(valueChainCondition);

		switch (request.sortBy) {
			case "name":
				QueryTools.orderBy(request.sort, valueChainProxy.getName());
				break;
			case "description":
				QueryTools.orderBy(request.sort, valueChainProxy.getDescription());
				break;
			default:
				QueryTools.orderBy(request.sort, valueChainProxy.getId());
		}

		return valueChainProxy;
	}

	public ApiPaginatedList<ApiProductType> getCompanyProductTypesList(Long companyId, ApiPaginatedRequest request, CustomUserDetails authUser, Language language) throws ApiException {

		// user permissions check
		Company company = companyQueries.fetchCompany(companyId);
		PermissionsUtil.checkUserIfCompanyEnrolled(company.getUsers().stream().toList(), authUser);

		return PaginationTools.createPaginatedResponse(em, request, () -> getCompanyProductTypes(companyId, request),
				apt -> ProductTypeMapper.toApiProductType(apt, language));
	}

	public ProductType getCompanyProductTypes(Long companyId, ApiPaginatedRequest request) {

		CompanyValueChain companyValueChainProxy = Torpedo.from(CompanyValueChain.class);
		OnGoingLogicalCondition companyCondition = Torpedo.condition(companyValueChainProxy.getCompany().getId()).eq(companyId);
		Torpedo.where(companyCondition);
		List<Long> productTypeIds = Torpedo.select(companyValueChainProxy.getValueChain().getProductType().getId()).list(em);

		if (productTypeIds != null) {
			// calc distinct ids
			productTypeIds = productTypeIds.stream().distinct().collect(Collectors.toList());
		}

		ProductType productTypeProxy = Torpedo.from(ProductType.class);
		OnGoingLogicalCondition productTypeCondition = Torpedo.condition().and(productTypeProxy.getId()).in(productTypeIds);
		Torpedo.where(productTypeCondition);

		if (request != null) {
			switch (request.sortBy) {
				case "name":
					QueryTools.orderBy(request.sort, productTypeProxy.getName());
					break;
				case "description":
					QueryTools.orderBy(request.sort, productTypeProxy.getDescription());
					break;
				default:
					QueryTools.orderBy(request.sort, productTypeProxy.getId());
			}
		}

		return productTypeProxy;
	}

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();

            return ((CustomUserDetails) principal).getUserId();

        }

        throw new RuntimeException("Utilisateur non authentifié");
    }

    public static String formatCoordinate6Decimals(Double value) {
        if (value == null) return "";
        return String.format(Locale.US, "%.6f", value); // 6 chiffres après la virgule
    }


}
