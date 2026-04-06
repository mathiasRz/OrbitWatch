import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AnomalyAlert, AnomalyAlertPage, AnomalyType, AnomalySeverity } from '../models/satellite.model';

export interface AnomalyAlertFilters {
  noradId?: number;
  type?: AnomalyType;
  severity?: AnomalySeverity;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class AnomalyService {
  private readonly http    = inject(HttpClient);
  private readonly baseUrl = 'http://localhost:8080/api/v1/anomaly';

  /** Liste paginée + filtres optionnels */
  getAlerts(filters: AnomalyAlertFilters = {}): Observable<AnomalyAlertPage> {
    let params = new HttpParams();
    if (filters.noradId  != null) params = params.set('noradId',  filters.noradId);
    if (filters.type)              params = params.set('type',     filters.type);
    if (filters.severity)          params = params.set('severity', filters.severity);
    if (filters.from)              params = params.set('from',     filters.from);
    if (filters.to)                params = params.set('to',       filters.to);
    params = params.set('page', filters.page ?? 0).set('size', filters.size ?? 20);
    return this.http.get<AnomalyAlertPage>(`${this.baseUrl}/alerts`, { params });
  }

  /** Alertes non acquittées — pour le badge */
  getUnreadAlerts(): Observable<AnomalyAlert[]> {
    return this.http.get<AnomalyAlert[]>(`${this.baseUrl}/alerts/unread`);
  }

  /** Acquitte une alerte */
  acknowledge(id: number): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/alerts/${id}/ack`, null);
  }
}

