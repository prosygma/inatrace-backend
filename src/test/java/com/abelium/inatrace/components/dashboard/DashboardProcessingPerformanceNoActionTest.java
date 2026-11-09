package com.abelium.inatrace.components.dashboard;

import com.abelium.inatrace.api.ApiStatus;
import com.abelium.inatrace.api.errors.ApiException;
import com.abelium.inatrace.components.common.BaseService;
import com.abelium.inatrace.components.dashboard.api.ApiAggregationTimeUnit;
import com.abelium.inatrace.components.dashboard.api.ApiProcessingPerformanceRequest;
import com.abelium.inatrace.components.dashboard.api.ApiProcessingPerformanceTotal;
import com.abelium.inatrace.components.processingaction.ProcessingActionService;
import com.abelium.inatrace.security.service.CustomUserDetails;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The company dashboard sends no processActionId when the company has no processing action yet
 * or the user clears the "Process" filter. The service then looked up a null id and answered
 * 400 "Invalid processing action ID" on every filter change. It now returns an empty chart.
 */
class DashboardProcessingPerformanceNoActionTest {

	@Test
	@DisplayName("processing performance without a processing action returns empty totals, not 400")
	@SuppressWarnings("unchecked")
	void noProcessingAction() throws Exception {
		ProcessingActionService processingActionService = mock(ProcessingActionService.class);
		// like the real service: Queries.get returns null for a null id, which it reports as 400
		when(processingActionService.fetchProcessingAction(null))
				.thenThrow(new ApiException(ApiStatus.INVALID_REQUEST, "Invalid processing action ID"));
		DashboardService service = new DashboardService(processingActionService);

		EntityManager em = mock(EntityManager.class, RETURNS_DEEP_STUBS);
		TypedQuery<Long> membership = mock(TypedQuery.class);
		when(em.createQuery(anyString(), eq(Long.class))).thenReturn(membership);
		when(membership.setParameter(anyString(), any())).thenReturn(membership);
		when(membership.getSingleResult()).thenReturn(1L);
		Field emField = BaseService.class.getDeclaredField("em");
		emField.setAccessible(true);
		emField.set(service, em);

		CustomUserDetails user = mock(CustomUserDetails.class);
		when(user.getUserId()).thenReturn(7L);

		ApiProcessingPerformanceRequest request = new ApiProcessingPerformanceRequest(1L, 2L, null,
				LocalDate.of(2024, 10, 31), LocalDate.of(2026, 11, 8), null, ApiAggregationTimeUnit.MONTH, null);

		ApiProcessingPerformanceTotal total = service.calculateProcessingPerformanceData(request, user);

		assertEquals(ApiAggregationTimeUnit.MONTH, total.getUnitType());
		assertTrue(total.getTotals().isEmpty());
		verify(processingActionService, never()).fetchProcessingAction(any());
	}
}
