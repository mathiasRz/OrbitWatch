export interface OrbitalElements {
  noradId: number;
  satelliteName: string;
  epochTle: string;          // ISO-8601
  semiMajorAxisKm: number;
  eccentricity: number;
  inclinationDeg: number;
  raanDeg: number;
  argOfPerigeeDeg: number;
  meanMotionRevDay: number;
  altitudePerigeeKm: number;
  altitudeApogeeKm: number;
}

export interface SatelliteSummary {
  noradId: number;
  name: string;
  tleLine1: string;
  tleLine2: string;
  tleEpoch: string;         // ISO-8601
  tleAgeHours: number;
  latestElements: OrbitalElements | null;
  recentConjunctions: ConjunctionAlertRef[];
  textSummary: string;
}

/** Référence légère à une ConjunctionAlert (évite la dépendance circulaire avec conjunction.model) */
export interface ConjunctionAlertRef {
  id: number;
  nameSat1: string;
  nameSat2: string;
  tca: string;
  distanceKm: number;
  acknowledged: boolean;
}

export interface AnomalyAlert {
  id: number;
  noradId: number;
  satelliteName: string;
  detectedAt: string;       // ISO-8601
  type: AnomalyType;
  severity: AnomalySeverity;
  description: string;
  acknowledged: boolean;
}

export type AnomalyType =
  | 'ALTITUDE_CHANGE'
  | 'INCLINATION_CHANGE'
  | 'RAAN_DRIFT'
  | 'ECCENTRICITY_CHANGE'
  | 'STATISTICAL';

export type AnomalySeverity = 'LOW' | 'MEDIUM' | 'HIGH';

export interface AnomalyAlertPage {
  content: AnomalyAlert[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

