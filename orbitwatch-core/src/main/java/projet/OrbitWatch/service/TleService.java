package projet.OrbitWatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;
import projet.OrbitWatch.dto.TleEntry;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Service responsable du téléchargement, du parsing et du stockage en mémoire
 * des TLE (Two-Line Element sets) depuis des sources publiques (CelesTrak).
 *
 * <p>Les TLE sont organisés par catalogue (clé = nom du catalogue CelesTrak).
 * Un refresh automatique est déclenché au démarrage puis toutes les 6 heures.
 *
 * <p>Format TLE à 3 lignes attendu :
 * <pre>
 * ISS (ZARYA)
 * 1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990
 * 2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999
 * </pre>
 */
@Service
public class TleService {

	private static final Logger log = LoggerFactory.getLogger(TleService.class);

	public ConcurrentHashMap<String, CopyOnWriteArrayList<TleEntry>> getCatalog() {
		return catalog;
	}

	/**
	 * Stockage en mémoire : clé = nom du catalogue, valeur = liste des TleEntry.
	 * Thread-safe via ConcurrentHashMap + CopyOnWriteArrayList.
	 */
	private final ConcurrentHashMap<String, CopyOnWriteArrayList<TleEntry>> catalog = new ConcurrentHashMap<>();


	/**
	 * Parse un texte brut au format TLE 3 lignes.
	 * Les lignes vides et les espaces superflus sont ignorés.
	 *
	 * @param raw         contenu brut téléchargé
	 * @param catalogName nom du catalogue source (pour le champ {@link TleEntry#source()})
	 * @return liste des {@link TleEntry} parsés
	 */
	public List<TleEntry> parseTle3Lines(String raw, String catalogName) {
		List<TleEntry> result = new ArrayList<>();
		Instant now = Instant.now();

		// Supprime les \r Windows, découpe par lignes, retire les blancs en tête/fin
		String[] lines = raw.replace("\r", "").split("\n");
		List<String> cleaned = Arrays.stream(lines).map(String::trim).filter(l -> !l.isBlank()).toList();

		int i = 0;
		while (i + 2 < cleaned.size()) {
			String nameLine = cleaned.get(i);
			String line1 = cleaned.get(i + 1);
			String line2 = cleaned.get(i + 2);

			// Validation basique : line1 commence par '1 ', line2 par '2 '
			if (line1.startsWith("1 ") && line2.startsWith("2 ")) {
				result.add(new TleEntry(nameLine, line1, line2, catalogName, now));
				i += 3;
			} else {
				// Décalage d'une ligne pour se resynchroniser
				log.warn("[TleService] Ligne TLE inattendue à l'index {} : '{}', tentative de resync.", i, nameLine);
				i++;
			}
		}

		return result;
	}

	/**
	 * Retourne tous les TLE de tous les catalogues.
	 *
	 * @return liste non modifiable de tous les {@link TleEntry}
	 */
	public List<TleEntry> findAll() {
		return catalog.values().stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableList());
	}

	/**
	 * Retourne les TLE d'un catalogue spécifique.
	 *
	 * @param catalogName nom du catalogue (ex : "stations")
	 * @return liste non modifiable, vide si le catalogue est inconnu
	 */
	public List<TleEntry> findByCatalog(String catalogName) {
		CopyOnWriteArrayList<TleEntry> list = catalog.get(catalogName);
		return list != null ? Collections.unmodifiableList(list) : List.of();
	}

	/**
	 * Recherche un TLE par nom de satellite (insensible à la casse, correspondance partielle).
	 *
	 * @param name fragment du nom à rechercher
	 * @return liste des {@link TleEntry} dont le nom contient {@code name}
	 */
	public List<TleEntry> findByName(String name) {
		String query = name.toLowerCase();
		return findAll().stream().filter(e -> e.name().toLowerCase().contains(query)).collect(Collectors.toUnmodifiableList());
	}

	/**
	 * Retourne la liste des catalogues actuellement chargés en mémoire.
	 *
	 * @return ensemble des noms de catalogues
	 */
	public Set<String> getCatalogNames() {
		return Collections.unmodifiableSet(catalog.keySet());
	}

	/**
	 * Retourne le nombre total de TLE en mémoire.
	 *
	 * @return nombre total de TLE
	 */
	public long countAll() {
		return catalog.values().stream().mapToLong(List::size).sum();
	}


	/**
	 * Résout un TLE unique depuis le catalogue en mémoire.
	 *
	 * @throws TleNotFoundException  si aucun satellite ne correspond
	 * @throws AmbiguousTleException si plusieurs satellites correspondent
	 */
	public TleEntry resolveUniqueTle(String name) {
		List<TleEntry> results = findByName(name);
		if (results.isEmpty()) {
			throw new TleNotFoundException("Aucun satellite trouvé pour le nom : " + name);
		}
		if (results.size() > 1) {
			throw new AmbiguousTleException("Plusieurs satellites correspondent à '%s' (%d résultats). Précisez le nom.".formatted(name, results.size()));
		}
		return results.get(0);
	}

	public static Instant parseEpoch(String epoch) {
		return (epoch != null && !epoch.isBlank()) ? Instant.parse(epoch) : null;
	}

	@ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
	public static class TleNotFoundException extends RuntimeException {
		public TleNotFoundException(String msg) {
			super(msg);
		}
	}

	@ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
	public static class AmbiguousTleException extends RuntimeException {
		public AmbiguousTleException(String msg) {
			super(msg);
		}
	}

}



