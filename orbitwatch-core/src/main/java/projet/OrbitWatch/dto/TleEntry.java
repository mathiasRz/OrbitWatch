package projet.OrbitWatch.dto;

import java.time.Instant;

/**
 * Représente un TLE (Two-Line Element set) parsé et stocké en mémoire.
 *
 * @param name      Nom du satellite (ligne 0 du TLE à 3 lignes)
 * @param line1     Première ligne TLE
 * @param line2     Deuxième ligne TLE
 * @param source    Source de téléchargement (ex : "celestrak-stations")
 * @param fetchedAt Horodatage UTC du dernier téléchargement
 */
public record TleEntry(
        String name,
        String line1,
        String line2,
        String source,
        Instant fetchedAt
) {}

