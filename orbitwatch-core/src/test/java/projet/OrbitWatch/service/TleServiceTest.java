package projet.OrbitWatch.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import projet.OrbitWatch.dto.TleEntry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires du TleService.
 * Valide le parsing TLE 3 lignes et les méthodes de recherche en mémoire.
 */
@ExtendWith(MockitoExtension.class)
class TleServiceTest {

    private TleService tleService;

    private static final String ISS_NAME  = "ISS (ZARYA)";
    private static final String ISS_LINE1 = "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String ISS_LINE2 = "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";

    private static final String HST_NAME  = "HST";
    private static final String HST_LINE1 = "1 20580U 90037B   26066.50000000  .00001000  00000+0  50000-4 0  9999";
    private static final String HST_LINE2 = "2 20580  28.4700 200.0000 0002000  90.0000 270.0000 15.09280000999999";

    /** TLE brut 3 lignes (2 satellites) */
    private static final String RAW_TLE_TWO_SATS = String.join("\n",
            ISS_NAME, ISS_LINE1, ISS_LINE2,
            HST_NAME, HST_LINE1, HST_LINE2
    );

    @BeforeEach
    void setUp() {
        tleService = new TleService();
    }

    @Test
    @DisplayName("parseTle3Lines : parse correctement 2 satellites")
    void parseTle3Lines_twoSatellites() {
        List<TleEntry> entries = tleService.parseTle3Lines(RAW_TLE_TWO_SATS, "test");

        assertThat(entries).hasSize(2);

        TleEntry iss = entries.get(0);
        assertThat(iss.name()).isEqualTo(ISS_NAME);
        assertThat(iss.line1()).isEqualTo(ISS_LINE1);
        assertThat(iss.line2()).isEqualTo(ISS_LINE2);
        assertThat(iss.source()).isEqualTo("test");
        assertThat(iss.fetchedAt()).isNotNull();

        TleEntry hst = entries.get(1);
        assertThat(hst.name()).isEqualTo(HST_NAME);
    }

    @Test
    @DisplayName("parseTle3Lines : ignore les lignes vides entre les TLE")
    void parseTle3Lines_ignoresBlankLines() {
        String rawWithBlanks = "\n" + ISS_NAME + "\n\n" + ISS_LINE1 + "\n" + ISS_LINE2 + "\n\n";
        List<TleEntry> entries = tleService.parseTle3Lines(rawWithBlanks, "test");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).name()).isEqualTo(ISS_NAME);
    }

    @Test
    @DisplayName("parseTle3Lines : retourne liste vide si contenu vide")
    void parseTle3Lines_emptyInput() {
        assertThat(tleService.parseTle3Lines("", "test")).isEmpty();
        assertThat(tleService.parseTle3Lines("   \n  \n  ", "test")).isEmpty();
    }

    @Test
    @DisplayName("parseTle3Lines : gère les fins de ligne Windows (CRLF)")
    void parseTle3Lines_handlesWindowsLineEndings() {
        String rawCrlf = String.join("\r\n", ISS_NAME, ISS_LINE1, ISS_LINE2);
        List<TleEntry> entries = tleService.parseTle3Lines(rawCrlf, "test");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).line1()).isEqualTo(ISS_LINE1);
    }

    @Test
    @DisplayName("parseTle3Lines : resynchronise si une ligne ne commence pas par '1 ' ou '2 '")
    void parseTle3Lines_resyncsOnMalformedLine() {
        String rawWithGarbage = String.join("\n",
                "GARBAGE LINE",
                ISS_NAME, ISS_LINE1, ISS_LINE2
        );
        // Après resync, l'ISS doit être parsé
        List<TleEntry> entries = tleService.parseTle3Lines(rawWithGarbage, "test");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).name()).isEqualTo(ISS_NAME);
    }

    @Test
    @DisplayName("parseEpoch : parse une chaîne ISO-8601 en Instant")
    void parseEpoch_parsesIso8601String() {
        java.time.Instant result = TleService.parseEpoch("2026-03-17T12:00:00Z");
        assertThat(result).isEqualTo(java.time.Instant.parse("2026-03-17T12:00:00Z"));
    }

    @Test
    @DisplayName("parseEpoch : retourne null si la chaîne est null")
    void parseEpoch_returnsNullForNullInput() {
        assertThat(TleService.parseEpoch(null)).isNull();
    }

    @Test
    @DisplayName("parseEpoch : retourne null si la chaîne est vide ou blanche")
    void parseEpoch_returnsNullForBlankInput() {
        assertThat(TleService.parseEpoch("")).isNull();
        assertThat(TleService.parseEpoch("   ")).isNull();
    }
}

