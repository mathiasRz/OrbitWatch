import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { SatellitePosition } from '../models/satellite-position.model';

export interface GroundTrackParams {
  name: string;
  epoch?: string;
  duration?: number;
  step?: number;
}

@Injectable({ providedIn: 'root' })
export class OrbitService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl    = 'http://localhost:8080/api/v1/orbit';
  private readonly catalogUrl = 'http://localhost:8080/api/v1/tle';

  getSatelliteNames(): Observable<string[]> {
    return this.http.get<string[]>(`${this.catalogUrl}/names`);
  }

  getPosition(name: string, epoch?: string): Observable<SatellitePosition> {
    let params = new HttpParams().set('name', name);
    if (epoch) params = params.set('epoch', epoch);
    return this.http.get<SatellitePosition>(`${this.baseUrl}/position`, { params });
  }

  getAllPositions(catalog: string = 'stations'): Observable<SatellitePosition[]> {
    const params = new HttpParams().set('catalog', catalog);
    return this.http.get<SatellitePosition[]>(`${this.baseUrl}/positions`, { params });
  }

  getGroundTrack(p: GroundTrackParams): Observable<SatellitePosition[]> {
    let params = new HttpParams()
      .set('name',     p.name)
      .set('duration', p.duration ?? 90)
      .set('step',     p.step     ?? 60);
    if (p.epoch) params = params.set('epoch', p.epoch);
    return this.http.get<SatellitePosition[]>(`${this.baseUrl}/groundtrack`, { params });
  }
}

