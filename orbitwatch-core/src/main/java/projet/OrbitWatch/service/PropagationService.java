package projet.OrbitWatch.service;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.BodyShape;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import projet.OrbitWatch.dto.SatellitePosition;
import projet.OrbitWatch.dto.TleEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service de propagation orbitale basé sur le propagateur SGP4/SDP4 d'Orekit.
 * Fournit le calcul de position instantanée et la génération de ground track.
 */
@Service
public class PropagationService {

    private static final Logger log = LoggerFactory.getLogger(PropagationService.class);

    /** Facteur de conversion mètres → kilomètres. */
    private static final double M_TO_KM = 1e-3;

    /**
     * Calcule la position géodésique d'un satellite à un instant donné.
     *
     * @param tle1  Première ligne du TLE (line 1)
     * @param tle2  Deuxième ligne du TLE (line 2)
     * @param name  Nom du satellite (peut être null ou vide)
     * @param epoch Instant UTC cible (si null, utilise l'époque du TLE)
     * @return {@link SatellitePosition} contenant lat/lon/alt et coordonnées ECI
     */
    public SatellitePosition propagate(String tle1, String tle2, String name, Instant epoch) {

        TLE tle = new TLE(tle1.trim(), tle2.trim());
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);

        AbsoluteDate targetDate = (epoch != null)
                ? new AbsoluteDate(epoch.toString(), TimeScalesFactory.getUTC())
                : tle.getDate();

        SpacecraftState state = propagator.propagate(targetDate);

        // ── Coordonnées ECI (TEME, repère natif SGP4) ────────────────────────
        Frame teme = FramesFactory.getTEME();
        PVCoordinates pv = state.getPVCoordinates(teme);
        Vector3D pos = pv.getPosition();

        double xKm = pos.getX() * M_TO_KM;
        double yKm = pos.getY() * M_TO_KM;
        double zKm = pos.getZ() * M_TO_KM;

        // ── Conversion vers latitude / longitude / altitude ───────────────────
        Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        BodyShape earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                itrf);

        PVCoordinates pvItrf = teme.getTransformTo(itrf, targetDate)
                .transformPVCoordinates(pv);
        GeodeticPoint geo = earth.transform(pvItrf.getPosition(), itrf, targetDate);

        double latDeg = Math.toDegrees(geo.getLatitude());
        double lonDeg = Math.toDegrees(geo.getLongitude());
        double altKm  = geo.getAltitude() * M_TO_KM;

        String satName = (name != null && !name.isBlank()) ? name.trim() : "UNKNOWN";

        log.debug("Propagation {} → lat={} lon={} alt={} km",
                satName,
                String.format("%.2f°", latDeg),
                String.format("%.2f°", lonDeg),
                String.format("%.1f", altKm));

        return new SatellitePosition(
                satName,
                targetDate.toDate(TimeScalesFactory.getUTC()).toInstant(),
                latDeg, lonDeg, altKm,
                xKm, yKm, zKm
        );
    }

    /**
     * Génère un ground track : liste de positions entre maintenant et
     * {@code durationMinutes} minutes plus tard, avec un pas de {@code stepSeconds} secondes.
     *
     * @param tle1            Première ligne du TLE
     * @param tle2            Deuxième ligne du TLE
     * @param name            Nom du satellite
     * @param startEpoch      Instant de début (si null, utilise l'époque du TLE)
     * @param durationMinutes Durée totale en minutes
     * @param stepSeconds     Pas d'échantillonnage en secondes
     * @return Liste ordonnée de {@link SatellitePosition}
     */
    public List<SatellitePosition> groundTrack(
            String tle1, String tle2, String name,
            Instant startEpoch, int durationMinutes, int stepSeconds) {

        TLE tle = new TLE(tle1.trim(), tle2.trim());
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);

        AbsoluteDate start = (startEpoch != null)
                ? new AbsoluteDate(startEpoch.toString(), TimeScalesFactory.getUTC())
                : tle.getDate();

        Frame teme = FramesFactory.getTEME();
        Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        BodyShape earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                itrf);

        String satName = (name != null && !name.isBlank()) ? name.trim() : "UNKNOWN";
        int totalSteps = (durationMinutes * 60) / stepSeconds;
        List<SatellitePosition> track = new ArrayList<>(totalSteps + 1);

        for (int i = 0; i <= totalSteps; i++) {
            AbsoluteDate date = start.shiftedBy((double) i * stepSeconds);
            SpacecraftState state = propagator.propagate(date);

            PVCoordinates pv = state.getPVCoordinates(teme);
            Vector3D pos = pv.getPosition();

            PVCoordinates pvItrf = teme.getTransformTo(itrf, date)
                    .transformPVCoordinates(pv);
            GeodeticPoint geo = earth.transform(pvItrf.getPosition(), itrf, date);

            track.add(new SatellitePosition(
                    satName,
                    date.toDate(TimeScalesFactory.getUTC()).toInstant(),
                    Math.toDegrees(geo.getLatitude()),
                    Math.toDegrees(geo.getLongitude()),
                    geo.getAltitude() * M_TO_KM,
                    pos.getX() * M_TO_KM,
                    pos.getY() * M_TO_KM,
                    pos.getZ() * M_TO_KM
            ));
        }

        log.info("Ground track généré : {} points sur {} min (pas {}s)",
                track.size(), durationMinutes, stepSeconds);
        return track;
    }

    /**
     * Calcule la position instantanée (Instant.now()) de chaque satellite
     * présent dans la liste fournie, en parallèle.
     *
     * <p>Utilisé par {@code GET /api/v1/orbit/positions} et par le {@code ConjunctionScanJob}.
     *
     * @param entries liste des TleEntry à propager
     * @return liste de {@link SatellitePosition} — les entrées en erreur sont silencieusement ignorées
     */
    public List<SatellitePosition> snapshotCatalog(List<TleEntry> entries) {
        Instant now = Instant.now();
        return entries.parallelStream()
                .map(e -> {
                    try {
                        return propagate(e.line1(), e.line2(), e.name(), now);
                    } catch (Exception ex) {
                        log.warn("[PropagationService] Propagation ignorée pour '{}' : {}", e.name(), ex.getMessage());
                        return null;
                    }
                })
                .filter(p -> p != null)
                .collect(Collectors.toList());
    }
}


