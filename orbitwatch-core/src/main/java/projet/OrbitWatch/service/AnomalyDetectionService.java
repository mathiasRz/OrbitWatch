package projet.OrbitWatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import projet.OrbitWatch.model.AnomalyAlert;
import projet.OrbitWatch.model.AnomalySeverity;
import projet.OrbitWatch.model.AnomalyType;
import projet.OrbitWatch.model.OrbitalHistory;
import projet.OrbitWatch.repository.OrbitalHistoryRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service de détection d'anomalies orbitales.
 *
 * <p><b>règles métier</b> : détection par seuils configurables sur les deltas
 * de paramètres Keplériens (altitude, inclinaison, RAAN, excentricité).
 * Valeur immédiate, zéro cold start, interprétable.
 *
 * <p><b>Z-score glissant</b> : détection statistique sur fenêtre mobile.
 * Skippé si l'historique est insuffisant ({@code anomaly.ml.min-history}).
 * Implémentation Java pure — pas de BLAS/LAPACK natif
 *
 * <p>Ce service est <b>sans état et sans persistance</b> : il produit des {@link AnomalyAlert}
 * transientes que {@code AnomalyScanJob} déduplique avant de persister.
 */
@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);

    // ── Seuils Phase 1 (configurables) ────────────────────────────────────────

    @Value("${anomaly.threshold.altitude-km:10.0}")
    private double thresholdAltitudeKm;

    @Value("${anomaly.threshold.inclination-deg:0.5}")
    private double thresholdInclinationDeg;

    @Value("${anomaly.threshold.raan-deg:1.0}")
    private double thresholdRaanDeg;

    @Value("${anomaly.threshold.eccentricity:0.001}")
    private double thresholdEccentricity;

    // ── Paramètres Phase 2 Z-score (configurables) ────────────────────────────

    @Value("${anomaly.ml.zscore-threshold:3.0}")
    private double zscoreThreshold;

    @Value("${anomaly.ml.min-history:30}")
    private int minHistory;

    /** Nombre de snapshots récents analysés par {@code detectRuleBased}. */
    private static final int RULE_WINDOW = 2;

    private final OrbitalHistoryRepository orbitalHistoryRepository;

    public AnomalyDetectionService(OrbitalHistoryRepository orbitalHistoryRepository) {
        this.orbitalHistoryRepository = orbitalHistoryRepository;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Phase 1 — règles métier
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Détecte les anomalies par seuils configurables sur les 2 derniers snapshots.
     *
     * @param noradId identifiant NORAD du satellite
     * @return liste des {@link AnomalyAlert} détectées (non persistées)
     */
    public List<AnomalyAlert> detectRuleBased(int noradId) {
        List<OrbitalHistory> snapshots = orbitalHistoryRepository
                .findByNoradIdOrderByFetchedAtDesc(noradId, PageRequest.of(0, RULE_WINDOW));

        if (snapshots.size() < RULE_WINDOW) {
            log.debug("[AnomalyDetection] NORAD {} — historique insuffisant pour règles métier ({} points)",
                    noradId, snapshots.size());
            return List.of();
        }

        // snapshots[0] = plus récent, snapshots[1] = précédent
        OrbitalHistory recent = snapshots.get(0);
        OrbitalHistory prev   = snapshots.get(1);
        String satName        = recent.getSatelliteName();
        Instant detectedAt    = Instant.now();

        List<AnomalyAlert> alerts = new ArrayList<>();

        // ── Altitude périgée ──────────────────────────────────────────────────
        double deltaPerigee = Math.abs(recent.getAltitudePerigeeKm() - prev.getAltitudePerigeeKm());
        if (deltaPerigee > thresholdAltitudeKm) {
            double delta = recent.getAltitudePerigeeKm() - prev.getAltitudePerigeeKm();
            alerts.add(new AnomalyAlert(
                    noradId, satName, detectedAt,
                    AnomalyType.ALTITUDE_CHANGE,
                    severity(deltaPerigee, thresholdAltitudeKm),
                    "Perigee altitude variation of %+.1f km detected (%.1f → %.1f km)"
                            .formatted(delta, prev.getAltitudePerigeeKm(), recent.getAltitudePerigeeKm())
            ));
        }

        // ── Altitude apogée ───────────────────────────────────────────────────
        double deltaApogee = Math.abs(recent.getAltitudeApogeeKm() - prev.getAltitudeApogeeKm());
        if (deltaApogee > thresholdAltitudeKm) {
            double delta = recent.getAltitudeApogeeKm() - prev.getAltitudeApogeeKm();
            alerts.add(new AnomalyAlert(
                    noradId, satName, detectedAt,
                    AnomalyType.ALTITUDE_CHANGE,
                    severity(deltaApogee, thresholdAltitudeKm),
                    "Apogee altitude variation of %+.1f km detected (%.1f → %.1f km)"
                            .formatted(delta, prev.getAltitudeApogeeKm(), recent.getAltitudeApogeeKm())
            ));
        }

        // ── Inclinaison ───────────────────────────────────────────────────────
        double deltaInc = Math.abs(recent.getInclinationDeg() - prev.getInclinationDeg());
        if (deltaInc > thresholdInclinationDeg) {
            alerts.add(new AnomalyAlert(
                    noradId, satName, detectedAt,
                    AnomalyType.INCLINATION_CHANGE,
                    severity(deltaInc, thresholdInclinationDeg),
                    "Inclination variation of %+.3f° detected (%.3f → %.3f°)"
                            .formatted(recent.getInclinationDeg() - prev.getInclinationDeg(),
                                    prev.getInclinationDeg(), recent.getInclinationDeg())
            ));
        }

        // ── RAAN ──────────────────────────────────────────────────────────────
        double deltaRaan = Math.abs(recent.getRaanDeg() - prev.getRaanDeg());
        // Gérer le saut circulaire 360°→0°
        if (deltaRaan > 180.0) deltaRaan = 360.0 - deltaRaan;

        // Soustraire la précession nodale naturelle J2 pour éviter les faux positifs.
        // Formule approchée : dΩ/dt ≈ -2.0663e10 * cos(i) / (a^3.5 * (1-e²)²) [°/jour]
        // où a est en km. Ramené sur l'intervalle de temps réel entre les deux snapshots.
        double naturalDriftDeg = 0.0;
        try {
            double incRad  = Math.toRadians(recent.getInclinationDeg());
            double a       = recent.getSemiMajorAxisKm(); // km
            double e       = recent.getEccentricity();
            double driftPerDay = -2.0663e10 * Math.cos(incRad)
                    / (Math.pow(a, 3.5) * Math.pow(1 - e * e, 2.0));
            // Temps entre les deux snapshots en jours
            double dtDays = java.time.Duration.between(
                    prev.getFetchedAt(), recent.getFetchedAt()).toSeconds() / 86400.0;
            naturalDriftDeg = Math.abs(driftPerDay * dtDays);
        } catch (Exception ignored) {
            // Si le calcul échoue, on conserve deltaRaan brut
        }

        double anomalousDrift = Math.max(0.0, deltaRaan - naturalDriftDeg);
        if (anomalousDrift > thresholdRaanDeg) {
            alerts.add(new AnomalyAlert(
                    noradId, satName, detectedAt,
                    AnomalyType.RAAN_DRIFT,
                    severity(anomalousDrift, thresholdRaanDeg),
                    "RAAN anomalous drift of %.3f° detected (total: %.3f°, natural: %.3f°) (%.3f → %.3f°)"
                            .formatted(anomalousDrift, deltaRaan, naturalDriftDeg,
                                    prev.getRaanDeg(), recent.getRaanDeg())
            ));
        }

        // ── Excentricité ──────────────────────────────────────────────────────
        double deltaEcc = Math.abs(recent.getEccentricity() - prev.getEccentricity());
        if (deltaEcc > thresholdEccentricity) {
            alerts.add(new AnomalyAlert(
                    noradId, satName, detectedAt,
                    AnomalyType.ECCENTRICITY_CHANGE,
                    severity(deltaEcc, thresholdEccentricity),
                    "Eccentricity variation of %+.6f detected (%.6f → %.6f)"
                            .formatted(recent.getEccentricity() - prev.getEccentricity(),
                                    prev.getEccentricity(), recent.getEccentricity())
            ));
        }

        if (!alerts.isEmpty()) {
            log.warn("[AnomalyDetection] NORAD {} ({}) — {} anomalie(s) détectée(s) par règles métier",
                    noradId, satName, alerts.size());
        }

        return alerts;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Phase 2 — Z-score glissant (Java pur)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Détecte les anomalies statistiques par Z-score glissant sur une fenêtre mobile.
     *
     * <p>Skippé si l'historique est insuffisant ({@code anomaly.ml.min-history} défaut : 30).
     *
     * @param noradId    identifiant NORAD du satellite
     * @param windowSize taille de la fenêtre glissante (défaut : 30)
     * @return liste des {@link AnomalyAlert} de type {@link AnomalyType#STATISTICAL}
     */
    public List<AnomalyAlert> detectZScore(int noradId, int windowSize) {
        long count = orbitalHistoryRepository.countByNoradId(noradId);
        if (count < minHistory) {
            log.info("[AnomalyDetection] NORAD {} — historique insuffisant pour Z-score ({}/{} points)",
                    noradId, count, minHistory);
            return List.of();
        }

        List<OrbitalHistory> snapshots = orbitalHistoryRepository
                .findByNoradIdOrderByFetchedAtDesc(noradId, PageRequest.of(0, windowSize));

        if (snapshots.size() < 3) {
            return List.of();
        }

        // Analyse sur l'altitude périgée (paramètre le plus sensible aux manœuvres)
        double[] values = snapshots.stream()
                .mapToDouble(OrbitalHistory::getAltitudePerigeeKm)
                .toArray();

        double mean   = mean(values);
        double stddev = stddev(values, mean);

        if (stddev < 1e-10) {
            // Série constante — Z-score non défini
            return List.of();
        }

        List<AnomalyAlert> alerts = new ArrayList<>();
        // On analyse uniquement le point le plus récent (index 0)
        OrbitalHistory latest = snapshots.get(0);
        double zscore = Math.abs((values[0] - mean) / stddev);

        if (zscore > zscoreThreshold) {
            log.warn("[AnomalyDetection] NORAD {} ({}) — Z-score altitude = {} > seuil {}",
                    noradId, latest.getSatelliteName(),
                    String.format("%.2f", zscore), String.format("%.2f", zscoreThreshold));
            alerts.add(new AnomalyAlert(
                    noradId,
                    latest.getSatelliteName(),
                    Instant.now(),
                    AnomalyType.STATISTICAL,
                    zscore > zscoreThreshold * 1.5 ? AnomalySeverity.HIGH : AnomalySeverity.MEDIUM,
                    "Statistical anomaly on perigee altitude: Z-score=%.2f (threshold=%.1f), value=%.1f km, mean=%.1f km"
                            .formatted(zscore, zscoreThreshold, values[0], mean)
            ));
        }

        return alerts;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Utilitaires statiques (package-visible pour les tests)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Calcule la moyenne d'un tableau de doubles.
     */
    static double mean(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    /**
     * Calcule l'écart-type (population) d'un tableau de doubles.
     */
    static double stddev(double[] values, double mean) {
        double sumSq = 0;
        for (double v : values) sumSq += (v - mean) * (v - mean);
        return Math.sqrt(sumSq / values.length);
    }

    /**
     * Détermine la sévérité en fonction du ratio delta/seuil.
     * <ul>
     *   <li>{@code ratio < 2} → {@link AnomalySeverity#LOW}</li>
     *   <li>{@code ratio < 5} → {@link AnomalySeverity#MEDIUM}</li>
     *   <li>{@code ratio ≥ 5} → {@link AnomalySeverity#HIGH}</li>
     * </ul>
     */
    static AnomalySeverity severity(double delta, double threshold) {
        double ratio = delta / threshold;
        if (ratio < 2.0) return AnomalySeverity.LOW;
        if (ratio < 5.0) return AnomalySeverity.MEDIUM;
        return AnomalySeverity.HIGH;
    }
}

