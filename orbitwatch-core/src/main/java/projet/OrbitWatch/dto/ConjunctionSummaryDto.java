package projet.OrbitWatch.dto;

public record ConjunctionSummaryDto(String sat1, String sat2, int eventCount,
                                    Double minDistanceKm, String error) {}

