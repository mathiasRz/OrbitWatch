import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { OrbitalElements, SatelliteSummary } from '../models/satellite.model';
import { API_ENDPOINTS } from '../config/api-endpoints';

@Injectable({ providedIn: 'root' })
export class OrbitalHistoryService {
  private readonly http = inject(HttpClient);

  /** Historique orbital sur N jours (défaut 30) */
  getHistory(noradId: number, days = 30): Observable<OrbitalElements[]> {
    const params = new HttpParams().set('days', days);
    return this.http.get<OrbitalElements[]>(API_ENDPOINTS.orbitalHistory.byNorad(noradId), { params });
  }

  /** Dernier snapshot connu */
  getLatest(noradId: number): Observable<OrbitalElements> {
    return this.http.get<OrbitalElements>(API_ENDPOINTS.orbitalHistory.latest(noradId));
  }

  /** Résumé complet (TLE courant + dernier snapshot + conjunctions + textSummary) */
  getSummary(noradId: number): Observable<SatelliteSummary> {
    return this.http.get<SatelliteSummary>(API_ENDPOINTS.satellite.summary(noradId));
  }

  /** Résumé complet via le nom du satellite (résolu côté serveur) */
  getSummaryByName(name: string): Observable<SatelliteSummary> {
    return this.http.get<SatelliteSummary>(API_ENDPOINTS.satellite.summaryByName(name));
  }

  /** Export CSV — retourne un Blob téléchargeable */
  exportCsv(noradId: number): Observable<Blob> {
    return this.http.get(API_ENDPOINTS.orbitalHistory.export(noradId), { responseType: 'blob' });
  }
}
