package com.abelium.inatrace.components.dashboard;

import com.abelium.inatrace.components.codebook.measure_unit_type.MeasureUnitWeights;
import com.abelium.inatrace.components.processingaction.ProcessingActionService;
import com.abelium.inatrace.db.entities.codebook.MeasureUnitType;
import com.abelium.inatrace.db.entities.codebook.SemiProduct;
import com.abelium.inatrace.db.entities.processingaction.ProcessingAction;
import com.abelium.inatrace.db.entities.processingaction.ProcessingActionOutputSemiProduct;
import com.abelium.inatrace.types.ProcessingActionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A measure unit's weight is optional (the settings form stores null when it is left empty).
 * The processing-performance dashboard divided by it and answered 500 with
 * "Cannot invoke BigDecimal.divide(...) because inputWeight is null" for any processing action
 * whose input semi-product used such a unit. A missing or non-positive weight now counts as 1.
 */
class DashboardWeightNormalisationTest {

	private static MeasureUnitType unit(BigDecimal weight) {
		return new MeasureUnitType("U", "unit", weight);
	}

	private static SemiProduct semiProduct(MeasureUnitType unit) {
		SemiProduct sp = new SemiProduct();
		sp.setMeasurementUnitType(unit);
		return sp;
	}

	private static ProcessingAction processing(MeasureUnitType in, MeasureUnitType out) {
		ProcessingAction pa = new ProcessingAction();
		pa.setType(ProcessingActionType.PROCESSING);
		pa.setInputSemiProduct(semiProduct(in));
		ProcessingActionOutputSemiProduct output = new ProcessingActionOutputSemiProduct();
		output.setOutputSemiProduct(semiProduct(out));
		pa.setOutputSemiProducts(Set.of(output));
		return pa;
	}

	private static BigDecimal quotient(ProcessingAction action) throws Exception {
		ProcessingActionService processingActionService = mock(ProcessingActionService.class);
		when(processingActionService.fetchProcessingAction(1L)).thenReturn(action);
		DashboardService service = new DashboardService(processingActionService);

		Method m = DashboardService.class.getDeclaredMethod("calculateInputNormalisationQuotient", Long.class);
		m.setAccessible(true);
		try {
			return (BigDecimal) m.invoke(service, 1L);
		} catch (InvocationTargetException e) {
			throw (Exception) e.getCause();
		}
	}

	@Test
	@DisplayName("input unit without a weight does not break the processing-performance quotient")
	void nullInputWeight() throws Exception {
		assertEquals(0, BigDecimal.ONE.compareTo(quotient(processing(unit(null), unit(BigDecimal.ONE)))));
	}

	@Test
	@DisplayName("output unit without a weight or with weight 0 is treated as 1, not divided by")
	void nullOrZeroOutputWeight() throws Exception {
		assertEquals(0, new BigDecimal("60").compareTo(quotient(processing(unit(new BigDecimal("60")), unit(null)))));
		assertEquals(0, new BigDecimal("60").compareTo(quotient(processing(unit(new BigDecimal("60")), unit(BigDecimal.ZERO)))));
	}

	@Test
	@DisplayName("real weights are still used")
	void realWeights() throws Exception {
		assertEquals(0, new BigDecimal("60").compareTo(quotient(processing(unit(new BigDecimal("60")), unit(BigDecimal.ONE)))));
	}

	@Test
	@DisplayName("weightOrOne: null type, null weight, zero and negative weights become 1")
	void weightOrOne() {
		assertEquals(BigDecimal.ONE, MeasureUnitWeights.weightOrOne((MeasureUnitType) null));
		assertEquals(BigDecimal.ONE, MeasureUnitWeights.weightOrOne(unit(null)));
		assertEquals(BigDecimal.ONE, MeasureUnitWeights.weightOrOne(unit(BigDecimal.ZERO)));
		assertEquals(BigDecimal.ONE, MeasureUnitWeights.weightOrOne(unit(new BigDecimal("-2"))));
		assertEquals(new BigDecimal("2.5"), MeasureUnitWeights.weightOrOne(unit(new BigDecimal("2.5"))));
	}
}
