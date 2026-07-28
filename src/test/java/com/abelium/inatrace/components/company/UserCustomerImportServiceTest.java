package com.abelium.inatrace.components.company;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserCustomerImportServiceTest {

    private final UserCustomerImportService service = new UserCustomerImportService();

    @Test
    void blankCell_isAbsentGeodata_notAnError() {
        assertNull(service.parseGeoDataCell(null));
        assertNull(service.parseGeoDataCell(""));
        assertNull(service.parseGeoDataCell("   "));
    }

    @Test
    void validPolygon_isParsed() {
        UserCustomerImportService.ParsedGeoData parsed = service.parseGeoDataCell(
                "POLYGON((5.17 10.23, 5.18 10.24, 5.19 10.25))");

        assertEquals(UserCustomerImportService.GeoDataType.POLYGON, parsed.type);
        assertEquals(3, parsed.points.size());
        assertEquals(5.17, parsed.points.get(0)[0]);
        assertEquals(10.23, parsed.points.get(0)[1]);
    }

    @Test
    void validPoint_isParsed() {
        UserCustomerImportService.ParsedGeoData parsed = service.parseGeoDataCell("POINT(5.17 10.23)");

        assertEquals(UserCustomerImportService.GeoDataType.POINT, parsed.type);
        assertEquals(1, parsed.points.size());
        assertEquals(5.17, parsed.points.get(0)[0]);
        assertEquals(10.23, parsed.points.get(0)[1]);
    }

    @Test
    void unrecognizedPrefix_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.parseGeoDataCell("MULTIPOLYGON((1 2, 3 4, 5 6))"));
        assertThrows(IllegalArgumentException.class, () -> service.parseGeoDataCell("not geodata at all"));
    }

    @Test
    void malformedShape_isRejected() {
        // Missing closing parenthesis
        assertThrows(IllegalArgumentException.class, () -> service.parseGeoDataCell("POLYGON((1 2, 3 4, 5 6)"));
        // Non-numeric token
        assertThrows(IllegalArgumentException.class, () -> service.parseGeoDataCell("POLYGON((1 2, a b, 5 6))"));
        // Three numbers in one pair
        assertThrows(IllegalArgumentException.class, () -> service.parseGeoDataCell("POLYGON((1 2 3, 4 5, 6 7))"));
    }

    @Test
    void outOfRangeCoordinates_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.parseGeoDataCell("POINT(95 10.23)"));
        assertThrows(IllegalArgumentException.class, () -> service.parseGeoDataCell("POINT(5.17 190)"));
        assertThrows(IllegalArgumentException.class,
                () -> service.parseGeoDataCell("POLYGON((95 10, 5 11, 6 12))"));
    }

    @Test
    void polygonWithFewerThanThreeDistinctVertices_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.parseGeoDataCell("POLYGON((1 2, 3 4))"));
        // Duplicated point does not count towards the minimum of 3 distinct vertices
        assertThrows(IllegalArgumentException.class,
                () -> service.parseGeoDataCell("POLYGON((1 2, 3 4, 1 2))"));
    }

    @Test
    void pointWithMoreThanOneCoordinatePair_isRejected() {
        // A POINT with two pairs never matches the single-pair POINT format to begin with
        assertThrows(IllegalArgumentException.class, () -> service.parseGeoDataCell("POINT(1 2, 3 4)"));
    }
}
