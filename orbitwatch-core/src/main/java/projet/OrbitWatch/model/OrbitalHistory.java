package projet.OrbitWatch.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entité JPA représentant un snapshot des paramètres orbitaux Keplériens
 * d'un satellite, persisté à chaque fetch TLE réussi.
 *
 * <p>Schéma BDD : table {@code orbital_history} (migration Flyway V2)
 *
 * <p>Le {@code noradId} (NORAD Catalog Number) est utilisé comme identifiant
 * stable universel, préféré au nom de satellite qui peut changer.
 */
@Entity
@Table(
    name = "orbital_history",
    indexes = {
        @Index(
            name = "idx_orbital_history_norad_time",
            columnList = "norad_id, fetched_at DESC"
        )
    }
)
public class OrbitalHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identifiant NORAD stable — clé de référence universelle. */
    @Column(name = "norad_id", nullable = false)
    private int noradId;

    /** Nom du satellite au moment du fetch (peut varier selon la source). */
    @Column(name = "satellite_name", length = 120)
    private String satelliteName;

    /** Instant UTC du fetch TLE qui a produit ce snapshot. */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** Demi-grand axe {@code a} en kilomètres. */
    @Column(name = "semi_major_axis_km")
    private double semiMajorAxisKm;

    /** Excentricité {@code e} (sans dimension). */
    @Column(name = "eccentricity")
    private double eccentricity;

    /** Inclinaison {@code i} en degrés. */
    @Column(name = "inclination_deg")
    private double inclinationDeg;

    /** Ascension droite du nœud ascendant {@code Ω} en degrés. */
    @Column(name = "raan_deg")
    private double raanDeg;

    /** Argument du périgée {@code ω} en degrés. */
    @Column(name = "arg_of_perigee_deg")
    private double argOfPerigeeDeg;

    /** Mouvement moyen {@code n} en révolutions par jour. */
    @Column(name = "mean_motion_rev_day")
    private double meanMotionRevDay;

    /** Altitude du périgée en km — {@code a*(1-e) - R_Earth}. */
    @Column(name = "altitude_perigee_km")
    private double altitudePerigeeKm;

    /** Altitude de l'apogée en km — {@code a*(1+e) - R_Earth}. */
    @Column(name = "altitude_apogee_km")
    private double altitudeApogeeKm;

    // ── Constructeurs ─────────────────────────────────────────────────────────

    protected OrbitalHistory() {}

    public OrbitalHistory(int noradId, String satelliteName, Instant fetchedAt,
                          double semiMajorAxisKm, double eccentricity,
                          double inclinationDeg, double raanDeg,
                          double argOfPerigeeDeg, double meanMotionRevDay,
                          double altitudePerigeeKm, double altitudeApogeeKm) {
        this.noradId           = noradId;
        this.satelliteName     = satelliteName;
        this.fetchedAt         = fetchedAt;
        this.semiMajorAxisKm   = semiMajorAxisKm;
        this.eccentricity      = eccentricity;
        this.inclinationDeg    = inclinationDeg;
        this.raanDeg           = raanDeg;
        this.argOfPerigeeDeg   = argOfPerigeeDeg;
        this.meanMotionRevDay  = meanMotionRevDay;
        this.altitudePerigeeKm = altitudePerigeeKm;
        this.altitudeApogeeKm  = altitudeApogeeKm;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long    getId()                { return id; }
    public int     getNoradId()           { return noradId; }
    public String  getSatelliteName()     { return satelliteName; }
    public Instant getFetchedAt()         { return fetchedAt; }
    public double  getSemiMajorAxisKm()   { return semiMajorAxisKm; }
    public double  getEccentricity()      { return eccentricity; }
    public double  getInclinationDeg()    { return inclinationDeg; }
    public double  getRaanDeg()           { return raanDeg; }
    public double  getArgOfPerigeeDeg()   { return argOfPerigeeDeg; }
    public double  getMeanMotionRevDay()  { return meanMotionRevDay; }
    public double  getAltitudePerigeeKm() { return altitudePerigeeKm; }
    public double  getAltitudeApogeeKm()  { return altitudeApogeeKm; }
}

