package projet.OrbitWatch.service;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;
import org.springframework.core.io.ClassPathResource;
import projet.OrbitWatch.dto.ConjunctionEvent;
import projet.OrbitWatch.dto.ConjunctionReport;
import projet.OrbitWatch.dto.ConjunctionRequest;
import projet.OrbitWatch.dto.SatellitePosition;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConjunctionServiceTest {

    // ── TLE ISS ───────────────────────────────────────────────────────────────
    private static final String ISS_LINE1 =
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String ISS_LINE2 =
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";

    // ── TLE CSS (fictif, réutilisé dans les tests qui passent par SGP4) ───────
    private static final String CSS_LINE1 =
            "1 48274U 21035A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String CSS_LINE2 =
            "2 48274  51.6400 290.0000 0003000  60.0000 300.1476 15.49560000999999";

    /** Service réel branché sur la propagation SGP4 — pour les tests structurels. */
    private ConjunctionService service;

    /** Service avec PropagationService mocké — pour les tests comportementaux déterministes. */
    private ConjunctionService serviceWithMock;
    private PropagationService mockPropagation;

    @BeforeAll
    void initOrekit() throws Exception {
        ClassPathResource resource = new ClassPathResource("orekit-data");
        File orekitData = resource.getFile();
        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        manager.addProvider(new DirectoryCrawler(orekitData));
    }

    @BeforeEach
    void setUp() {
        service          = new ConjunctionService(new PropagationService());
        mockPropagation  = Mockito.mock(PropagationService.class);
        serviceWithMock  = new ConjunctionService(mockPropagation);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — génération de trajectoires synthétiques
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Génère une trajectoire synthétique de {@code n} points.
     * Sat1 se déplace le long de l'axe X, sat2 commence loin puis se rapproche
     * jusqu'à un minimum à l'indice {@code minIdx}, puis s'éloigne.
     * Distance au minimum = {@code minDistKm}.
     */
    private List<SatellitePosition> trackWithMinimum(
            String name, int n, int minIdx, double minDistKm, boolean isSat2) {

        Instant t0 = Instant.now();  // aligné sur windowStart = Instant.now() dans analyze()
        List<SatellitePosition> track = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double x = isSat2 ? minDistKm + Math.abs(i - minIdx) * 10.0 : 0.0;
            track.add(new SatellitePosition(name, t0.plusSeconds(i * 60L),
                    0, 0, 400, x, 0.0, 0.0));
        }
        return track;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // analyze() — comportements généraux (PropagationService mocké)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("analyze : trajectoires synthétiques avec minimum garanti → au moins un événement détecté")
    void analyze_issVsCss_withLargeThreshold_detectsEvents() {
        // sat1 : fixe à l'origine | sat2 : distance = 5 km au step 5, sinon >> 500 km
        int n = 10, minIdx = 5;
        List<SatellitePosition> track1 = trackWithMinimum("ISS", n, minIdx, 0.0,  false);
        List<SatellitePosition> track2 = trackWithMinimum("CSS", n, minIdx, 5.0,  true);

        Mockito.when(mockPropagation.groundTrack(anyString(), anyString(), eq("ISS"),
                any(), anyInt(), anyInt())).thenReturn(track1);
        Mockito.when(mockPropagation.groundTrack(anyString(), anyString(), eq("CSS"),
                any(), anyInt(), anyInt())).thenReturn(track2);

        ConjunctionRequest req = new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2,
                "CSS", CSS_LINE1, CSS_LINE2,
                24.0, 60, 500.0);

        ConjunctionReport report = serviceWithMock.analyze(req);

        assertThat(report).isNotNull();
        assertThat(report.nameSat1()).isEqualTo("ISS");
        assertThat(report.nameSat2()).isEqualTo("CSS");
        assertThat(report.thresholdKm()).isEqualTo(500.0);
        assertThat(report.events()).isNotEmpty();
    }

    @Test
    @DisplayName("analyze : résultat trié par distance croissante")
    void analyze_eventsSortedByDistanceAscending() {
        int n = 12;
        Instant t0 = Instant.now();
        List<SatellitePosition> track1 = new ArrayList<>();
        List<SatellitePosition> track2 = new ArrayList<>();
        double[] dist = {100, 50, 20, 3, 20, 50, 20, 8, 20, 50, 100, 200};
        for (int i = 0; i < n; i++) {
            track1.add(new SatellitePosition("ISS", t0.plusSeconds(i * 60L), 0, 0, 400, 0.0, 0.0, 0.0));
            track2.add(new SatellitePosition("CSS", t0.plusSeconds(i * 60L), 0, 0, 400, dist[i], 0.0, 0.0));
        }
        Mockito.when(mockPropagation.groundTrack(anyString(), anyString(), eq("ISS"),
                any(), anyInt(), anyInt())).thenReturn(track1);
        Mockito.when(mockPropagation.groundTrack(anyString(), anyString(), eq("CSS"),
                any(), anyInt(), anyInt())).thenReturn(track2);

        ConjunctionReport report = serviceWithMock.analyze(new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2, "CSS", CSS_LINE1, CSS_LINE2, 24.0, 60, 500.0));

        List<Double> distances = report.events().stream()
                .map(ConjunctionEvent::distanceKm).toList();
        for (int i = 0; i < distances.size() - 1; i++) {
            assertThat(distances.get(i)).isLessThanOrEqualTo(distances.get(i + 1));
        }
    }

    @Test
    @DisplayName("analyze : windowStart < windowEnd dans le rapport")
    void analyze_windowStartBeforeWindowEnd() {
        int n = 5;
        Instant t0 = Instant.now();
        List<SatellitePosition> flat = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            flat.add(new SatellitePosition("X", t0.plusSeconds(i * 60L), 0, 0, 400, 1000.0, 0.0, 0.0));
        }
        Mockito.when(mockPropagation.groundTrack(anyString(), anyString(), anyString(),
                any(), anyInt(), anyInt())).thenReturn(flat);

        ConjunctionReport report = serviceWithMock.analyze(new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2, "CSS", CSS_LINE1, CSS_LINE2, 12.0, 120, 500.0));

        assertThat(report.windowStart()).isBefore(report.windowEnd());
    }

    @Test
    @DisplayName("analyze : seuil 0 km → aucun événement (sauf chevauchement exact)")
    void analyze_zeroThreshold_noEvents() {
        // Utilise ISS vs lui-même avec seuil infime — distance constante = 0,
        // jamais de minimum local strict → liste vide
        ConjunctionRequest req = new ConjunctionRequest(
                "ISS-A", ISS_LINE1, ISS_LINE2,
                "ISS-B", ISS_LINE1, ISS_LINE2,
                24.0, 60, 0.001
        );

        ConjunctionReport report = service.analyze(req);

        // Même orbite → distance constante ~0, pas de minimum local strict détecté
        assertThat(report.events()).isEmpty();
    }

    @Test
    @DisplayName("analyze : même TLE × 2 avec seuil 1 km → distance ~0, jamais de minimum local")
    void analyze_sameTleTwice_zeroDistance() {
        ConjunctionRequest req = new ConjunctionRequest(
                "ISS-A", ISS_LINE1, ISS_LINE2,
                "ISS-B", ISS_LINE1, ISS_LINE2,
                1.0, 60, 1.0
        );

        ConjunctionReport report = service.analyze(req);

        // Même orbite → distance constante = 0 → pas de minimum local strict
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
        int n = 10, minIdx = 5;
        List<SatellitePosition> track1 = trackWithMinimum("ISS", n, minIdx, 0.0, false);
        List<SatellitePosition> track2 = trackWithMinimum("CSS", n, minIdx, 5.0, true);
        Mockito.when(mockPropagation.groundTrack(anyString(), anyString(), eq("ISS"),
                any(), anyInt(), anyInt())).thenReturn(track1);
        Mockito.when(mockPropagation.groundTrack(anyString(), anyString(), eq("CSS"),
                any(), anyInt(), anyInt())).thenReturn(track2);

        ConjunctionReport report = serviceWithMock.analyze(new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2, "CSS", CSS_LINE1, CSS_LINE2, 24.0, 60, 500.0));

        report.events().forEach(e -> {
            assertThat(e.tca()).isAfterOrEqualTo(report.windowStart());
            assertThat(e.tca()).isBeforeOrEqualTo(report.windowEnd());
        });
    }

    @Test
    @DisplayName("analyze : chaque événement a une distanceKm positive")
    void analyze_distanceAlwaysPositive() {
        int n = 10, minIdx = 5;
        List<SatellitePosition> track1 = trackWithMinimum("ISS", n, minIdx, 0.0, false);
        List<SatellitePosition> track2 = trackWithMinimum("CSS", n, minIdx, 5.0, true);
        Mockito.when(mockPropagation.groundTrack(anyString(), anyString(), eq("ISS"),
                any(), anyInt(), anyInt())).thenReturn(track1);
        Mockito.when(mockPropagation.groundTrack(anyString(), anyString(), eq("CSS"),
                any(), anyInt(), anyInt())).thenReturn(track2);

        ConjunctionReport report = serviceWithMock.analyze(new ConjunctionRequest(
                "ISS", ISS_LINE1, ISS_LINE2, "CSS", CSS_LINE1, CSS_LINE2, 24.0, 60, 500.0));

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
    @DisplayName("refineTca : parabole symétrique (d0=d2) → offset = 0 → TCA = tPrev")
    void refineTca_symmetricParabola_returnsCenter() {
        var tPrev = java.time.Instant.parse("2026-03-07T12:00:00Z");
        // d0=10, d1=5, d2=10 → numérateur (d0-d2)=0 → offset=0 → TCA = tPrev
        var tca = ConjunctionService.refineTca(tPrev, 60, 10.0, 5.0, 10.0);
        assertThat(tca).isEqualTo(tPrev);
    }

    @Test
    @DisplayName("refineTca : minimum décalé vers la droite → TCA entre tPrev et tPrev+2*step")
    void refineTca_asymmetricParabola_tcaInWindow() {
        var tPrev = java.time.Instant.parse("2026-03-07T12:00:00Z");
        // d0=10, d1=4, d2=6 → minimum parabolique entre t et t+step
        // offset = 60 * (10-6) / (2*(10-8+6)) = 60 * 4 / 16 = 15s
        var tca = ConjunctionService.refineTca(tPrev, 60, 10.0, 4.0, 6.0);
        assertThat(tca).isEqualTo(tPrev.plusSeconds(15));
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

