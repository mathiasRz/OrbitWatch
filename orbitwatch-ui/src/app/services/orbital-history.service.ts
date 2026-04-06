import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { OrbitalElements, SatelliteSummary } from '../models/satellite.model';

@Injectable({ providedIn: 'root' })
export class OrbitalHistoryService {
  private readonly http        = inject(HttpClient);
  private readonly baseUrl     = 'http://localhost:8080/api/v1/orbital-history';
  private readonly satelliteUrl = 'http://localhost:8080/api/v1/satellite';

  /** Historique orbital sur N jours (défaut 30) */
  getHistory(noradId: number, days = 30): Observable<OrbitalElements[]> {
    const params = new HttpParams().set('days', days);
    return this.http.get<OrbitalElements[]>(`${this.baseUrl}/${noradId}`, { params });
  }

  /** Dernier snapshot connu */
  getLatest(noradId: number): Observable<OrbitalElements> {
    return this.http.get<OrbitalElements>(`${this.baseUrl}/${noradId}/latest`);
  }

  /** Résumé complet (TLE courant + dernier snapshot + conjunctions + textSummary) */
  getSummary(noradId: number): Observable<SatelliteSummary> {
    return this.http.get<SatelliteSummary>(`${this.satelliteUrl}/${noradId}/summary`);
  }

  /** Résumé complet via le nom du satellite (résolu côté serveur) */
  getSummaryByName(name: string): Observable<SatelliteSummary> {
    return this.http.get<SatelliteSummary>(`${this.satelliteUrl}/byname/${encodeURIComponent(name)}/summary`);
  }

  /** Export CSV — retourne un Blob téléchargeable */
  exportCsv(noradId: number): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${noradId}/export`, { responseType: 'blob' });
  }
}


