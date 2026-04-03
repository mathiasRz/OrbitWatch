package projet.OrbitWatch.dto;

import java.time.Instant;

/**
 * Paramètres orbitaux Keplériens extraits d'un TLE via Orekit.
 *
 * @param noradId            Identifiant NORAD (extrait de la ligne 1 du TLE — colonne 3-7)
 * @param satelliteName      Nom du satellite (ligne 0 du TLE à 3 lignes)
 * @param epochTle           Époque du TLE (instant UTC de référence)
 * @param semiMajorAxisKm    Demi-grand axe {@code a} en kilomètres — calculé via μ/n²
 * @param eccentricity       Excentricité {@code e} (sans dimension, 0 = circulaire)
 * @param inclinationDeg     Inclinaison {@code i} en degrés
 * @param raanDeg            Ascension droite du nœud ascendant {@code Ω} en degrés
 * @param argOfPerigeeDeg    Argument du périgée {@code ω} en degrés
 * @param meanMotionRevDay   Mouvement moyen {@code n} en révolutions par jour
 * @param altitudePerigeeKm  Altitude du périgée en km — {@code a*(1-e) - R_Earth}
 * @param altitudeApogeeKm   Altitude de l'apogée en km — {@code a*(1+e) - R_Earth}
 */
public record OrbitalElements(
        int     noradId,
        String  satelliteName,
        Instant epochTle,
        double  semiMajorAxisKm,
        double  eccentricity,
        double  inclinationDeg,
        double  raanDeg,
        double  argOfPerigeeDeg,
        double  meanMotionRevDay,
        double  altitudePerigeeKm,
        double  altitudeApogeeKm
) {}
