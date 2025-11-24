package com.abelium.inatrace.db.entities.common;

import com.abelium.inatrace.db.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;

import java.util.Objects;

/**
 * Singe coordinate of a plot.
 *
 * @author Pece Adjievski, Sunesis d.o.o.
 */
@Entity
public class PlotCoordinate extends BaseEntity {

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column
    private Integer coordinateOrder; // Nouveau champ pour l'ordre

	@ManyToOne
	private Plot plot;

	public Double getLatitude() {
		return latitude;
	}

	public void setLatitude(Double latitude) {
		this.latitude = latitude;
	}

	public Double getLongitude() {
		return longitude;
	}

	public void setLongitude(Double longitude) {
		this.longitude = longitude;
	}

	public Plot getPlot() {
		return plot;
	}

	public void setPlot(Plot plot) {
		this.plot = plot;
	}

    public Integer getCoordinateOrder() {
        return coordinateOrder;
    }

    public void setCoordinateOrder(Integer coordinateOrder) {
        this.coordinateOrder = coordinateOrder;
    }

    // pour gerer le pb de ddesordre des coordonnées
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (!(o instanceof PlotCoordinate thatz)) return false;
//        return Objects.equals(getLatitude(), thatz.getLatitude()) &&
//                Objects.equals(getLongitude(), thatz.getLongitude());
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(getLatitude(), getLongitude());
//    }

}
