import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { HeatmapCell } from '../models/heatmap-cell.model';
import { API_ENDPOINTS } from '../config/api-endpoints';

@Injectable({ providedIn: 'root' })
export class DebrisHeatmapService {
  private readonly http = inject(HttpClient);

  /**
   * Récupère la heatmap de densité orbitale pour un catalogue donné.
   * Par défaut catalog=debris, altMin=0, altMax=2000.
   */
  getHeatmap(
    catalog: string = 'debris',
    altMin: number  = 0,
    altMax: number  = 2000
  ): Observable<HeatmapCell[]> {
    const params = new HttpParams()
      .set('catalog', catalog)
      .set('altMin',  altMin)
      .set('altMax',  altMax);
    return this.http.get<HeatmapCell[]>(API_ENDPOINTS.orbit.heatmap, { params });
  }
}
