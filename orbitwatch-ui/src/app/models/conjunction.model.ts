import { SatellitePosition } from './satellite-position.model';

export interface ConjunctionRequest {
  nameSat1: string;
  tle1Sat1: string;
  tle2Sat1: string;
  nameSat2: string;
  tle1Sat2: string;
  tle2Sat2: string;
  durationHours: number;
  stepSeconds: number;
  thresholdKm: number;
}

export interface AnalyzeByNameRequest {
  nameSat1: string;
  nameSat2: string;
  durationHours: number;
  stepSeconds: number;
  thresholdKm: number;
}

export interface ConjunctionEvent {
  tca: string;          // ISO-8601
  distanceKm: number;
  sat1: SatellitePosition;
  sat2: SatellitePosition;
}

export interface ConjunctionReport {
  nameSat1: string;
  nameSat2: string;
  thresholdKm: number;
  windowStart: string;
  windowEnd: string;
  events: ConjunctionEvent[];
}

export interface ConjunctionAlert {
  id: number;
  nameSat1: string;
  nameSat2: string;
  tca: string;
  distanceKm: number;
  lat1: number; lon1: number; alt1: number;
  lat2: number; lon2: number; alt2: number;
  detectedAt: string;
  acknowledged: boolean;
}

export interface AlertPage {
  content: ConjunctionAlert[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

