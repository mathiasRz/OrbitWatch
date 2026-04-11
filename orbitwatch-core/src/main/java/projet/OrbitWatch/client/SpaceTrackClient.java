package projet.OrbitWatch.client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Map;
/**
 * Client Space-Track.org (18th Space Control Squadron / USSPACECOM).
 *
 * <p>Utilise comme source de fallback quand CelesTrak est indisponible.
 * Authentification par cookie de session (POST /ajaxauth/login).
 *
 * <p>Active uniquement si {@code tle.spacetrack.enabled=true} ET que
 * username/password sont configures.
 *
 * <p>Mapping catalogues CelesTrak vers requetes Space-Track GP :
 * <ul>
 *   <li>{@code stations} : ISS + CSS (NORAD IDs fixes)</li>
 *   <li>{@code active}   : tous les payloads actifs en orbite</li>
 *   <li>{@code visual}   : objets de grande taille (observables a l oeil nu)</li>
 *   <li>{@code debris}   : debris orbitaux</li>
 *   <li>{@code rocket}   : corps de fusees</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "tle.spacetrack.enabled", havingValue = "true")
public class SpaceTrackClient implements TleSourceClient {
    private static final Logger log = LoggerFactory.getLogger(SpaceTrackClient.class);
    private static final String BASE_URL  = "https://www.space-track.org";
    private static final String LOGIN_URL = BASE_URL + "/ajaxauth/login";
    private static final String GP_URL    = BASE_URL + "/basicspacedata/query/class/gp/%s/orderby/NORAD_CAT_ID/format/3le";
    private static final Map<String, String> CATALOG_QUERIES = Map.of(
        "stations", "NORAD_CAT_ID/25544,48274/",
        "active",   "OBJECT_TYPE/PAYLOAD/DECAYED/0/",
        "visual",   "OBJECT_TYPE/PAYLOAD/RCS_SIZE/LARGE/DECAYED/0/",
        "debris",   "OBJECT_TYPE/DEBRIS/DECAYED/0/",
        "rocket",   "OBJECT_TYPE/ROCKET BODY/DECAYED/0/"
    );
    private static final String DEFAULT_QUERY = "OBJECT_TYPE/PAYLOAD/DECAYED/0/";
    @Value("${tle.spacetrack.username}")
    private String username;
    @Value("${tle.spacetrack.password}")
    private String password;
    private final RestTemplate restTemplate;
    public SpaceTrackClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    @Override
    public String sourceName() {
        return "spacetrack";
    }
    /**
     * Telecharge un catalogue TLE depuis Space-Track.
     * Flux : POST login -> cookie session -> GET GP catalogue.
     */
    @Override
    public String getCatalog(String catalogName) {
        String cookie = login();
        return fetchCatalog(catalogName, cookie);
    }
    private String login() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("identity", username);
        body.add("password", password);
        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(LOGIN_URL, new HttpEntity<>(body, headers), String.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("[SpaceTrack] Echec authentification : " + e.getMessage(), e);
        }
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            throw new IllegalStateException("[SpaceTrack] Aucun cookie recu apres login — credentials invalides ?");
        }
        String cookie = String.join("; ", cookies.stream().map(c -> c.split(";")[0]).toList());
        log.debug("[SpaceTrack] Authentification reussie.");
        return cookie;
    }
    private String fetchCatalog(String catalogName, String cookie) {
        String query = CATALOG_QUERIES.getOrDefault(catalogName, DEFAULT_QUERY);
        String url   = GP_URL.formatted(query);
        log.debug("[SpaceTrack] Telechargement catalogue '{}' depuis {}", catalogName, url);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                "[SpaceTrack] Echec catalogue '%s' : %s".formatted(catalogName, e.getMessage()), e);
        }
        String rawBody = response.getBody();
        if (rawBody == null || rawBody.isBlank()) {
            log.warn("[SpaceTrack] Catalogue '{}' : reponse vide.", catalogName);
            return null;
        }
        log.debug("[SpaceTrack] Catalogue '{}' : {} chars.", catalogName, rawBody.length());
        return rawBody;
    }
}