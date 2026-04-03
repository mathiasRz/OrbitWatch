import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  AlertPage,
  AnalyzeByNameRequest,
  ConjunctionAlert,
  ConjunctionReport,
  ConjunctionRequest
} from '../models/conjunction.model';

@Injectable({ providedIn: 'root' })
export class ConjunctionService {
  private readonly http    = inject(HttpClient);
  private readonly baseUrl = 'http://localhost:8080/api/v1/conjunction';

  /** Analyse on-demand avec TLEs bruts */
  analyze(req: ConjunctionRequest): Observable<ConjunctionReport> {
    return this.http.post<ConjunctionReport>(`${this.baseUrl}/analyze`, req);
  }

  /** Analyse on-demand par nom de satellite (résolu côté serveur) */
  analyzeByName(req: AnalyzeByNameRequest): Observable<ConjunctionReport> {
    return this.http.post<ConjunctionReport>(`${this.baseUrl}/analyze-by-name`, req);
  }

  /** Liste paginée de toutes les alertes */
  getAlerts(page = 0, size = 20): Observable<AlertPage> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<AlertPage>(`${this.baseUrl}/alerts`, { params });
  }

  /** Alertes non acquittées — pour le badge navbar */
  getUnreadAlerts(): Observable<ConjunctionAlert[]> {
    return this.http.get<ConjunctionAlert[]>(`${this.baseUrl}/alerts/unread`);
  }

  /** Acquitte une alerte */
  acknowledge(id: number): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/alerts/${id}/ack`, null);
  }
}

