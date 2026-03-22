package projet.OrbitWatch.service;

import org.junit.jupiter.api.*;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;
import org.springframework.core.io.ClassPathResource;
import projet.OrbitWatch.dto.SatellitePosition;
import projet.OrbitWatch.dto.TleEntry;

import java.io.File;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests unitaires du PropagationService.
 *
 * Orekit est initialisé une seule fois pour toute la classe (@BeforeAll) afin
 * d'éviter de charger les données à chaque test.
 *
 * TLE de référence : ISS (ZARYA) — époque 2026-03-07, valeurs officielles NORAD.
 * Constantes physiques attendues :
 *   - Inclinaison ISS ≈ 51.64° → latitude toujours dans [-51.64°, +51.64°]
 *   - Altitude LEO  ≈ 400 km  → intervalle de tolérance [380, 440] km
 *   - Norme ECI     ≈ 6 778 km (rayon terrestre + altitude)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PropagationServiceTest {

    // ── TLE ISS public (époque proche du 2026-03-07) ─────────────────────────
    private static final String TLE1 =
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String TLE2 =
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";

    // Instant correspondant à l'époque du TLE (2026-03-07 12:00:00 UTC)
    private static final Instant TLE_EPOCH = Instant.parse("2026-03-07T12:00:00Z");

    private PropagationService service;

    // ── Initialisation Orekit (une seule fois par suite) ──────────────────────
    @BeforeAll
    void initOrekit() throws Exception {
        ClassPathResource resource = new ClassPathResource("orekit-data");
        File orekitData = resource.getFile();
        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        manager.addProvider(new DirectoryCrawler(orekitData));
    }

    @BeforeEach
    void setUp() {
        service = new PropagationService();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // propagate() — position instantanée
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("La position retournée n'est pas nulle")
    void propagate_returnsNonNullResult() {
        SatellitePosition pos = service.propagate(TLE1, TLE2, "ISS", TLE_EPOCH);
        assertThat(pos).isNotNull();
    }

    @Test
    @DisplayName("Le nom du satellite est correctement renseigné")
    void propagate_setsName() {
        SatellitePosition pos = service.propagate(TLE1, TLE2, "ISS", TLE_EPOCH);
        assertThat(pos.name()).isEqualTo("ISS");
    }

    @Test
    @DisplayName("Le nom est 'UNKNOWN' quand null est passé")
    void propagate_defaultsNameToUnknown() {
        SatellitePosition pos = service.propagate(TLE1, TLE2, null, TLE_EPOCH);
        assertThat(pos.name()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("L'epoch retourné est cohérent avec la date demandée (±2 secondes)")
    void propagate_epochIsConsistent() {
        SatellitePosition pos = service.propagate(TLE1, TLE2, "ISS", TLE_EPOCH);
        assertThat(pos.epoch())
                .isAfterOrEqualTo(TLE_EPOCH.minusSeconds(2))
                .isBeforeOrEqualTo(TLE_EPOCH.plusSeconds(2));
    }

    @Test
    @DisplayName("Latitude ISS dans l'intervalle d'inclinaison [-51.64°, +51.64°]")
    void propagate_latitudeWithinISSInclination() {
        SatellitePosition pos = service.propagate(TLE1, TLE2, "ISS", TLE_EPOCH);
        assertThat(pos.latitude())
                .as("Latitude doit être dans [-51.64°, +51.64°]")
                .isBetween(-51.64, 51.64);
    }

    @Test
    @DisplayName("Longitude dans l'intervalle [-180°, +180°]")
    void propagate_longitudeInValidRange() {
        SatellitePosition pos = service.propagate(TLE1, TLE2, "ISS", TLE_EPOCH);
        assertThat(pos.longitude())
                .as("Longitude doit être dans [-180°, +180°]")
                .isBetween(-180.0, 180.0);
    }

    @Test
    @DisplayName("Altitude LEO cohérente avec l'ISS : [380, 440] km")
    void propagate_altitudeIsLEO() {
        SatellitePosition pos = service.propagate(TLE1, TLE2, "ISS", TLE_EPOCH);
        assertThat(pos.altitude())
                .as("Altitude attendue entre 380 et 440 km pour l'ISS")
                .isBetween(380.0, 440.0);
    }

    @Test
    @DisplayName("Norme du vecteur ECI cohérente avec une orbite LEO [6 700, 6 900] km")
    void propagate_eciNormIsLEO() {
        SatellitePosition pos = service.propagate(TLE1, TLE2, "ISS", TLE_EPOCH);
        double norm = Math.sqrt(
                pos.x() * pos.x() +
                pos.y() * pos.y() +
                pos.z() * pos.z()
        );
        assertThat(norm)
                .as("Norme ECI attendue entre 6 700 km et 6 900 km")
                .isBetween(6700.0, 6900.0);
    }

    @Test
    @DisplayName("Quand epoch est null, la propagation utilise l'époque du TLE sans erreur")
    void propagate_nullEpochUseTleEpoch() {
        assertThatCode(() -> service.propagate(TLE1, TLE2, "ISS", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Propagation à 45 min après l'époque TLE reste physiquement cohérente")
    void propagate_propagationInFutureIsCoherent() {
        Instant future = TLE_EPOCH.plusSeconds(45 * 60);
        SatellitePosition pos = service.propagate(TLE1, TLE2, "ISS", future);
        assertThat(pos.altitude()).isBetween(380.0, 440.0);
        assertThat(pos.latitude()).isBetween(-52.0, 52.0);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // groundTrack() — liste de positions
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Ground track 90 min / pas 60 s → 91 points (0..90 inclus)")
    void groundTrack_returnsCorrectNumberOfPoints() {
        List<SatellitePosition> track =
                service.groundTrack(TLE1, TLE2, "ISS", TLE_EPOCH, 90, 60);
        // totalSteps = (90 * 60) / 60 = 90 → points 0..90 = 91
        assertThat(track).hasSize(91);
    }

    @Test
    @DisplayName("Ground track : le premier point correspond à l'instant de départ (±2 s)")
    void groundTrack_firstPointMatchesStartEpoch() {
        List<SatellitePosition> track =
                service.groundTrack(TLE1, TLE2, "ISS", TLE_EPOCH, 90, 60);
        assertThat(track.get(0).epoch())
                .isAfterOrEqualTo(TLE_EPOCH.minusSeconds(2))
                .isBeforeOrEqualTo(TLE_EPOCH.plusSeconds(2));
    }

    @Test
    @DisplayName("Ground track : le dernier point est ~90 min après le premier")
    void groundTrack_lastPointIs90MinAfterFirst() {
        List<SatellitePosition> track =
                service.groundTrack(TLE1, TLE2, "ISS", TLE_EPOCH, 90, 60);
        Instant first = track.get(0).epoch();
        Instant last  = track.get(track.size() - 1).epoch();
        long deltaSeconds = last.getEpochSecond() - first.getEpochSecond();
        // 90 pas × 60 s = 5 400 s (±2 s de tolérance d'arrondi Orekit)
        assertThat(deltaSeconds).isBetween(5398L, 5402L);
    }

    @Test
    @DisplayName("Ground track : chaque point a une altitude LEO cohérente [380, 440] km")
    void groundTrack_allPointsHaveLEOAltitude() {
        List<SatellitePosition> track =
                service.groundTrack(TLE1, TLE2, "ISS", TLE_EPOCH, 90, 60);
        assertThat(track)
                .allSatisfy(p -> assertThat(p.altitude())
                        .as("Altitude du point %s", p.epoch())
                        .isBetween(380.0, 440.0));
    }

    @Test
    @DisplayName("snapshotCatalog : liste vide → retourne liste vide")
    void snapshotCatalog_emptyInput_returnsEmptyList() {
        List<SatellitePosition> result = service.snapshotCatalog(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("snapshotCatalog : un satellite valide → retourne 1 position")
    void snapshotCatalog_oneSatellite_returnsOnePosition() {
        TleEntry entry = new TleEntry("ISS (ZARYA)", TLE1, TLE2, "stations", TLE_EPOCH);
        List<SatellitePosition> result = service.snapshotCatalog(List.of(entry));
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("snapshotCatalog : la position calculée est physiquement cohérente")
    void snapshotCatalog_positionIsPhysicallyCoherent() {
        TleEntry entry = new TleEntry("ISS (ZARYA)", TLE1, TLE2, "stations", TLE_EPOCH);
        SatellitePosition pos = service.snapshotCatalog(List.of(entry)).get(0);
        assertThat(pos.altitude()).isBetween(380.0, 440.0);
        assertThat(pos.latitude()).isBetween(-52.0, 52.0);
        assertThat(pos.longitude()).isBetween(-180.0, 180.0);
    }

    @Test
    @DisplayName("snapshotCatalog : le nom du satellite est préservé")
    void snapshotCatalog_preservesSatelliteName() {
        TleEntry entry = new TleEntry("ISS (ZARYA)", TLE1, TLE2, "stations", TLE_EPOCH);
        SatellitePosition pos = service.snapshotCatalog(List.of(entry)).get(0);
        assertThat(pos.name()).isEqualTo("ISS (ZARYA)");
    }

    @Test
    @DisplayName("snapshotCatalog : un TLE invalide est ignoré, les autres sont propagés")
    void snapshotCatalog_invalidTleIsSkipped() {
        TleEntry valid   = new TleEntry("ISS (ZARYA)", TLE1, TLE2, "stations", TLE_EPOCH);
        TleEntry invalid = new TleEntry("BROKEN", "invalid line 1", "invalid line 2", "stations", TLE_EPOCH);
        List<SatellitePosition> result = service.snapshotCatalog(List.of(valid, invalid));
        // Le TLE invalide doit être silencieusement ignoré
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("ISS (ZARYA)");
    }

    @Test
    @DisplayName("snapshotCatalog : plusieurs satellites valides → retourne autant de positions")
    void snapshotCatalog_multipleSatellites_returnsAllPositions() {
        // On utilise le même TLE deux fois avec des noms différents pour simuler 2 satellites
        TleEntry sat1 = new TleEntry("SAT-1", TLE1, TLE2, "stations", TLE_EPOCH);
        TleEntry sat2 = new TleEntry("SAT-2", TLE1, TLE2, "stations", TLE_EPOCH);
        List<SatellitePosition> result = service.snapshotCatalog(List.of(sat1, sat2));
        assertThat(result).hasSize(2);
        assertThat(result).extracting(SatellitePosition::name)
                .containsExactlyInAnyOrder("SAT-1", "SAT-2");
    }

}


