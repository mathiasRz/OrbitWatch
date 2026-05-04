package projet.OrbitWatch.dto;

public record AnomalyAlertDto(String satelliteName, String type, String severity,
                              String detectedAt, String description) {}

