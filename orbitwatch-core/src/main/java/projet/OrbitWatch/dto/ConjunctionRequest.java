package projet.OrbitWatch.dto;

/**
 * Requête d'analyse de conjunction on-demand.
 *
 * @param nameSat1       Nom du satellite 1
 * @param tle1Sat1       Ligne 1 du TLE du satellite 1
 * @param tle2Sat1       Ligne 2 du TLE du satellite 1
 * @param nameSat2       Nom du satellite 2
 * @param tle1Sat2       Ligne 1 du TLE du satellite 2
 * @param tle2Sat2       Ligne 2 du TLE du satellite 2
 * @param durationHours  Fenêtre d'analyse en heures (défaut : 24.0)
 * @param stepSeconds    Pas de propagation en secondes (défaut : 60)
 * @param thresholdKm    Seuil de rapprochement critique en km (défaut : 5.0)
 */
public record ConjunctionRequest(
        String nameSat1,
        String tle1Sat1,
        String tle2Sat1,
        String nameSat2,
        String tle1Sat2,
        String tle2Sat2,
        double durationHours,
        int    stepSeconds,
        double thresholdKm
) {
    /** Constructeur avec valeurs par défaut. */
    public ConjunctionRequest {
        if (durationHours <= 0) durationHours = 24.0;
        if (stepSeconds   <= 0) stepSeconds   = 60;
        if (thresholdKm   <= 0) thresholdKm   = 5.0;
    }
}

