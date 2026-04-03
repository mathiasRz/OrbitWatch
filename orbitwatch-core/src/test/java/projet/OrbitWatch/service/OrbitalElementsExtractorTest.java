package projet.OrbitWatch.service;

import org.junit.jupiter.api.*;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;
import org.springframework.core.io.ClassPathResource;
import projet.OrbitWatch.dto.OrbitalElements;

import java.io.File;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests unitaires de {@link OrbitalElementsExtractor}.
 *
 * <p>TLE de référence : ISS (ZARYA) — NORAD 25544.
 * Valeurs physiques attendues :
 * <ul>
 *   <li>Demi-grand axe  ≈ 6 780 km  (tolérance ±50 km)</li>
 *   <li>Inclinaison     ≈ 51.64°    (tolérance ±0.1°)</li>
 *   <li>Excentricité    &lt; 0.001  (orbite quasi-circulaire)</li>
 *   <li>Altitude périgée ≈ 400 km   (tolérance ±50 km)</li>
 *   <li>Altitude apogée  ≈ 400 km   (tolérance ±50 km)</li>
 *   <li>NORAD ID = 25544</li>
 * </ul>
 *
 * Orekit est initialisé une seule fois via {@code @BeforeAll}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrbitalElementsExtractorTest {

    // ── TLE ISS public (même époque que PropagationServiceTest) ──────────────
    private static final String SAT_NAME = "ISS (ZARYA)";
    private static final String TLE1 =
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String TLE2 =
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";

    private OrbitalElementsExtractor extractor;

    @BeforeAll
    void initOrekit() throws Exception {
        ClassPathResource resource = new ClassPathResource("orekit-data");
        File orekitData = resource.getFile();
        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        manager.addProvider(new DirectoryCrawler(orekitData));
    }

    @BeforeEach
    void setUp() {
        extractor = new OrbitalElementsExtractor();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Contrat de base
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extract() retourne un résultat non nul")
    void extract_returnsNonNull() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements).isNotNull();
    }

    @Test
    @DisplayName("NORAD ID extrait = 25544 (ISS)")
    void extract_noradId_isCorrect() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.noradId()).isEqualTo(25544);
    }

    @Test
    @DisplayName("Le nom du satellite est préservé tel quel")
    void extract_satelliteName_isPreserved() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.satelliteName()).isEqualTo(SAT_NAME);
    }

    @Test
    @DisplayName("L'époque TLE est non nulle et cohérente (après 2020)")
    void extract_epochTle_isNonNullAndRecent() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.epochTle()).isNotNull();
        assertThat(elements.epochTle().getEpochSecond())
                .as("L'époque TLE doit être postérieure au 01/01/2020")
                .isGreaterThan(1577836800L); // 2020-01-01T00:00:00Z
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Paramètres Keplériens — valeurs physiques ISS
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Demi-grand axe ISS ≈ 6 780 km (±50 km)")
    void extract_semiMajorAxis_isISSValue() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.semiMajorAxisKm())
                .as("Demi-grand axe attendu ≈ 6 780 km pour l'ISS")
                .isBetween(6730.0, 6830.0);
    }

    @Test
    @DisplayName("Excentricité ISS < 0.001 (orbite quasi-circulaire)")
    void extract_eccentricity_isNearZero() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.eccentricity())
                .as("L'ISS a une orbite quasi-circulaire, e < 0.001")
                .isLessThan(0.001);
    }

    @Test
    @DisplayName("Inclinaison ISS ≈ 51.64° (±0.1°)")
    void extract_inclination_isISSValue() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.inclinationDeg())
                .as("Inclinaison attendue ≈ 51.64° pour l'ISS")
                .isBetween(51.54, 51.74);
    }

    @Test
    @DisplayName("RAAN dans l'intervalle valide [0°, 360°]")
    void extract_raan_isInValidRange() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.raanDeg())
                .as("RAAN doit être dans [0°, 360°]")
                .isBetween(0.0, 360.0);
    }

    @Test
    @DisplayName("Argument du périgée dans l'intervalle valide [0°, 360°]")
    void extract_argOfPerigee_isInValidRange() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.argOfPerigeeDeg())
                .as("Argument du périgée doit être dans [0°, 360°]")
                .isBetween(0.0, 360.0);
    }

    @Test
    @DisplayName("Mouvement moyen ISS ≈ 15.5 rev/jour (orbite LEO)")
    void extract_meanMotion_isISSValue() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.meanMotionRevDay())
                .as("Mouvement moyen attendu ≈ 15.5 rev/jour pour l'ISS")
                .isBetween(15.0, 16.0);
    }

    @Test
    @DisplayName("Altitude périgée ISS ≈ 400 km (±50 km)")
    void extract_altitudePerigee_isISSValue() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.altitudePerigeeKm())
                .as("Altitude périgée attendue ≈ 400 km pour l'ISS")
                .isBetween(350.0, 450.0);
    }

    @Test
    @DisplayName("Altitude apogée ISS ≈ 400 km (±50 km)")
    void extract_altitudeApogee_isISSValue() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.altitudeApogeeKm())
                .as("Altitude apogée attendue ≈ 400 km pour l'ISS")
                .isBetween(350.0, 450.0);
    }

    @Test
    @DisplayName("Altitude apogée ≥ altitude périgée (cohérence physique)")
    void extract_apogeeIsGreaterOrEqualThanPerigee() {
        OrbitalElements elements = extractor.extract(SAT_NAME, TLE1, TLE2);
        assertThat(elements.altitudeApogeeKm())
                .as("L'apogée doit être supérieur ou égal au périgée")
                .isGreaterThanOrEqualTo(elements.altitudePerigeeKm());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Robustesse
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TLE malformé lève une exception (pas de retour silencieux)")
    void extract_malformedTle_throwsException() {
        assertThatThrownBy(() -> extractor.extract("BROKEN", "bad line 1", "bad line 2"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Les espaces en début/fin de lignes TLE sont tolérés (trim)")
    void extract_trimsWhitespace() {
        assertThatCode(() -> extractor.extract(SAT_NAME, "  " + TLE1 + "  ", "  " + TLE2 + "  "))
                .doesNotThrowAnyException();
    }
}

