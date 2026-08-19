package com.abelium.inatrace.tools;

import com.abelium.inatrace.components.company.api.ApiPlotCoordinate;
import java.util.List;
import java.util.stream.Collectors;

public class MapTools {
    /**
     * Calcule le centroid (centre géométrique) d'un polygone
     * Utilise la formule du centre de masse de polygone
     * Plus précis qu'une simple moyenne
     */
    public static double[] calculatePolygonCentroid(List<ApiPlotCoordinate> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return new double[]{0.0, 0.0};
        }

        // Si un seul point, retourner ce point
        if (coordinates.size() == 1) {
            return new double[]{
                    coordinates.get(0).getLatitude(),
                    coordinates.get(0).getLongitude()
            };
        }

        // Si seulement 2 points, retourner le milieu
        if (coordinates.size() == 2) {
            return new double[]{
                    (coordinates.get(0).getLatitude() + coordinates.get(1).getLatitude()) / 2.0,
                    (coordinates.get(0).getLongitude() + coordinates.get(1).getLongitude()) / 2.0
            };
        }

        double signedArea = 0.0;
        double centroidLat = 0.0;
        double centroidLng = 0.0;

        int n = coordinates.size();

        // Appliquer la formule du centre de masse de polygone
        for (int i = 0; i < n; i++) {
            double x0 = coordinates.get(i).getLongitude();
            double y0 = coordinates.get(i).getLatitude();
            double x1 = coordinates.get((i + 1) % n).getLongitude();
            double y1 = coordinates.get((i + 1) % n).getLatitude();

            // Produit vectoriel (cross product)
            double cross = (x0 * y1) - (x1 * y0);
            signedArea += cross;

            centroidLat += (y0 + y1) * cross;
            centroidLng += (x0 + x1) * cross;
        }

        signedArea *= 0.5;

        // Si l'aire est très petite (polygone dégénéré), utiliser la moyenne simple
        if (Math.abs(signedArea) < 1e-9) {
            return calculateSimpleCentroid(coordinates);
        }

        centroidLat /= (6.0 * signedArea);
        centroidLng /= (6.0 * signedArea);

        return new double[]{centroidLat, centroidLng};
    }

    /**
     * Calcul simple (moyenne arithmétique)
     * Moins précis pour les polygones irréguliers
     */
    public static double[] calculateSimpleCentroid(List<ApiPlotCoordinate> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return new double[]{0.0, 0.0};
        }

        double sumLat = 0.0;
        double sumLng = 0.0;

        for (ApiPlotCoordinate coord : coordinates) {
            sumLat += coord.getLatitude();
            sumLng += coord.getLongitude();
        }

        return new double[]{
                sumLat / coordinates.size(),
                sumLng / coordinates.size()
        };
    }

    /**
     * Calcule l'aire approximative en mètres carrés
     * (Formule de Gauss pour les coordonnées sphériques simplifiée)
     */
    public static double calculateAreaApprox(List<ApiPlotCoordinate> coordinates) {
        if (coordinates == null || coordinates.size() < 3) {
            return 0.0;
        }

        double area = 0.0;
        int n = coordinates.size();

        // Formule de l'aire de Gauss (shoelace formula)
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double xi = coordinates.get(i).getLongitude();
            double yi = coordinates.get(i).getLatitude();
            double xj = coordinates.get(j).getLongitude();
            double yj = coordinates.get(j).getLatitude();

            area += xi * yj;
            area -= yi * xj;
        }

        area = Math.abs(area) / 2.0;

        // Conversion approximative de degrés² en m²
        // 1 degré de latitude ≈ 111.32 km
        // 1 degré de longitude ≈ cos(latitude) * 111.32 km
        double avgLat = calculateSimpleCentroid(coordinates)[0];
        double latToMeters = 111319.9; // mètres par degré de latitude
        double lngToMeters = Math.cos(Math.toRadians(avgLat)) * 111319.9; // mètres par degré de longitude

        return area * latToMeters * lngToMeters;
    }
}

