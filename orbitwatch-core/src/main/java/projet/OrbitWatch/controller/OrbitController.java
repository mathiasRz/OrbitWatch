package projet.OrbitWatch.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;
import projet.OrbitWatch.dto.SatellitePosition;
import projet.OrbitWatch.service.PropagationService;

import java.time.Instant;
import java.util.List;

/**
 * Contrôleur REST exposant les fonctionnalités de propagation orbitale.
 * Base URL : /api/v1/orbit
 */
@RestController
@RequestMapping("/api/v1/orbit")
public class OrbitController {

    private final PropagationService propagationService;

    public OrbitController(PropagationService propagationService) {
        this.propagationService = propagationService;
    }

    /**
     * Retourne la position instantanée d'un satellite.
     *
     * <p>Exemple :
     * <pre>GET /api/v1/orbit/position
     *   ?tle1=1 25544U 98067A   ...
     *   &tle2=2 25544  51.6400 ...
     *   &name=ISS
     *   &epoch=2026-03-07T12:00:00Z   (optionnel — défaut : époque du TLE)
     * </pre>
     *
     * @param tle1  Ligne 1 du TLE (URL-encodée)
     * @param tle2  Ligne 2 du TLE (URL-encodée)
     * @param name  Nom du satellite (optionnel)
     * @param epoch Instant UTC cible ISO-8601 (optionnel)
     * @return {@link SatellitePosition} sérialisé en JSON
     */
    @GetMapping("/position")
    public ResponseEntity<SatellitePosition> getPosition(
            @RequestParam String tle1,
            @RequestParam String tle2,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String epoch) {

        Instant targetEpoch = (epoch != null && !epoch.isBlank())
                ? Instant.parse(epoch)
                : null;

        SatellitePosition position = propagationService.propagate(tle1, tle2, name, targetEpoch);
        return ResponseEntity.ok(position);
    }

    /**
     * Retourne le ground track d'un satellite sur une durée donnée.
     *
     * <p>Exemple :
     * <pre>GET /api/v1/orbit/groundtrack
     *   ?tle1=1 25544U ...
     *   &tle2=2 25544 ...
     *   &name=ISS
     *   &duration=90      (minutes, défaut : 90)
     *   &step=60          (secondes, défaut : 60)
     *   &epoch=2026-03-07T12:00:00Z  (optionnel)
     * </pre>
     *
     * @param tle1     Ligne 1 du TLE
     * @param tle2     Ligne 2 du TLE
     * @param name     Nom du satellite (optionnel)
     * @param epoch    Instant de début ISO-8601 (optionnel — défaut : époque du TLE)
     * @param duration Durée en minutes (défaut : 90)
     * @param step     Pas en secondes (défaut : 60)
     * @return Liste de {@link SatellitePosition} sérialisée en JSON
     */
    @GetMapping("/groundtrack")
    public ResponseEntity<List<SatellitePosition>> getGroundTrack(
            @RequestParam String tle1,
            @RequestParam String tle2,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String epoch,
            @RequestParam(defaultValue = "90") int duration,
            @RequestParam(defaultValue = "60") int step) {

        Instant startEpoch = (epoch != null && !epoch.isBlank())
                ? Instant.parse(epoch)
                : null;

        List<SatellitePosition> track = propagationService.groundTrack(
                tle1, tle2, name, startEpoch, duration, step);
        return ResponseEntity.ok(track);
    }
}




