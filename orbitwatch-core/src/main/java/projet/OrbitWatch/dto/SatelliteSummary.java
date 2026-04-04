package projet.OrbitWatch.dto;

import projet.OrbitWatch.model.ConjunctionAlert;

import java.time.Instant;
import java.util.List;

/**
 * DTO agrégé représentant le profil complet d'un satellite.
 *
 * <p>Ce record est conçu pour être exposé via {@code GET /api/v1/satellite/{noradId}/summary}
 * et consommé par la page "Profil satellite" Angular.
 *
 *
 * @param noradId            Identifiant NORAD stable
 * @param name               Nom du satellite
 * @param tleLine1           Première ligne TLE courante (depuis le catalogue en mémoire)
 * @param tleLine2           Deuxième ligne TLE courante
 * @param tleEpoch           Époque du TLE courant
 * @param tleAgeHours        Âge du TLE en heures (depuis {@code tleEpoch} jusqu'à maintenant)
 * @param latestElements     Derniers paramètres orbitaux persistés en base
 * @param recentConjunctions Conjonctions récentes impliquant ce satellite
 * @param textSummary        Résumé textuel en langage naturel — prerequis RAG M5
 */
public record SatelliteSummary(
        int                  noradId,
        String               name,
        String               tleLine1,
        String               tleLine2,
        Instant              tleEpoch,
        double               tleAgeHours,
        OrbitalElements      latestElements,
        List<ConjunctionAlert> recentConjunctions,
        String               textSummary
) {}

