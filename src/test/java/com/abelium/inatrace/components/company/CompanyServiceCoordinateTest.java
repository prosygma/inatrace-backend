package com.abelium.inatrace.components.company;

import com.abelium.inatrace.components.company.api.ApiPlotCoordinate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordering of plot coordinates as they are persisted - see
 * {@link CompanyService#normalizePlotCoordinates} and {@link CompanyService#untangleRing}.
 * <p>
 * The fixture is a deeply concave "C" shaped plot, because that is the case the previous
 * implementation got wrong: it re-ordered points by bearing around the centroid, which is only
 * valid for star-shaped plots. The centroid of this ring falls inside the notch, i.e. outside the
 * plot, so the bearing order walks in and out of the boundary and inflates the area from 13 to 21
 * while still being free of crossing edges - which is why the old self-intersection check reported
 * success on visibly mangled polygons.
 */
class CompanyServiceCoordinateTest {

	/** Deeply concave "C", opening to the right, in correct perimeter-walk order. Area = 13. */
	private static final double[][] CONCAVE_C = {
			{ 0, 0 }, { 0, 5 }, { 1, 5 }, { 1, 1 }, { 4, 1 }, { 4, 5 }, { 5, 5 }, { 5, 0 }
	};

	private final CompanyService service = new CompanyService();

	@Test
	void simpleConcaveRing_isStoredExactlyAsReceived() {
		List<double[]> stored = service.normalizePlotCoordinates(toApi(CONCAVE_C));

		assertRingIs(CONCAVE_C, stored);
		assertEquals(13.0, shoelaceArea(stored), 1e-9);
	}

	@Test
	void singleWobble_isUntangledBackToTheOriginalWalk() {
		// Two adjacent points recorded out of order - the GPS-jitter case that made the whole
		// 98-point BIME track get re-sorted in production.
		double[][] wobbled = swap(CONCAVE_C, 3, 4);
		assertTrue(CompanyService.ringSelfIntersects(Arrays.asList(wobbled)),
				"fixture must self-intersect, otherwise the untangle path is never exercised");

		List<double[]> stored = service.normalizePlotCoordinates(toApi(wobbled));

		// One 2-opt reversal restores the received walk exactly, point for point.
		assertRingIs(CONCAVE_C, stored);
	}

	@Test
	void untangledRing_keepsTheConcaveShape_insteadOfBeingSortedByBearing() {
		List<double[]> stored = service.normalizePlotCoordinates(toApi(swap(CONCAVE_C, 3, 4)));
		List<double[]> ring = openRing(stored);

		assertFalse(CompanyService.ringSelfIntersects(ring), "stored ring must be simple");
		// The regression guard: sorting by bearing around the centroid also yields a crossing-free
		// ring, but one with area 21 - it fills in the notch. Anything other than 13 means the
		// concave shape was flattened.
		assertEquals(13.0, shoelaceArea(stored), 1e-9);
	}

	@Test
	void alreadySimpleRing_isNeverReordered() {
		List<double[]> ring = new ArrayList<>(Arrays.asList(CONCAVE_C));

		assertFalse(CompanyService.ringSelfIntersects(ring));
		assertTrue(CompanyService.untangleRing(ring));

		// untangleRing works on the open ring, so no closing point here.
		assertEquals(CONCAVE_C.length, ring.size());
		for (int i = 0; i < CONCAVE_C.length; i++) {
			assertArrayEquals(CONCAVE_C[i], ring.get(i), "point " + i);
		}
	}

	@Test
	void duplicatePoints_areCollapsed_keepingFirstOccurrenceOrder() {
		List<ApiPlotCoordinate> input = toApi(CONCAVE_C);
		input.add(coordinate(1.0, 1.0));   // already present at index 3
		input.add(coordinate(0.0, 0.0));   // already present at index 0

		assertRingIs(CONCAVE_C, service.normalizePlotCoordinates(input));
	}

	@Test
	void nullCoordinates_areSkipped() {
		List<ApiPlotCoordinate> input = toApi(CONCAVE_C);
		input.add(coordinate(null, 2.0));
		input.add(coordinate(2.0, null));

		assertRingIs(CONCAVE_C, service.normalizePlotCoordinates(input));
	}

	@Test
	void fewerThanThreeDistinctPoints_areStoredAsIsAndNotClosed() {
		List<double[]> stored = service.normalizePlotCoordinates(
				toApi(new double[][] { { 1, 1 }, { 2, 2 } }));

		assertEquals(2, stored.size());
		assertArrayEquals(new double[] { 1, 1 }, stored.get(0));
		assertArrayEquals(new double[] { 2, 2 }, stored.get(1));
	}

	// --- helpers -------------------------------------------------------------------------------

	/** Asserts the stored ring is {@code expected} in order, plus the repeated closing point. */
	private static void assertRingIs(double[][] expected, List<double[]> stored) {
		assertEquals(expected.length + 1, stored.size(), "ring must be closed");
		for (int i = 0; i < expected.length; i++) {
			assertArrayEquals(expected[i], stored.get(i), "point " + i);
		}
		assertArrayEquals(expected[0], stored.get(expected.length), "closing point");
	}

	private static List<double[]> openRing(List<double[]> closed) {
		return new ArrayList<>(closed.subList(0, closed.size() - 1));
	}

	private static double[][] swap(double[][] points, int i, int j) {
		double[][] copy = points.clone();
		double[] tmp = copy[i];
		copy[i] = copy[j];
		copy[j] = tmp;
		return copy;
	}

	private static List<ApiPlotCoordinate> toApi(double[][] points) {
		List<ApiPlotCoordinate> input = new ArrayList<>();
		for (double[] point : points) {
			input.add(coordinate(point[0], point[1]));
		}
		return input;
	}

	private static ApiPlotCoordinate coordinate(Double latitude, Double longitude) {
		ApiPlotCoordinate coordinate = new ApiPlotCoordinate();
		coordinate.setLatitude(latitude);
		coordinate.setLongitude(longitude);
		return coordinate;
	}

	/** Planar area of the ring, using latitude as y and longitude as x. */
	private static double shoelaceArea(List<double[]> ring) {
		List<double[]> points = openRing(ring);
		double sum = 0;
		for (int i = 0; i < points.size(); i++) {
			double[] a = points.get(i);
			double[] b = points.get((i + 1) % points.size());
			sum += a[1] * b[0] - b[1] * a[0];
		}
		return Math.abs(sum) / 2;
	}
}
