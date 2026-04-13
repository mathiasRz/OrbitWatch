package projet.OrbitWatch.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entité JPA représentant une alerte de rapprochement critique (conjunction)
 * détectée par le {@code ConjunctionScanJob}.
 *
 * <p>Schéma BDD : table {@code conjunction_alert}
 */
@Entity
@Table(
    name = "conjunction_alert",
    indexes = {
        // Index composite pour la déduplication par NORAD ID + tca
        @Index(name = "idx_conjunction_dedup", columnList = "norad_id1, norad_id2, tca")
    }
)
public class ConjunctionAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom du satellite 1. */
    @Column(name = "name_sat1", nullable = false)
    private String nameSat1;

    /** Nom du satellite 2. */
    @Column(name = "name_sat2", nullable = false)
    private String nameSat2;

    /** NORAD Catalog Number du satellite 1. */
    @Column(name = "norad_id1", nullable = false)
    private int noradId1;

    /** NORAD Catalog Number du satellite 2. */
    @Column(name = "norad_id2", nullable = false)
    private int noradId2;

    /** Time of Closest Approach : instant UTC du minimum de distance. */
    @Column(name = "tca", nullable = false)
    private Instant tca;

    /** Distance minimale au TCA en kilomètres (repère ECI). */
    @Column(name = "distance_km", nullable = false)
    private double distanceKm;

    // ── Position du satellite 1 au TCA ───────────────────────────────────────
    @Column(name = "lat1") private double lat1;
    @Column(name = "lon1") private double lon1;
    @Column(name = "alt1") private double alt1;

    // ── Position du satellite 2 au TCA ───────────────────────────────────────
    @Column(name = "lat2") private double lat2;
    @Column(name = "lon2") private double lon2;
    @Column(name = "alt2") private double alt2;

    /** Instant où le job a détecté cette alerte. */
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    /** {@code true} si l'alerte a été lue/acquittée par l'utilisateur. */
    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged = false;

    // ── Constructeurs ─────────────────────────────────────────────────────────

    protected ConjunctionAlert() {}

    public ConjunctionAlert(String nameSat1, String nameSat2,
                            int noradId1, int noradId2,
                            Instant tca, double distanceKm,
                            double lat1, double lon1, double alt1,
                            double lat2, double lon2, double alt2) {
        this.nameSat1    = nameSat1;
        this.nameSat2    = nameSat2;
        this.noradId1    = noradId1;
        this.noradId2    = noradId2;
        this.tca         = tca;
        this.distanceKm  = distanceKm;
        this.lat1        = lat1;
        this.lon1        = lon1;
        this.alt1        = alt1;
        this.lat2        = lat2;
        this.lon2        = lon2;
        this.alt2        = alt2;
        this.detectedAt  = Instant.now();
        this.acknowledged = false;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long    getId()          { return id; }
    public String  getNameSat1()    { return nameSat1; }
    public String  getNameSat2()    { return nameSat2; }
    public int     getNoradId1()    { return noradId1; }
    public int     getNoradId2()    { return noradId2; }
    public Instant getTca()         { return tca; }
    public double  getDistanceKm()  { return distanceKm; }
    public double  getLat1()        { return lat1; }
    public double  getLon1()        { return lon1; }
    public double  getAlt1()        { return alt1; }
    public double  getLat2()        { return lat2; }
    public double  getLon2()        { return lon2; }
    public double  getAlt2()        { return alt2; }
    public Instant getDetectedAt()  { return detectedAt; }
    public boolean isAcknowledged() { return acknowledged; }

    public void acknowledge() { this.acknowledged = true; }
}

