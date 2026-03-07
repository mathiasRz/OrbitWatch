import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { SatellitePosition } from '../models/satellite-position.model';

export interface GroundTrackParams {
  tle1: string;
  tle2: string;
  name?: string;
  epoch?: string;
  duration?: number;
  step?: number;
}

@Injectable({ providedIn: 'root' })
export class OrbitService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = 'http://localhost:8080/api/v1/orbit';

  getPosition(tle1: string, tle2: string, name?: string, epoch?: string): Observable<SatellitePosition> {
    let params = new HttpParams()
      .set('tle1', tle1)
      .set('tle2', tle2);
    if (name)  params = params.set('name', name);
    if (epoch) params = params.set('epoch', epoch);
    return this.http.get<SatellitePosition>(`${this.baseUrl}/position`, { params });
  }

  getGroundTrack(p: GroundTrackParams): Observable<SatellitePosition[]> {
    let params = new HttpParams()
      .set('tle1', p.tle1)
      .set('tle2', p.tle2)
      .set('duration', p.duration ?? 90)
      .set('step',     p.step     ?? 60);
    if (p.name)  params = params.set('name',  p.name);
    if (p.epoch) params = params.set('epoch', p.epoch);
    return this.http.get<SatellitePosition[]>(`${this.baseUrl}/groundtrack`, { params });
  }
}

