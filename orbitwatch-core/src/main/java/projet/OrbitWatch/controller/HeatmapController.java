package projet.OrbitWatch.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import projet.OrbitWatch.dto.HeatmapCell;
import projet.OrbitWatch.dto.OrbitalElements;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.service.OrbitalElementsExtractor;
import projet.OrbitWatch.service.TleService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Contrôleur REST exposant la heatmap de densité orbitale.
 * GET /api/v1/orbit/heatmap?catalog=debris&altMin=0&altMax=2000
 */
@RestController
@RequestMapping("/api/v1/orbit")
public class HeatmapController {

    private static final Logger log = LoggerFactory.getLogger(HeatmapController.class);

    private final TleService               tleService;
    private final OrbitalElementsExtractor extractor;

    public HeatmapController(TleService tleService, OrbitalElementsExtractor extractor) {
        this.tleService = tleService;
        this.extractor  = extractor;
    }

    /**
     * Retourne la heatmap de densité orbitale pour un catalogue donné.
     *
     * @param catalog Nom du catalogue (défaut : "debris")
     * @param altMin  Altitude périgée minimale en km (défaut : 0)
     * @param altMax  Altitude périgée maximale en km (défaut : 2000)
     * @return liste de cellules triées par count décroissant
     */
    @GetMapping("/heatmap")
    public ResponseEntity<List<HeatmapCell>> getHeatmap(
            @RequestParam(defaultValue = "debris") String catalog,
            @RequestParam(defaultValue = "0")      double altMin,
            @RequestParam(defaultValue = "2000")   double altMax) {

        List<TleEntry> entries = tleService.findByCatalog(catalog);
        if (entries.isEmpty()) {
            log.debug("[HeatmapController] Catalogue '{}' inconnu ou vide.", catalog);
            return ResponseEntity.ok(List.of());
        }

        // ── Structure de stockage ─────────────────────────────────────────────
        // Clé = "latBand:altBand" (ex : "80:500")
        // Valeur = int[3] : [0]=count, [1]=latBand, [2]=altBand
        //
        // Nombre de cases théoriques :
        //   - Inclinaisons : de 0° à 180° par pas de 5°  → 37 bandes de latitude
        //     (0°=équatorial, 90°=polaire, 98°=héliosynchrone, 180°=rétrograde)
        //   - Altitudes     : de altMin à altMax par pas de 50 km
        //     ex : 0→2000 km → 40 bandes d'altitude
        //   → Maximum théorique : 37 × 40 = 1 480 cellules
        //   En pratique, le catalogue debris CelesTrak (~2 000 objets) produit
        //   ~50–150 cellules non vides, concentrées sur quelques inclinaisons clés :
        //     • ~65–74°  : débris Cosmos (collisions historiques)
        //     • ~82–86°  : orbites quasi-polaires soviétiques
        //     • ~97–99°  : orbites héliosynchrones (météo, observation)
        Map<String, int[]> cellCounts = new LinkedHashMap<>();
        int skipped = 0;

        for (TleEntry entry : entries) {
            try {
                OrbitalElements el = extractor.extract(entry.name(), entry.line1(), entry.line2());
                double altPeri = el.altitudePerigeeKm();
                if (altPeri < altMin || altPeri > altMax) continue;

                // ── Calcul de la cellule ──────────────────────────────────────
                // latBand : latitude maximale atteinte par l'objet
                //
                // Pour les orbites DIRECTES  (inclinaison ≤ 90°) :
                //   lat max = inclinaison
                //   ex : 74.0° → latBand = 75°
                //
                // Pour les orbites RÉTROGRADES (inclinaison > 90°) :
                //   lat max = 180° - inclinaison
                //   ex : Fengyun à 98.6° → 180 - 98.6 = 81.4° → latBand = 80°
                //   (un satellite rétrograde à 98° couvre les latitudes -82°/+82°,
                //    pas -100°/+100° qui n'existe pas sur Terre)
                //
                // Cap à 85° : limite de la projection Web Mercator de Leaflet
                double incl = el.inclinationDeg();
                double effectiveInclination = incl > 90.0 ? 180.0 - incl : incl;
                double latBand = Math.min(Math.round(effectiveInclination / 5.0) * 5.0, 85.0);

                // altBand : altitude périgée arrondie à 50 km
                //   ex : 487 km → round(487/50)*50 = round(9.74)*50 = 10*50 = 500 km
                //   Représente le point le plus bas de l'orbite elliptique,
                //   donc la zone où la densité atmosphérique est la plus forte
                //   (et le risque de collision le plus élevé).
                double altBand = Math.round(altPeri / 50.0) * 50.0;

                String key = latBand + ":" + altBand;
                cellCounts.computeIfAbsent(key, k -> new int[]{0, (int) latBand, (int) altBand})[0]++;
            } catch (Exception ex) {
                skipped++;
                log.debug("[HeatmapController] Skip '{}' : {}", entry.name(), ex.getMessage());
            }
        }

        if (skipped > 0) {
            log.warn("[HeatmapController] {} TLE(s) ignore(s) sur {}", skipped, entries.size());
        }

        List<HeatmapCell> result = cellCounts.values().stream()
                .map(v -> new HeatmapCell(v[1], v[2], v[0]))
                .sorted(Comparator.comparingInt(HeatmapCell::count).reversed())
                .collect(Collectors.toList());

        log.info("[HeatmapController] Catalogue '{}' -> {} cellule(s), altMin={}, altMax={}",
                catalog, result.size(), altMin, altMax);

        return ResponseEntity.ok(result);
    }
}

