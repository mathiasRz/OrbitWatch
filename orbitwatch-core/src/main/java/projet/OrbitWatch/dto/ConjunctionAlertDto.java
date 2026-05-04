package projet.OrbitWatch.dto;

/**
 * DTO plat représentant une alerte de conjunction — sérialisable JSON sans boucle JPA.
 *
 * @param sat1       Nom du satellite 1
 * @param sat2       Nom du satellite 2
 * @param distanceKm Distance minimale au TCA en kilomètres
 * @param tca        Time of Closest Approach en ISO-8601
 */
public record ConjunctionAlertDto(String sat1, String sat2, double distanceKm, String tca) {}

