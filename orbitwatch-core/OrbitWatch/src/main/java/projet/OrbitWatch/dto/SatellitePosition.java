package projet.OrbitWatch.dto;

import java.time.Instant;

/**
 * DTO représentant la position d'un satellite à un instant donné.
 *
 * @param name      Nom ou identifiant du satellite (ligne 0 du TLE si présente, sinon "UNKNOWN")
 * @param epoch     Instant UTC de la position calculée
 * @param latitude  Latitude géodésique en degrés décimaux  ([-90, +90])
 * @param longitude Longitude géodésique en degrés décimaux ([-180, +180])
 * @param altitude  Altitude au-dessus de l'ellipsoïde WGS-84 en kilomètres
 * @param x         Coordonnée X en repère ECI (km)
 * @param y         Coordonnée Y en repère ECI (km)
 * @param z         Coordonnée Z en repère ECI (km)
 */
public record SatellitePosition(
        String  name,
        Instant epoch,
        double  latitude,
        double  longitude,
        double  altitude,
        double  x,
        double  y,
        double  z
) {}

