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
) {
    /**
     * Extrait le NORAD Catalog Number depuis la ligne 1 du TLE (colonnes 3-7, 1-indexed).
     * Retourne -1 si le parsing échoue.
     */
    public int noradId() {
        try {
            // Format TLE ligne 1 : "1 NNNNNX ..." — le NORAD ID est aux positions 2-6 (0-indexed)
            return Integer.parseInt(line1.substring(2, 7).trim());
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return -1;
        }
    }
}

