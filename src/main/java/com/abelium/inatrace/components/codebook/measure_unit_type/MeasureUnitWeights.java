package com.abelium.inatrace.components.codebook.measure_unit_type;

import com.abelium.inatrace.components.codebook.measure_unit_type.api.ApiMeasureUnitType;
import com.abelium.inatrace.db.entities.codebook.MeasureUnitType;

import java.math.BigDecimal;

/**
 * Weight of a measure unit for quantity conversions.
 * <p>
 * The weight is optional (the settings form stores null when it is left empty). Callers multiply and divide
 * by it, so a missing or non-positive weight is treated as 1: no conversion instead of a NullPointerException
 * or a division by zero.
 */
public final class MeasureUnitWeights {

	private MeasureUnitWeights() {
	}

	public static BigDecimal weightOrOne(MeasureUnitType measureUnitType) {
		return measureUnitType == null ? BigDecimal.ONE : weightOrOne(measureUnitType.getWeight());
	}

	public static BigDecimal weightOrOne(ApiMeasureUnitType measureUnitType) {
		return measureUnitType == null ? BigDecimal.ONE : weightOrOne(measureUnitType.getWeight());
	}

	public static BigDecimal weightOrOne(BigDecimal weight) {
		return weight == null || weight.signum() <= 0 ? BigDecimal.ONE : weight;
	}
}
