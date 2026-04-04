package projet.OrbitWatch.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import projet.OrbitWatch.dto.OrbitalElements;
import projet.OrbitWatch.dto.SatelliteSummary;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.model.ConjunctionAlert;
import projet.OrbitWatch.model.OrbitalHistory;
import projet.OrbitWatch.repository.ConjunctionAlertRepository;
import projet.OrbitWatch.repository.OrbitalHistoryRepository;
import projet.OrbitWatch.service.OrbitalElementsExtractor;
import projet.OrbitWatch.service.TleService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Contrôleur REST exposant l'historique orbital et le profil satellite.
 *
 * <p>Base URL : {@code /api/v1}
 *
 * <ul>
 *   <li>{@code GET /api/v1/orbital-history/{noradId}?days=30} — historique sur N jours</li>
 *   <li>{@code GET /api/v1/orbital-history/{noradId}/latest} — dernier snapshot</li>
 *   <li>{@code GET /api/v1/satellite/{noradId}/summary} — profil agrégé complet</li>
 *   <li>{@code GET /api/v1/orbital-history/{noradId}/export?format=csv} — export CSV</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class OrbitalHistoryController {

    private static final Logger log = LoggerFactory.getLogger(OrbitalHistoryController.class);

    /** Badge jaune si l'âge TLE dépasse 7 jours. */
    private static final double TLE_WARNING_THRESHOLD_HOURS = 168.0;

    /** Nombre de conjonctions récentes incluses dans le summary. */
    private static final int RECENT_CONJUNCTIONS_LIMIT = 5;

    private final OrbitalHistoryRepository   orbitalHistoryRepository;
    private final ConjunctionAlertRepository conjunctionAlertRepository;
    private final TleService                 tleService;
    private final OrbitalElementsExtractor   extractor;

    public OrbitalHistoryController(OrbitalHistoryRepository orbitalHistoryRepository,
                                    ConjunctionAlertRepository conjunctionAlertRepository,
                                    TleService tleService,
                                    OrbitalElementsExtractor extractor) {
        this.orbitalHistoryRepository   = orbitalHistoryRepository;
        this.conjunctionAlertRepository = conjunctionAlertRepository;
        this.tleService                 = tleService;
        this.extractor                  = extractor;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/orbital-history/{noradId}?days=30
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retourne l'historique orbital d'un satellite sur une fenêtre glissante.
     *
     * @param noradId identifiant NORAD du satellite
     * @param days    nombre de jours en arrière (défaut : 30, max : 365)
     * @return liste des snapshots triés par date croissante
     */
    @GetMapping("/orbital-history/{noradId}")
    public ResponseEntity<List<OrbitalElements>> getHistory(
            @PathVariable int noradId,
            @RequestParam(defaultValue = "30") int days) {

        int clampedDays = Math.min(Math.max(days, 1), 365);
        Instant from    = Instant.now().minus(clampedDays, ChronoUnit.DAYS);
        Instant to      = Instant.now();

        List<OrbitalHistory> snapshots = orbitalHistoryRepository
                .findByNoradIdAndFetchedAtBetweenOrderByFetchedAtAsc(noradId, from, to);

        List<OrbitalElements> result = snapshots.stream()
                .map(this::toDto)
                .toList();

        log.debug("[OrbitalHistoryController] getHistory({}, {}j) → {} snapshots", noradId, clampedDays, result.size());
        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/orbital-history/{noradId}/latest
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retourne le dernier snapshot orbital enregistré pour un satellite.
     *
     * @param noradId identifiant NORAD du satellite
     * @return 200 + OrbitalElements, ou 404 si aucun historique disponible
     */
    @GetMapping("/orbital-history/{noradId}/latest")
    public ResponseEntity<OrbitalElements> getLatest(@PathVariable int noradId) {

        List<OrbitalHistory> snapshots = orbitalHistoryRepository
                .findByNoradIdOrderByFetchedAtDesc(noradId, PageRequest.of(0, 1));

        if (snapshots.isEmpty()) {
            log.debug("[OrbitalHistoryController] getLatest({}) → aucun snapshot", noradId);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(toDto(snapshots.get(0)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/satellite/{noradId}/summary
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retourne le profil agrégé complet d'un satellite.
     *
     * <p>Agrège : TLE courant, dernier snapshot orbital, conjonctions récentes.
     * Le champ {@code textSummary} est intentionnellement en langage naturel
     * pour l'indexation RAG M5.
     *
     * @param noradId identifiant NORAD du satellite
     * @return 200 + SatelliteSummary, ou 404 si le satellite est inconnu
     */
    @GetMapping("/satellite/{noradId}/summary")
    public ResponseEntity<SatelliteSummary> getSummary(@PathVariable int noradId) {

        // ── 1. Dernier snapshot orbital ───────────────────────────────────────
        List<OrbitalHistory> snapshots = orbitalHistoryRepository
                .findByNoradIdOrderByFetchedAtDesc(noradId, PageRequest.of(0, 1));

        if (snapshots.isEmpty()) {
            log.debug("[OrbitalHistoryController] getSummary({}) → aucun snapshot", noradId);
            return ResponseEntity.notFound().build();
        }

        OrbitalHistory latest = snapshots.get(0);
        OrbitalElements latestElements = toDto(latest);

        // ── 2. TLE courant en mémoire ─────────────────────────────────────────
        Optional<TleEntry> tleOpt = tleService.findAll().stream()
                .filter(e -> {
                    try {
                        return extractor.extract(e.name(), e.line1(), e.line2()).noradId() == noradId;
                    } catch (Exception ex) {
                        return false;
                    }
                })
                .findFirst();

        String  tleLine1    = tleOpt.map(TleEntry::line1).orElse("");
        String  tleLine2    = tleOpt.map(TleEntry::line2).orElse("");
        Instant tleEpoch    = latestElements.epochTle();
        double  tleAgeHours = tleEpoch != null
                ? ChronoUnit.MINUTES.between(tleEpoch, Instant.now()) / 60.0
                : -1.0;

        // ── 3. Conjonctions récentes impliquant ce satellite ──────────────────
        String satName = latest.getSatelliteName();
        List<ConjunctionAlert> recent = conjunctionAlertRepository
                .findByAcknowledgedFalseOrderByTcaAsc()
                .stream()
                .filter(a -> satName != null && (
                        satName.equalsIgnoreCase(a.getNameSat1()) ||
                        satName.equalsIgnoreCase(a.getNameSat2())))
                .limit(RECENT_CONJUNCTIONS_LIMIT)
                .toList();

        // ── 4. Résumé textuel (langage naturel — prerequis RAG M5) ────────────
        String ageWarning = tleAgeHours > TLE_WARNING_THRESHOLD_HOURS
                ? " [TLE OBSOLÈTE : %.0f h]".formatted(tleAgeHours)
                : "";

        String conjunction = recent.isEmpty()
                ? "aucune conjunction récente détectée"
                : "dernière conjunction à %.1f km le %s".formatted(
                        recent.get(0).getDistanceKm(),
                        recent.get(0).getTca());

        String textSummary = "%s, NORAD %d, altitude %.0f–%.0f km, inclinaison %.2f°, %s%s".formatted(
                satName,
                noradId,
                latestElements.altitudePerigeeKm(),
                latestElements.altitudeApogeeKm(),
                latestElements.inclinationDeg(),
                conjunction,
                ageWarning);

        SatelliteSummary summary = new SatelliteSummary(
                noradId,
                satName,
                tleLine1,
                tleLine2,
                tleEpoch,
                tleAgeHours,
                latestElements,
                recent,
                textSummary);

        log.info("[OrbitalHistoryController] getSummary({}) → {}", noradId, satName);
        return ResponseEntity.ok(summary);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/orbital-history/{noradId}/export?format=csv
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exporte l'historique orbital d'un satellite au format CSV.
     *
     * @param noradId identifiant NORAD du satellite
     * @param days    nombre de jours à inclure (défaut : 30, max : 365)
     * @return fichier CSV téléchargeable
     */
    @GetMapping("/orbital-history/{noradId}/export")
    public ResponseEntity<byte[]> exportCsv(
            @PathVariable int noradId,
            @RequestParam(defaultValue = "30") int days) {

        int clampedDays = Math.min(Math.max(days, 1), 365);
        Instant from    = Instant.now().minus(clampedDays, ChronoUnit.DAYS);
        Instant to      = Instant.now();

        List<OrbitalHistory> snapshots = orbitalHistoryRepository
                .findByNoradIdAndFetchedAtBetweenOrderByFetchedAtAsc(noradId, from, to);

        StringBuilder csv = new StringBuilder();
        csv.append("fetchedAt,noradId,satelliteName,semiMajorAxisKm,eccentricity,")
           .append("inclinationDeg,raanDeg,argOfPerigeeDeg,meanMotionRevDay,")
           .append("altitudePerigeeKm,altitudeApogeeKm\n");

        for (OrbitalHistory s : snapshots) {
            csv.append(s.getFetchedAt()).append(',')
               .append(s.getNoradId()).append(',')
               .append(s.getSatelliteName()).append(',')
               .append(s.getSemiMajorAxisKm()).append(',')
               .append(s.getEccentricity()).append(',')
               .append(s.getInclinationDeg()).append(',')
               .append(s.getRaanDeg()).append(',')
               .append(s.getArgOfPerigeeDeg()).append(',')
               .append(s.getMeanMotionRevDay()).append(',')
               .append(s.getAltitudePerigeeKm()).append(',')
               .append(s.getAltitudeApogeeKm()).append('\n');
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        String filename = "orbital-history-%d.csv".formatted(noradId);

        log.info("[OrbitalHistoryController] exportCsv({}) → {} lignes", noradId, snapshots.size());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(bytes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mapping helper
    // ─────────────────────────────────────────────────────────────────────────

    /** Convertit une entité {@link OrbitalHistory} en DTO {@link OrbitalElements}. */
    private OrbitalElements toDto(OrbitalHistory h) {
        return new OrbitalElements(
                h.getNoradId(),
                h.getSatelliteName(),
                h.getFetchedAt(),          // epochTle = fetchedAt (meilleure approximation sans parser le TLE)
                h.getSemiMajorAxisKm(),
                h.getEccentricity(),
                h.getInclinationDeg(),
                h.getRaanDeg(),
                h.getArgOfPerigeeDeg(),
                h.getMeanMotionRevDay(),
                h.getAltitudePerigeeKm(),
                h.getAltitudeApogeeKm()
        );
    }
}

