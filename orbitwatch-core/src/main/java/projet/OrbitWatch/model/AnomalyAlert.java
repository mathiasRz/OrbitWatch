package projet.OrbitWatch.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entité JPA représentant une alerte d'anomalie orbitale détectée
 * par {@code AnomalyDetectionService}.
 *
 * <p>Schéma BDD : table {@code anomaly_alert} (migration Flyway V3)
 */
@Entity
@Table(
    name = "anomaly_alert",
    indexes = {
        @Index(
            name = "idx_anomaly_norad_type",
            columnList = "norad_id, type, detected_at DESC"
        )
    }
)
public class AnomalyAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identifiant NORAD stable. */
    @Column(name = "norad_id", nullable = false)
    private int noradId;

    /** Nom du satellite au moment de la détection. */
    @Column(name = "satellite_name", length = 120)
    private String satelliteName;

    /** Instant UTC de détection de l'anomalie. */
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    /** Nature de l'anomalie détectée. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private AnomalyType type;

    /** Niveau de sévérité. */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private AnomalySeverity severity;

    /** Description humaine de l'anomalie (ex : "Altitude variation of +12.3 km detected"). */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** {@code true} si l'alerte a été lue/acquittée par l'utilisateur. */
    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged = false;

    // ── Constructeurs ─────────────────────────────────────────────────────────

    protected AnomalyAlert() {}

    public AnomalyAlert(int noradId, String satelliteName, Instant detectedAt,
                        AnomalyType type, AnomalySeverity severity, String description) {
        this.noradId       = noradId;
        this.satelliteName = satelliteName;
        this.detectedAt    = detectedAt;
        this.type          = type;
        this.severity      = severity;
        this.description   = description;
        this.acknowledged  = false;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long            getId()            { return id; }
    public int             getNoradId()       { return noradId; }
    public String          getSatelliteName() { return satelliteName; }
    public Instant         getDetectedAt()    { return detectedAt; }
    public AnomalyType     getType()          { return type; }
    public AnomalySeverity getSeverity()      { return severity; }
    public String          getDescription()   { return description; }
    public boolean         isAcknowledged()   { return acknowledged; }

    public void acknowledge() { this.acknowledged = true; }
}

