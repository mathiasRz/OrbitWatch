package projet.OrbitWatch.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import projet.OrbitWatch.service.TleService;

import java.util.List;
import java.util.Map;

/**
 * Contrôleur utilitaire exposant l'état du catalogue TLE en mémoire.
 * Base URL : /api/v1/tle
 *
 * <p>La résolution par nom et la propagation sont gérées par {@link OrbitController}.
 */
@RestController
@RequestMapping("/api/v1/tle")
public class TleController {

    private final TleService tleService;

    public TleController(TleService tleService) {
        this.tleService = tleService;
    }

    /**
     * Retourne la liste triée des noms de tous les satellites en mémoire.
     * Utilisé par le front pour alimenter le sélecteur de satellite.
     *
     * <p>GET /api/v1/tle/names
     */
    @GetMapping("/names")
    public ResponseEntity<List<String>> getNames() {
        List<String> names = tleService.findAll().stream()
                .map(e -> e.name())
                .distinct()
                .sorted()
                .toList();
        return ResponseEntity.ok(names);
    }

    /**
     * Retourne l'état du catalogue TLE : catalogues chargés et nombre total de TLE.
     *
     * <p>GET /api/v1/tle/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = Map.of(
                "catalogs", tleService.getCatalogNames(),
                "totalTle",  tleService.countAll()
        );
        return ResponseEntity.ok(status);
    }
}

