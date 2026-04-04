package projet.OrbitWatch.model;

/**
 * Type d'anomalie orbitale détectée par {@code AnomalyDetectionService}.
 */
public enum AnomalyType {

    /** Variation significative de l'altitude (périgée ou apogée) — manœuvre ou rentrée. */
    ALTITUDE_CHANGE,

    /** Variation de l'inclinaison orbitale — manœuvre de changement de plan. */
    INCLINATION_CHANGE,

    /** Dérive de l'ascension droite du nœud ascendant (RAAN). */
    RAAN_DRIFT,

    /** Variation de l'excentricité — circularisation ou elliptisation. */
    ECCENTRICITY_CHANGE,

    /** Anomalie statistique détectée par Z-score glissant (Phase 2). */
    STATISTICAL
}

