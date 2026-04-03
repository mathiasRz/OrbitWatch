package projet.OrbitWatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import projet.OrbitWatch.dto.ConjunctionEvent;
import projet.OrbitWatch.dto.ConjunctionReport;
import projet.OrbitWatch.dto.ConjunctionRequest;
import projet.OrbitWatch.dto.SatellitePosition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Service de détection de rapprochements critiques (conjunctions) entre deux satellites.
 *
 * <p>Algorithme :
 * <ol>
 *   <li>Propagation SGP4 des deux satellites sur la fenêtre temporelle (via {@link PropagationService})</li>
 *   <li>Calcul de la distance euclidienne ECI à chaque pas de temps</li>
 *   <li>Détection des minima locaux sous le seuil : {@code d[i-1] > d[i] < d[i+1] && d[i] < threshold}</li>
 *   <li>Raffinement du TCA par interpolation parabolique sur 3 points</li>
 * </ol>
 *
 * <p>Ce service est <b>sans état et sans dépendance JPA</b> : il peut être appelé
 * aussi bien par le {@code ConjunctionScanJob} que par le {@code ConjunctionController}.
 */
@Service
public class ConjunctionService {

    private static final Logger log = LoggerFactory.getLogger(ConjunctionService.class);

    private final PropagationService propagationService;

    public ConjunctionService(PropagationService propagationService) {
        this.propagationService = propagationService;
    }

    /**
     * Analyse une fenêtre temporelle et retourne tous les rapprochements critiques détectés.
     *
     * @param req Paramètres de la requête (TLEs, fenêtre, pas, seuil)
     * @return {@link ConjunctionReport} avec la liste des {@link ConjunctionEvent} triés par distance croissante
     */
    public ConjunctionReport analyze(ConjunctionRequest req) {
        Instant windowStart = Instant.now();
        Instant windowEnd   = windowStart.plusSeconds((long) (req.durationHours() * 3600));

        log.info("[ConjunctionService] Analyse {} ↔ {} sur {}h (pas {}s, seuil {}km)",
                req.nameSat1(), req.nameSat2(),
                req.durationHours(), req.stepSeconds(), req.thresholdKm());

        // ── 1. Propagation des deux satellites sur toute la fenêtre ───────────
        List<SatellitePosition> track1 = propagationService.groundTrack(
                req.tle1Sat1(), req.tle2Sat1(), req.nameSat1(),
                windowStart, (int) (req.durationHours() * 60), req.stepSeconds());
        List<SatellitePosition> track2 = propagationService.groundTrack(
                req.tle1Sat2(), req.tle2Sat2(), req.nameSat2(),
                windowStart, (int) (req.durationHours() * 60), req.stepSeconds());

        int size = Math.min(track1.size(), track2.size());
        if (size < 3) {
            log.warn("[ConjunctionService] Fenêtre trop courte pour l'analyse ({} points)", size);
            return new ConjunctionReport(req.nameSat1(), req.nameSat2(),
                    req.thresholdKm(), windowStart, windowEnd, List.of());
        }

        // ── 2. Calcul des distances ECI à chaque instant ──────────────────────
        double[] distances = new double[size];
        for (int i = 0; i < size; i++) {
            distances[i] = eciDistance(track1.get(i), track2.get(i));
        }

        // ── 3. Détection des minima locaux sous le seuil ──────────────────────
        List<ConjunctionEvent> events = new ArrayList<>();
        for (int i = 1; i < size - 1; i++) {
            double dPrev = distances[i - 1];
            double dCurr = distances[i];
            double dNext = distances[i + 1];

            if (dCurr < dPrev && dCurr < dNext && dCurr < req.thresholdKm()) {
                // ── 4. Raffinement du TCA par interpolation parabolique ────────
                Instant tca        = refineTca(track1.get(i - 1).epoch(), req.stepSeconds(), dPrev, dCurr, dNext);
                double  distanceTca = dCurr; // valeur au minimum discret (proche du vrai minimum)

                // Position des deux satellites à l'instant discret le plus proche du TCA
                SatellitePosition pos1 = track1.get(i);
                SatellitePosition pos2 = track2.get(i);

                events.add(new ConjunctionEvent(tca, distanceTca, pos1, pos2));

                log.warn("[ConjunctionService] ⚠ Rapprochement détecté : {} ↔ {} — {} km au {}",
                        req.nameSat1(), req.nameSat2(),
                        String.format("%.3f", distanceTca), tca);
            }
        }

        // ── 5. Tri par distance croissante ────────────────────────────────────
        events.sort(Comparator.comparingDouble(ConjunctionEvent::distanceKm));

        log.info("[ConjunctionService] Analyse terminée — {} rapprochement(s) détecté(s)", events.size());
        return new ConjunctionReport(req.nameSat1(), req.nameSat2(),
                req.thresholdKm(), windowStart, windowEnd, events);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Méthodes utilitaires
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Distance euclidienne entre deux positions en repère ECI (km).
     */
    static double eciDistance(SatellitePosition a, SatellitePosition b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Raffine le TCA par interpolation parabolique sur 3 points consécutifs.
     *
     * <p>Étant donné trois distances {@code d0, d1, d2} aux instants
     * {@code t-step, t, t+step}, le minimum parabolique est à :
     * {@code offset = step * (d0 - d2) / (2 * (d0 - 2*d1 + d2))}
     *
     * @param tPrev      Instant du point précédent (i-1)
     * @param stepSeconds Pas de temps en secondes
     * @param d0         Distance au point i-1
     * @param d1         Distance au point i (minimum local)
     * @param d2         Distance au point i+1
     * @return Instant raffiné du TCA
     */
    static Instant refineTca(Instant tPrev, int stepSeconds, double d0, double d1, double d2) {
        double denom = d0 - 2 * d1 + d2;
        if (Math.abs(denom) < 1e-12) {
            // Dénominateur quasi-nul : parabole plate → TCA = point central
            return tPrev.plusSeconds(stepSeconds);
        }
        double offsetSeconds = stepSeconds * (d0 - d2) / (2.0 * denom);
        // Clamp dans [0, 2*step] pour rester dans la fenêtre des 3 points
        offsetSeconds = Math.max(0, Math.min(2.0 * stepSeconds, offsetSeconds));
        return tPrev.plusMillis((long) (offsetSeconds * 1000));
    }
}



