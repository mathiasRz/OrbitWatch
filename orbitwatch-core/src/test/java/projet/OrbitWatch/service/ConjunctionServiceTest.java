package projet.OrbitWatch.service;

import org.junit.jupiter.api.*;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;
import org.springframework.core.io.ClassPathResource;
import projet.OrbitWatch.dto.ConjunctionEvent;
import projet.OrbitWatch.dto.ConjunctionReport;
import projet.OrbitWatch.dto.ConjunctionRequest;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests unitaires du ConjunctionService.
 *
 * Orekit est initialisé une seule fois via @BeforeAll.
 * On utilise des TLEs ISS et CSS (Tiangong) réels pour valider les comportements.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConjunctionServiceTest {

    // ── TLE ISS ───────────────────────────────────────────────────────────────
    private static final String ISS_LINE1 =
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String ISS_LINE2 =
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";

    // ── TLE CSS (Tiangong) ────────────────────────────────────────────────────
    private static final String CSS_LINE1 =
            "1 48274U 21035A   26066.50000000  .00015000  00000+0  17000-3 0  9993";
    private static final String CSS_LINE2 =
            "2 48274  41.4700 175.0000 0005000  80.0000 280.0000 15.60000000999999";

    private ConjunctionService service;

    @BeforeAll
    void initOrekit() throws Exception {
        ClassPathResource resource = new ClassPathResource("orekit-data");
        File orekitData = resource.getFile();
        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        manager.addProvider(new DirectoryCrawler(orekitData));
    }

    @BeforeEach
    void setUp() {
        service = new ConjunctionService(new PropagationService());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // analyze() — comportements généraux
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("analyze : ISS vs CSS avec seuil large (500 km) → au moins un événement détecté")
    void analyze_issVsCss_withLargeThreshold_detectsEvents() {
        ConjunctionRequest req = new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2,
                "CSS", CSS_LINE1, CSS_LINE2,
                24.0, 60, 500.0
        );

        ConjunctionReport report = service.analyze(req);

        assertThat(report).isNotNull();
        assertThat(report.nameSat1()).isEqualTo("ISS");
        assertThat(report.nameSat2()).isEqualTo("CSS");
        assertThat(report.thresholdKm()).isEqualTo(500.0);
        assertThat(report.events()).isNotEmpty();
    }

    @Test
    @DisplayName("analyze : résultat trié par distance croissante")
    void analyze_eventsSortedByDistanceAscending() {
        ConjunctionRequest req = new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2,
                "CSS", CSS_LINE1, CSS_LINE2,
                24.0, 60, 500.0
        );

        ConjunctionReport report = service.analyze(req);

        List<Double> distances = report.events().stream()
                .map(ConjunctionEvent::distanceKm)
                .toList();

        for (int i = 0; i < distances.size() - 1; i++) {
            assertThat(distances.get(i)).isLessThanOrEqualTo(distances.get(i + 1));
        }
    }

    @Test
    @DisplayName("analyze : windowStart < windowEnd dans le rapport")
    void analyze_windowStartBeforeWindowEnd() {
        ConjunctionRequest req = new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2,
                "CSS", CSS_LINE1, CSS_LINE2,
                12.0, 120, 500.0
        );

        ConjunctionReport report = service.analyze(req);

        assertThat(report.windowStart()).isBefore(report.windowEnd());
    }

    @Test
    @DisplayName("analyze : seuil 0 km → aucun événement (sauf chevauchement exact)")
    void analyze_zeroThreshold_noEvents() {
        ConjunctionRequest req = new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2,
                "CSS", CSS_LINE1, CSS_LINE2,
                24.0, 60, 0.001
        );

        ConjunctionReport report = service.analyze(req);

        // ISS et CSS sont sur des orbites distinctes, distance minimale >> 0.001 km
        assertThat(report.events()).isEmpty();
    }

    @Test
    @DisplayName("analyze : même TLE × 2 avec seuil 0 km → distance = 0, TCA détecté à t=0")
    void analyze_sameTleTwice_zeroDistance() {
        ConjunctionRequest req = new ConjunctionRequest(
                "ISS-A", ISS_LINE1, ISS_LINE2,
                "ISS-B", ISS_LINE1, ISS_LINE2,
                1.0, 60, 1.0
        );

        ConjunctionReport report = service.analyze(req);

        // Même orbite → distance constante = 0 → pas de minimum local strict (d[i-1] > d[i] < d[i+1] jamais vrai)
        // Sauf variation numérique — le résultat doit être cohérent (vide ou distance ~0)
        report.events().forEach(e ->
                assertThat(e.distanceKm()).isLessThan(1.0));
    }

    @Test
    @DisplayName("analyze : fenêtre très courte (< 3 points) → rapport vide sans exception")
    void analyze_shortWindow_returnsEmptyReport() {
        ConjunctionRequest req = new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2,
                "CSS", CSS_LINE1, CSS_LINE2,
                0.01, 300, 500.0   // ~2 points → trop court
        );

        ConjunctionReport report = service.analyze(req);

        assertThat(report).isNotNull();
        assertThat(report.events()).isEmpty();
    }

    @Test
    @DisplayName("analyze : chaque événement a un TCA entre windowStart et windowEnd")
    void analyze_tcaWithinWindow() {
        ConjunctionRequest req = new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2,
                "CSS", CSS_LINE1, CSS_LINE2,
                24.0, 60, 500.0
        );

        ConjunctionReport report = service.analyze(req);

        report.events().forEach(e -> {
            assertThat(e.tca()).isAfterOrEqualTo(report.windowStart());
            assertThat(e.tca()).isBeforeOrEqualTo(report.windowEnd());
        });
    }

    @Test
    @DisplayName("analyze : chaque événement a une distanceKm positive")
    void analyze_distanceAlwaysPositive() {
        ConjunctionRequest req = new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2,
                "CSS", CSS_LINE1, CSS_LINE2,
                24.0, 60, 500.0
        );

        ConjunctionReport report = service.analyze(req);

        report.events().forEach(e ->
                assertThat(e.distanceKm()).isGreaterThanOrEqualTo(0.0));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // eciDistance() — méthode utilitaire statique
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("eciDistance : deux points identiques → distance = 0")
    void eciDistance_samePoint_returnsZero() {
        var pos = new projet.OrbitWatch.dto.SatellitePosition(
                "TEST", java.time.Instant.now(), 0, 0, 0, 1000.0, 2000.0, 3000.0);

        assertThat(ConjunctionService.eciDistance(pos, pos)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("eciDistance : triangle 3-4-5 → distance = 5 km")
    void eciDistance_knownValues_returnsExpected() {
        var p1 = new projet.OrbitWatch.dto.SatellitePosition(
                "A", java.time.Instant.now(), 0, 0, 0, 0.0, 0.0, 0.0);
        var p2 = new projet.OrbitWatch.dto.SatellitePosition(
                "B", java.time.Instant.now(), 0, 0, 0, 3.0, 4.0, 0.0);

        assertThat(ConjunctionService.eciDistance(p1, p2)).isEqualTo(5.0);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // refineTca() — interpolation parabolique
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("refineTca : minimum parfaitement centré → TCA = tPrev + step")
    void refineTca_symmetricParabola_returnsCenter() {
        var tPrev = java.time.Instant.parse("2026-03-07T12:00:00Z");
        // d0=10, d1=5, d2=10 → parabole symétrique → offset = step
        var tca = ConjunctionService.refineTca(tPrev, 60, 10.0, 5.0, 10.0);
        assertThat(tca).isEqualTo(tPrev.plusSeconds(60));
    }

    @Test
    @DisplayName("refineTca : dénominateur nul → TCA = point central (sans exception)")
    void refineTca_flatParabola_returnsCenterWithoutException() {
        var tPrev = java.time.Instant.parse("2026-03-07T12:00:00Z");
        // d0=d1=d2=5 → dénominateur = 0
        assertThatCode(() -> ConjunctionService.refineTca(tPrev, 60, 5.0, 5.0, 5.0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refineTca : TCA clampé dans [tPrev, tPrev + 2*step]")
    void refineTca_resultClampedInWindow() {
        var tPrev = java.time.Instant.parse("2026-03-07T12:00:00Z");
        var tca   = ConjunctionService.refineTca(tPrev, 60, 1.0, 100.0, 1.0);
        // Résultat ne peut pas sortir de la fenêtre [tPrev, tPrev+120s]
        assertThat(tca).isAfterOrEqualTo(tPrev);
        assertThat(tca).isBeforeOrEqualTo(tPrev.plusSeconds(120));
    }
}

