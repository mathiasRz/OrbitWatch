package projet.OrbitWatch.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import projet.OrbitWatch.model.AnomalyAlert;
import projet.OrbitWatch.model.AnomalySeverity;
import projet.OrbitWatch.model.AnomalyType;
import projet.OrbitWatch.repository.AnomalyAlertRepository;
import projet.OrbitWatch.repository.AnomalySpecification;

import java.time.Instant;
import java.util.List;

/**
 * Contrôleur REST exposant les alertes d'anomalies orbitales.
 *
 * <p>Base URL : {@code /api/v1/anomaly}
 * <ul>
 *   <li>{@code GET  /alerts}        — liste paginée avec filtres optionnels</li>
 *   <li>{@code GET  /alerts/unread} — alertes non acquittées (badge IHM)</li>
 *   <li>{@code PUT  /alerts/{id}/ack} — acquittement</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/anomaly")
public class AnomalyController {

    private static final Logger log = LoggerFactory.getLogger(AnomalyController.class);

    private final AnomalyAlertRepository repository;

    public AnomalyController(AnomalyAlertRepository repository) {
        this.repository = repository;
    }

    // ── GET /alerts ───────────────────────────────────────────────────────────

    /**
     * Liste paginée des alertes avec filtres optionnels.
     *
     * <p>GET /api/v1/anomaly/alerts?page=0&size=20&noradId=25544&type=ALTITUDE_CHANGE&severity=HIGH&from=...&to=...
     */
    @GetMapping("/alerts")
    public ResponseEntity<Page<AnomalyAlert>> getAlerts(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    Integer noradId,
            @RequestParam(required = false)    AnomalyType type,
            @RequestParam(required = false)    AnomalySeverity severity,
            @RequestParam(required = false)    Instant from,
            @RequestParam(required = false)    Instant to) {

        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "detectedAt"));
        var spec     = AnomalySpecification.build(noradId, type, severity, from, to);
        return ResponseEntity.ok(repository.findAll(spec, pageable));
    }

    // ── GET /alerts/unread ────────────────────────────────────────────────────

    /** Alertes non acquittées — badge IHM. */
    @GetMapping("/alerts/unread")
    public ResponseEntity<List<AnomalyAlert>> getUnreadAlerts() {
        return ResponseEntity.ok(repository.findByAcknowledgedFalseOrderByDetectedAtDesc());
    }

    // ── PUT /alerts/{id}/ack ──────────────────────────────────────────────────

    /** Acquitte une alerte → 204 / 404. */
    @PutMapping("/alerts/{id}/ack")
    public ResponseEntity<Void> acknowledge(@PathVariable Long id) {
        AnomalyAlert alert = repository.findById(id)
                .orElseThrow(() -> new AnomalyAlertNotFoundException("Alerte anomalie introuvable : " + id));
        alert.acknowledge();
        repository.save(alert);
        log.info("[AnomalyController] Alerte anomalie {} acquittée.", id);
        return ResponseEntity.noContent().build();
    }

    // ── Exception 404 ─────────────────────────────────────────────────────────

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public static class AnomalyAlertNotFoundException extends RuntimeException {
        public AnomalyAlertNotFoundException(String msg) { super(msg); }
    }
}

