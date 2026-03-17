package projet.OrbitWatch.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du CelesTrackClient.
 * Valide la construction de l'URL, la délégation au RestTemplate
 * et la gestion des cas d'erreur (réponse vide, exception réseau).
 */
@ExtendWith(MockitoExtension.class)
class CelesTrackClientTest {

    @Mock
    private RestTemplate restTemplate;

    private CelesTrackClient client;

    private static final String BASE_URL =
            "https://celestrak.org/SOCRATES/query.php?GROUP=%s&FORMAT=TLE";

    private static final String SAMPLE_TLE =
            "ISS (ZARYA)\n" +
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990\n" +
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999\n";

    @BeforeEach
    void setUp() {
        client = new CelesTrackClient(restTemplate);
        ReflectionTestUtils.setField(client, "celestrakBaseUrl", BASE_URL);
    }

    @Test
    @DisplayName("getCatalog : retourne le corps de la réponse brute")
    void getCatalog_returnsRawBody() {
        when(restTemplate.getForObject(contains("stations"), eq(String.class)))
                .thenReturn(SAMPLE_TLE);

        String result = client.getCatalog("stations");

        assertThat(result).isEqualTo(SAMPLE_TLE);
    }

    @Test
    @DisplayName("getCatalog : construit l'URL avec le nom du catalogue")
    void getCatalog_buildsUrlWithCatalogName() {
        when(restTemplate.getForObject(
                "https://celestrak.org/SOCRATES/query.php?GROUP=active&FORMAT=TLE",
                String.class))
                .thenReturn(SAMPLE_TLE);

        String result = client.getCatalog("active");

        assertThat(result).isNotNull();
        verify(restTemplate).getForObject(
                "https://celestrak.org/SOCRATES/query.php?GROUP=active&FORMAT=TLE",
                String.class);
    }

    @Test
    @DisplayName("getCatalog : retourne null si la réponse est null")
    void getCatalog_returnsNullWhenResponseIsNull() {
        when(restTemplate.getForObject(contains("stations"), eq(String.class)))
                .thenReturn(null);

        String result = client.getCatalog("stations");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getCatalog : retourne null si la réponse est vide (blancs)")
    void getCatalog_returnsNullWhenResponseIsBlank() {
        when(restTemplate.getForObject(contains("stations"), eq(String.class)))
                .thenReturn("   \n   ");

        String result = client.getCatalog("stations");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getCatalog : lève IllegalStateException si le serveur est injoignable")
    void getCatalog_throwsIllegalStateExceptionOnNetworkError() {
        when(restTemplate.getForObject(contains("stations"), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> client.getCatalog("stations"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stations");
    }

    @Test
    @DisplayName("getCatalog : le message d'erreur contient l'URL")
    void getCatalog_errorMessageContainsUrl() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("timeout"));

        assertThatThrownBy(() -> client.getCatalog("visual"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visual");
    }

    @Test
    @DisplayName("getCatalog : utilise la base-url configurée via propriété")
    void getCatalog_usesConfiguredBaseUrl() {
        ReflectionTestUtils.setField(client, "celestrakBaseUrl",
                "https://custom-host/tle?cat=%s&fmt=TLE");
        when(restTemplate.getForObject("https://custom-host/tle?cat=stations&fmt=TLE", String.class))
                .thenReturn(SAMPLE_TLE);

        String result = client.getCatalog("stations");

        assertThat(result).isEqualTo(SAMPLE_TLE);
        verify(restTemplate).getForObject("https://custom-host/tle?cat=stations&fmt=TLE", String.class);
    }
}

