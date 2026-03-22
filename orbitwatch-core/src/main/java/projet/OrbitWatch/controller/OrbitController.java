package projet.OrbitWatch.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import projet.OrbitWatch.dto.SatellitePosition;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.service.PropagationService;
import projet.OrbitWatch.service.TleService;

import java.util.List;

/**
 * Contrôleur REST exposant les fonctionnalités de propagation orbitale.
 * Base URL : /api/v1/orbit
 *
 * <p>Le satellite est résolu par son nom depuis le catalogue CelesTrak
 * maintenu en mémoire par {@link TleService}.
 */
@RestController
@RequestMapping("/api/v1/orbit")
public class OrbitController {

    private static final Logger log = LoggerFactory.getLogger(OrbitController.class);

    private final PropagationService propagationService;
    private final TleService tleService;

    public OrbitController(PropagationService propagationService, TleService tleService) {
        this.propagationService = propagationService;
        this.tleService = tleService;
    }

    /**
     * Retourne la position instantanée d'un satellite identifié par son nom.
     *
     * <p>GET /api/v1/orbit/position?name=ISS&epoch=2026-03-15T12:00:00Z
     *
     * @param name  Nom du satellite (correspondance partielle, insensible à la casse)
     * @param epoch Instant UTC cible ISO-8601 (optionnel — défaut : époque du TLE)
     */
    @GetMapping("/position")
    public ResponseEntity<SatellitePosition> getPosition(
            @RequestParam String name,
            @RequestParam(required = false) String epoch) {

        TleEntry tle = tleService.resolveUniqueTle(name);
        return ResponseEntity.ok(
                propagationService.propagate(tle.line1(), tle.line2(), tle.name(), tleService.parseEpoch(epoch)));
    }

    /**
     * Retourne le ground track d'un satellite identifié par son nom.
     *
     * <p>GET /api/v1/orbit/groundtrack?name=ISS&duration=90&step=60
     *
     * @param name     Nom du satellite (correspondance partielle, insensible à la casse)
     * @param epoch    Instant de début ISO-8601 (optionnel — défaut : époque du TLE)
     * @param duration Durée en minutes (défaut : 90)
     * @param step     Pas en secondes (défaut : 60)
     */
    @GetMapping("/groundtrack")
    public ResponseEntity<List<SatellitePosition>> getGroundTrack(
            @RequestParam String name,
            @RequestParam(required = false) String epoch,
            @RequestParam(defaultValue = "90") int duration,
            @RequestParam(defaultValue = "60") int step) {

        TleEntry tle = tleService.resolveUniqueTle(name);
        return ResponseEntity.ok(
                propagationService.groundTrack(tle.line1(), tle.line2(), tle.name(), tleService.parseEpoch(epoch), duration, step));
    }

    /**
     * Retourne un snapshot de la position instantanée de tous les satellites
     * d'un catalogue chargé en mémoire.
     *
     * <p>GET /api/v1/orbit/positions?catalog=stations
     *
     * @param catalog Nom du catalogue (défaut : "stations")
     */
    @GetMapping("/positions")
    public ResponseEntity<List<SatellitePosition>> getPositions(
            @RequestParam(defaultValue = "stations") String catalog) {

        try {
            List<TleEntry> entries = tleService.findByCatalog(catalog);
            if (entries.isEmpty()) {
                log.warn("[OrbitController] getPositions — catalogue '{}' inconnu ou vide en mémoire.", catalog);
                return ResponseEntity.ok(List.of());
            }

            List<SatellitePosition> positions = propagationService.snapshotCatalog(entries);
            log.info("[OrbitController] getPositions — catalogue '{}' : {}/{} satellites propagés avec succès.",
                    catalog, positions.size(), entries.size());
            return ResponseEntity.ok(positions);

        } catch (Exception e) {
            log.error("[OrbitController] getPositions — erreur technique sur le catalogue '{}' : {}",
                    catalog, e.getMessage(), e);
            throw e;
        }
    }
}
