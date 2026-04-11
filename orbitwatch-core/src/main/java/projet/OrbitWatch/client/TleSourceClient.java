package projet.OrbitWatch.client;

/**
 * Contrat commun pour toutes les sources de données TLE.
 *
 * <p>Chaque implémentation représente une source externe (CelesTrak, Space-Track, etc.).
 * Le {@link FetchTleJob} parcourt la liste des sources dans l'ordre de priorité
 * et utilise la première qui répond correctement (Chain of Responsibility).
 *
 * <p>Les exceptions réseau sont propagées — c'est au job de les intercepter et
 * de passer à la source suivante.
 */
public interface TleSourceClient {

    /**
     * Identifiant court de la source (utilisé dans les logs).
     * Ex : {@code "celestrak"}, {@code "spacetrack"}.
     */
    String sourceName();

    /**
     * Télécharge un catalogue TLE par son nom logique.
     *
     * @param catalogName nom du catalogue (ex : {@code "stations"}, {@code "active"})
     * @return le contenu brut au format TLE 3 lignes, ou {@code null} / blank si vide
     * @throws IllegalStateException si la source est injoignable ou retourne une erreur HTTP
     */
    String getCatalog(String catalogName);
}

