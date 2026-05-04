package projet.OrbitWatch.dto;

public record OrbitalHistoryDto(String fetchedAt, double altPerigeeKm, double altApogeeKm,
                                double inclinationDeg, double eccentricity) {}

