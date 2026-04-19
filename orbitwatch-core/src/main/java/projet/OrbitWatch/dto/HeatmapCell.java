package projet.OrbitWatch.dto;

/**
 * Cellule de la heatmap de densité orbitale.
 *
 * <p>La latitude est approximée par la bande d'inclinaison (proxy de la latitude max atteinte).
 * L'altitude correspond au périgée regroupé par bandes de 50 km.
 *
 * @param latBandDeg  Bande de latitude / inclinaison (arrondie à 5°, ex : 50, 55, 65…)
 * @param altBandKm   Bande d'altitude périgée (arrondie à 50 km, ex : 400, 450, 500…)
 * @param count       Nombre d'objets dans cette cellule
 */
public record HeatmapCell(double latBandDeg, double altBandKm, int count) {}

