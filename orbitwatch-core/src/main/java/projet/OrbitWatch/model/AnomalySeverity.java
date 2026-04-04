package projet.OrbitWatch.model;

/**
 * Niveau de sévérité d'une anomalie orbitale.
 *
 * <ul>
 *   <li>{@link #LOW} — variation faible, à surveiller</li>
 *   <li>{@link #MEDIUM} — variation notable, probable manœuvre</li>
 *   <li>{@link #HIGH} — variation forte, action immédiate recommandée</li>
 * </ul>
 */
public enum AnomalySeverity {
    LOW,
    MEDIUM,
    HIGH
}

