package projet.OrbitWatch.dto;

import java.time.Instant;

/**
 * Représente un rapprochement critique détecté entre deux satellites.
 *
 * @param tca         Time of Closest Approach (instant UTC du minimum de distance)
 * @param distanceKm  Distance minimale au TCA en kilomètres (repère ECI)
 * @param sat1        Position du satellite 1 au TCA
 * @param sat2        Position du satellite 2 au TCA
 */
public record ConjunctionEvent(
        Instant          tca,
        double           distanceKm,
        SatellitePosition sat1,
        SatellitePosition sat2
) {}

