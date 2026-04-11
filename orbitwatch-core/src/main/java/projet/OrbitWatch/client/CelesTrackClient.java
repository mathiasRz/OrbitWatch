package projet.OrbitWatch.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class CelesTrackClient implements TleSourceClient {

	private static final Logger log = LoggerFactory.getLogger(CelesTrackClient.class);

  /**
   * URL CelesTrak GP avec placeholder {@code %s} pour le nom du catalogue.
   */
  @Value("${tle.celestrak.base-url:https://celestrak.org/SOCRATES/query.php?GROUP=%s&FORMAT=TLE}")
  private String celestrakBaseUrl;

  private final RestTemplate restTemplate;

	public CelesTrackClient(RestTemplate restTemplate) {
		this.restTemplate = restTemplate;
	}

	@Override
	public String sourceName() {
		return "celestrak";
	}

	/**
	   * Télécharge un catalogue CelesTrak spécifique.
	   * @param catalogName identifiant du catalogue (ex : "stations", "active")
	   */
	@Override
  public String getCatalog(String catalogName) {
      String url = buildUrl(catalogName);
      log.debug("[TleService] Téléchargement catalogue '{}' depuis {}", catalogName, url);

      String rawBody;
      try {
          rawBody = restTemplate.getForObject(url, String.class);
      } catch (RestClientException e) {
          throw new IllegalStateException(
                  "Impossible de télécharger le catalogue '%s' depuis %s".formatted(catalogName, url), e);
      }

      if (rawBody == null || rawBody.isBlank()) {
          log.warn("[TleService] Catalogue '{}' : réponse vide, ignorée.", catalogName);
          return null;
      }

      return rawBody;
  }

	/**
   * Construit l'URL CelesTrak GP pour un catalogue donné.
   * Utilise la propriété {@code tle.celestrak.base-url} qui doit contenir un {@code %s}
   * comme placeholder du nom du catalogue.
   * Exemple : {@code https://celestrak.org/SOCRATES/query.php?GROUP=%s&FORMAT=TLE}
   *
   * <p>URL GP réelle : {@code https://celestrak.org/SOCRATES/query.php?GROUP={name}&FORMAT=TLE}
   */
  private String buildUrl(String catalogName) {
      return celestrakBaseUrl.formatted(catalogName);
  }
}
