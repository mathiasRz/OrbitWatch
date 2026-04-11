package projet.OrbitWatch.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du SpaceTrackClient.
 * Valide le login, la construction des URLs GP et la gestion des erreurs.
 */
@ExtendWith(MockitoExtension.class)
class SpaceTrackClientTest {

    @Mock
    private RestTemplate restTemplate;

    private SpaceTrackClient client;

    private static final String SAMPLE_TLE =
            "ISS (ZARYA)\n" +
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990\n" +
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999\n";

    private static final String SESSION_COOKIE = "chocolatechip=abc123";

    @BeforeEach
    void setUp() {
        client = new SpaceTrackClient(restTemplate);
        ReflectionTestUtils.setField(client, "username", "test@example.com");
        ReflectionTestUtils.setField(client, "password", "secret");
    }

    /** Simule un login réussi en retournant un cookie Set-Cookie. */
    private void mockLoginSuccess() {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set(HttpHeaders.SET_COOKIE, SESSION_COOKIE + "; Path=/; HttpOnly");
        when(restTemplate.postForEntity(
                contains("/ajaxauth/login"),
                any(HttpEntity.class),
                eq(String.class)))
            .thenReturn(new ResponseEntity<>("{\"Login\":\"Success\"}", responseHeaders, HttpStatus.OK));
    }

    @Test
    @DisplayName("sourceName : retourne 'spacetrack'")
    void sourceName_returnsSpacetrack() {
        assertThat(client.sourceName()).isEqualTo("spacetrack");
    }

    @Test
    @DisplayName("getCatalog : login puis fetch — retourne le TLE brut")
    void getCatalog_loginThenFetch_returnsTle() {
        mockLoginSuccess();
        when(restTemplate.exchange(
                contains("/basicspacedata/query"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
            .thenReturn(new ResponseEntity<>(SAMPLE_TLE, HttpStatus.OK));

        String result = client.getCatalog("stations");

        assertThat(result).isEqualTo(SAMPLE_TLE);
        // login appelé une fois
        verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
        // fetch appelé une fois
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    @DisplayName("getCatalog : URL GP contient le segment correct pour 'stations'")
    void getCatalog_stationsCatalog_usesNoradIdQuery() {
        mockLoginSuccess();
        when(restTemplate.exchange(
                contains("NORAD_CAT_ID/25544"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
            .thenReturn(new ResponseEntity<>(SAMPLE_TLE, HttpStatus.OK));

        client.getCatalog("stations");

        verify(restTemplate).exchange(
                contains("NORAD_CAT_ID/25544"),
                eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    @DisplayName("getCatalog : catalogue inconnu utilise la requête par défaut (PAYLOAD/DECAYED/0)")
    void getCatalog_unknownCatalog_usesDefaultQuery() {
        mockLoginSuccess();
        when(restTemplate.exchange(
                contains("OBJECT_TYPE/PAYLOAD/DECAYED/0"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
            .thenReturn(new ResponseEntity<>(SAMPLE_TLE, HttpStatus.OK));

        client.getCatalog("unknown-catalog");

        verify(restTemplate).exchange(
                contains("OBJECT_TYPE/PAYLOAD/DECAYED/0"),
                eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    @DisplayName("getCatalog : retourne null si réponse vide")
    void getCatalog_emptyResponse_returnsNull() {
        mockLoginSuccess();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(new ResponseEntity<>("   ", HttpStatus.OK));

        String result = client.getCatalog("active");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getCatalog : lève IllegalStateException si login échoue (réseau)")
    void getCatalog_loginNetworkError_throwsIllegalState() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> client.getCatalog("stations"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SpaceTrack");
    }

    @Test
    @DisplayName("getCatalog : lève IllegalStateException si aucun cookie reçu (credentials invalides)")
    void getCatalog_noCookieReceived_throwsIllegalState() {
        // Réponse sans Set-Cookie
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(new ResponseEntity<>("{}", new HttpHeaders(), HttpStatus.OK));

        assertThatThrownBy(() -> client.getCatalog("stations"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookie");
    }

    @Test
    @DisplayName("getCatalog : lève IllegalStateException si le fetch échoue après login")
    void getCatalog_fetchError_throwsIllegalState() {
        mockLoginSuccess();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenThrow(new RestClientException("503 Service Unavailable"));

        assertThatThrownBy(() -> client.getCatalog("active"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active");
    }

    @Test
    @DisplayName("getCatalog : le cookie est transmis dans le header Authorization du fetch")
    void getCatalog_sendsCookieInFetchRequest() {
        mockLoginSuccess();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenAnswer(invocation -> {
                HttpEntity<?> req = invocation.getArgument(2);
                List<String> cookies = req.getHeaders().get(HttpHeaders.COOKIE);
                assertThat(cookies).isNotNull().anyMatch(c -> c.contains("chocolatechip"));
                return new ResponseEntity<>(SAMPLE_TLE, HttpStatus.OK);
            });

        client.getCatalog("stations");
    }
}

