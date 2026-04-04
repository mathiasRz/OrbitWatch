package projet.OrbitWatch.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import projet.OrbitWatch.dto.ConjunctionReport;
import projet.OrbitWatch.dto.ConjunctionRequest;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.model.ConjunctionAlert;
import projet.OrbitWatch.repository.ConjunctionAlertRepository;
import projet.OrbitWatch.repository.ConjunctionSpecification;
import projet.OrbitWatch.service.ConjunctionService;
import projet.OrbitWatch.service.TleService;

import java.time.Instant;
import java.util.List;

/**
 * Contrôleur REST exposant les fonctionnalités de détection de rapprochements.
 * Base URL : /api/v1/conjunction
 */
@RestController
@RequestMapping("/api/v1/conjunction")
@Validated
public class ConjunctionController {

    private static final Logger log = LoggerFactory.getLogger(ConjunctionController.class);

    private final ConjunctionService         conjunctionService;
    private final ConjunctionAlertRepository repository;
    private final TleService                 tleService;

    public ConjunctionController(ConjunctionService conjunctionService,
                                  ConjunctionAlertRepository repository,
                                  TleService tleService) {
        this.conjunctionService = conjunctionService;
        this.repository         = repository;
        this.tleService         = tleService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Analyse on-demand
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Analyse on-demand à partir de TLEs fournis directement.
     * Utile pour le debug et les tests frontend.
     *
     * POST /api/v1/conjunction/analyze
     */
    @PostMapping("/analyze")
    public ResponseEntity<ConjunctionReport> analyze(@RequestBody @Valid ConjunctionRequestBody body) {
        ConjunctionRequest req = new ConjunctionRequest(
                body.nameSat1(), body.tle1Sat1(), body.tle2Sat1(),
                body.nameSat2(), body.tle1Sat2(), body.tle2Sat2(),
                body.durationHours(), body.stepSeconds(), body.thresholdKm()
        );
        log.info("[ConjunctionController] analyze on-demand : {} ↔ {}", body.nameSat1(), body.nameSat2());
        return ResponseEntity.ok(conjunctionService.analyze(req));
    }

    /**
     * Analyse on-demand par nom de satellite (résolu depuis le catalogue en mémoire).
     *
     * POST /api/v1/conjunction/analyze-by-name
     */
    @PostMapping("/analyze-by-name")
    public ResponseEntity<ConjunctionReport> analyzeByName(
            @RequestBody @Valid AnalyzeByNameBody body) {

        TleEntry sat1 = tleService.resolveUniqueTle(body.nameSat1());
        TleEntry sat2 = tleService.resolveUniqueTle(body.nameSat2());

        ConjunctionRequest req = new ConjunctionRequest(
                sat1.name(), sat1.line1(), sat1.line2(),
                sat2.name(), sat2.line1(), sat2.line2(),
                body.durationHours(), body.stepSeconds(), body.thresholdKm()
        );
        log.info("[ConjunctionController] analyze-by-name : {} ↔ {}", sat1.name(), sat2.name());
        return ResponseEntity.ok(conjunctionService.analyze(req));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gestion des alertes persistées
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Liste paginée des alertes avec filtres optionnels, triée par TCA décroissant.
     *
     * <p>GET /api/v1/conjunction/alerts?page=0&size=20&sat=ISS&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z&maxKm=5
     *
     * @param page   numéro de page (défaut : 0)
     * @param size   taille de page (défaut : 20)
     * @param sat    fragment de nom de satellite (filtre LIKE insensible à la casse)
     * @param from   borne inférieure du TCA (ISO-8601, optionnel)
     * @param to     borne supérieure du TCA (ISO-8601, optionnel)
     * @param maxKm  distance maximale en km (optionnel)
     */
    @GetMapping("/alerts")
    public ResponseEntity<Page<ConjunctionAlert>> getAlerts(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String sat,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false)    Instant to,
            @RequestParam(required = false)    Double maxKm) {

        Specification<ConjunctionAlert> spec = ConjunctionSpecification.build(sat, from, to, maxKm);
        Page<ConjunctionAlert> result = repository.findAll(
                spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "tca")));
        return ResponseEntity.ok(result);
    }

    /**
     * Alertes non acquittées, pour le badge de notification IHM.
     *
     * GET /api/v1/conjunction/alerts/unread
     */
    @GetMapping("/alerts/unread")
    public ResponseEntity<List<ConjunctionAlert>> getUnreadAlerts() {
        return ResponseEntity.ok(repository.findByAcknowledgedFalseOrderByTcaAsc());
    }

    /**
     * Acquitte une alerte (la retire du badge IHM).
     *
     * PUT /api/v1/conjunction/alerts/{id}/ack
     */
    @PutMapping("/alerts/{id}/ack")
    public ResponseEntity<Void> acknowledge(@PathVariable Long id) {
        ConjunctionAlert alert = repository.findById(id)
                .orElseThrow(() -> new AlertNotFoundException("Alerte introuvable : " + id));
        alert.acknowledge();
        repository.save(alert);
        log.info("[ConjunctionController] Alerte {} acquittée.", id);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DTOs internes (body des requêtes)
    // ─────────────────────────────────────────────────────────────────────────

    public record ConjunctionRequestBody(
            @NotBlank String nameSat1,
            @NotBlank String tle1Sat1,
            @NotBlank String tle2Sat1,
            @NotBlank String nameSat2,
            @NotBlank String tle1Sat2,
            @NotBlank String tle2Sat2,
            @DecimalMin("1.0") @DecimalMax("72.0") double durationHours,
            @Min(10) @Max(300) int stepSeconds,
            @DecimalMin("0.1") @DecimalMax("200.0") double thresholdKm
    ) {
        public ConjunctionRequestBody {
            if (durationHours == 0) durationHours = 24.0;
            if (stepSeconds   == 0) stepSeconds   = 60;
            if (thresholdKm   == 0) thresholdKm   = 5.0;
        }
    }

    public record AnalyzeByNameBody(
            @NotBlank String nameSat1,
            @NotBlank String nameSat2,
            double durationHours,
            int    stepSeconds,
            double thresholdKm
    ) {
        public AnalyzeByNameBody {
            if (durationHours == 0) durationHours = 24.0;
            if (stepSeconds   == 0) stepSeconds   = 60;
            if (thresholdKm   == 0) thresholdKm   = 5.0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exception 404 alerte
    // ─────────────────────────────────────────────────────────────────────────

    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.NOT_FOUND)
    public static class AlertNotFoundException extends RuntimeException {
        public AlertNotFoundException(String msg) { super(msg); }
    }
}

