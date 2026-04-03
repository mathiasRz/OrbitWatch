package projet.OrbitWatch.service;

import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import projet.OrbitWatch.dto.OrbitalElements;

import java.time.Instant;

/**
 * Service pur Orekit chargé d'extraire les paramètres orbitaux Keplériens
 * à partir des deux lignes brutes d'un TLE.
 *
 * <p>Aucune propagation n'est effectuée : les éléments sont lus directement
 * depuis la structure {@code TLE} d'Orekit (moyenne motion, excentricité, etc.).
 *
 * <p><b>Calculs clés :</b>
 * <ul>
 *   <li>{@code a = (μ / n²)^(1/3)} — demi-grand axe via la 3e loi de Kepler</li>
 *   <li>{@code altPerigee = a*(1-e) - R_Earth}</li>
 *   <li>{@code altApogee  = a*(1+e) - R_Earth}</li>
 * </ul>
 *
 * <p>Orekit doit être initialisé ({@code DataContext}) avant d'appeler ce service.
 */
@Service
public class OrbitalElementsExtractor {

    private static final Logger log = LoggerFactory.getLogger(OrbitalElementsExtractor.class);

    /** Conversion mètres → kilomètres. */
    private static final double M_TO_KM = 1e-3;

    /** Rayon équatorial WGS84 en km. */
    private static final double R_EARTH_KM =
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS * M_TO_KM;

    /** Constante gravitationnelle standard μ (m³/s²). */
    private static final double MU = Constants.WGS84_EARTH_MU;

    /** Conversion rad/s → révolutions/jour. */
    private static final double RAD_PER_S_TO_REV_PER_DAY = 86_400.0 / (2.0 * Math.PI);

    /**
     * Extrait les paramètres orbitaux Keplériens depuis les deux lignes TLE.
     *
     * @param satelliteName Nom du satellite (ligne 0 du TLE)
     * @param line1         Première ligne TLE
     * @param line2         Deuxième ligne TLE
     * @return {@link OrbitalElements} contenant les 6 éléments Keplériens et
     *         les altitudes de périgée/apogée
     * @throws IllegalArgumentException si les lignes TLE sont malformées
     */
    public OrbitalElements extract(String satelliteName, String line1, String line2) {

        TLE tle = new TLE(line1.trim(), line2.trim());

        // ── Paramètres lus directement depuis Orekit ─────────────────────────
        double nRadPerS   = tle.getMeanMotion();            // rad/s
        double e          = tle.getE();                     // sans dimension
        double iRad       = tle.getI();                     // rad
        double raanRad    = tle.getRaan();                  // rad
        double omegaRad   = tle.getPerigeeArgument();       // rad
        int    noradId    = tle.getSatelliteNumber();

        // ── Demi-grand axe via la 3e loi de Kepler : a = (μ/n²)^(1/3) ───────
        double aM   = Math.cbrt(MU / (nRadPerS * nRadPerS)); // mètres
        double aKm  = aM * M_TO_KM;

        // ── Altitudes périgée / apogée ────────────────────────────────────────
        double periKm = aKm * (1.0 - e) - R_EARTH_KM;
        double apoKm  = aKm * (1.0 + e) - R_EARTH_KM;

        // ── Époque TLE → Instant UTC ──────────────────────────────────────────
        Instant epochTle = tle.getDate()
                .toDate(TimeScalesFactory.getUTC())
                .toInstant();

        OrbitalElements elements = new OrbitalElements(
                noradId,
                satelliteName,
                epochTle,
                aKm,
                e,
                Math.toDegrees(iRad),
                Math.toDegrees(raanRad),
                Math.toDegrees(omegaRad),
                nRadPerS * RAD_PER_S_TO_REV_PER_DAY,
                periKm,
                apoKm
        );

        log.debug("[OrbitalElementsExtractor] {} (NORAD {}) → a={} km, i={}°, e={}, peri={} km, apo={} km",
                satelliteName, noradId,
                String.format("%.1f", aKm),
                String.format("%.2f", Math.toDegrees(iRad)),
                String.format("%.6f", e),
                String.format("%.1f", periKm),
                String.format("%.1f", apoKm));

        return elements;
    }
}


