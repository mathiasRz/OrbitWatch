import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  inject,
  OnInit
} from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { catchError, forkJoin, of, switchMap } from 'rxjs';
import { OrbitalHistoryService } from '../../services/orbital-history.service';
import { AnomalyService } from '../../services/anomaly.service';
import { AlertBadgeComponent } from '../../components/alert-badge/alert-badge.component';
import { AlertPanelComponent } from '../../components/alert-panel/alert-panel.component';
import { OrbitalChartComponent } from '../../components/orbital-chart/orbital-chart.component';
import { OrbitalElements, SatelliteSummary, AnomalyAlert } from '../../models/satellite.model';
import { ConjunctionAlert } from '../../models/conjunction.model';
import { ConjunctionService } from '../../services/conjunction.service';
import { CombinedAlerts } from '../../components/alert-badge/alert-badge.component';

@Component({
  selector: 'app-satellite-profile-page',
  standalone: true,
  imports: [
    CommonModule,
    DatePipe,
    DecimalPipe,
    RouterLink,
    RouterLinkActive,
    AlertBadgeComponent,
    AlertPanelComponent,
    OrbitalChartComponent
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './satellite-profile-page.component.html',
  styleUrl: './satellite-profile-page.component.scss'
})
export class SatelliteProfilePageComponent implements OnInit {

  noradId: number | null = null;
  satName: string | null = null;  // nom affiché en mode byname avant résolution
  summary: SatelliteSummary | null = null;
  history: OrbitalElements[] = [];
  anomalies: AnomalyAlert[] = [];

  isLoading = true;
  errorMsg: string | null = null;

  panelOpen = false;
  panelAlerts: CombinedAlerts = { conjunctions: [], anomalies: [] };

  private readonly route             = inject(ActivatedRoute);
  private readonly router            = inject(Router);
  private readonly cdr               = inject(ChangeDetectorRef);
  private readonly orbitalHistorySvc = inject(OrbitalHistoryService);
  private readonly anomalySvc        = inject(AnomalyService);
  private readonly conjunctionSvc    = inject(ConjunctionService);

  ngOnInit(): void {
    const noradParam = this.route.snapshot.paramMap.get('noradId');
    const nameParam  = this.route.snapshot.paramMap.get('name');

    if (noradParam) {
      const id = Number(noradParam);
      if (isNaN(id) || id <= 0) {
        this.errorMsg  = 'Identifiant NORAD invalide.';
        this.isLoading = false;
        this.cdr.markForCheck();
        return;
      }
      this.noradId = id;
      this.loadByNoradId(id);
    } else if (nameParam) {
      this.satName = nameParam;
      this.loadByName(nameParam);
    } else {
      this.errorMsg  = 'Paramètre manquant.';
      this.isLoading = false;
      this.cdr.markForCheck();
    }
  }

  private loadByNoradId(noradId: number): void {
    this.isLoading = true;
    forkJoin({
      summary: this.orbitalHistorySvc.getSummary(noradId).pipe(catchError(() => of(null))),
      history: this.orbitalHistorySvc.getHistory(noradId, 30).pipe(catchError(() => of([] as OrbitalElements[]))),
      anomalies: this.anomalySvc.getAlerts({ noradId, size: 10 }).pipe(catchError(() => of({ content: [] as AnomalyAlert[], totalElements: 0, totalPages: 0, number: 0, size: 10 })))
    }).subscribe(({ summary, history, anomalies }) => {
      this.summary   = summary;
      this.history   = history;
      this.anomalies = anomalies.content;
      this.isLoading = false;
      if (!summary) this.errorMsg = `Aucune donnée pour NORAD ${noradId}. Les données s'accumulent après le premier fetch TLE.`;
      this.cdr.markForCheck();
    });
  }

  private loadByName(name: string): void {
    this.isLoading = true;
    // D'abord on résout le summary par nom, puis on charge le reste avec le noradId obtenu
    this.orbitalHistorySvc.getSummaryByName(name).pipe(
      catchError(() => of(null)),
      switchMap(summary => {
        if (!summary) {
          return of({ summary: null as SatelliteSummary | null, history: [] as OrbitalElements[], anomalies: [] as AnomalyAlert[] });
        }
        this.noradId = summary.noradId;
        return forkJoin({
          summary: of(summary),
          history: this.orbitalHistorySvc.getHistory(summary.noradId, 30).pipe(catchError(() => of([] as OrbitalElements[]))),
          anomalies: this.anomalySvc.getAlerts({ noradId: summary.noradId, size: 10 }).pipe(
            catchError(() => of({ content: [] as AnomalyAlert[], totalElements: 0, totalPages: 0, number: 0, size: 10 }))
          )
        }).pipe(
          // Remap pour aligner les types
          switchMap(r => of({ summary: r.summary, history: r.history, anomalies: r.anomalies.content }))
        );
      })
    ).subscribe(({ summary, history, anomalies }) => {
      this.summary   = summary;
      this.history   = history;
      this.anomalies = anomalies;
      this.isLoading = false;
      if (!summary) this.errorMsg = `Satellite "${name}" introuvable dans le catalogue courant.`;
      this.cdr.markForCheck();
    });
  }

  exportCsv(): void {
    if (!this.noradId) return;
    this.orbitalHistorySvc.exportCsv(this.noradId).subscribe(blob => {
      const url = URL.createObjectURL(blob);
      const a   = document.createElement('a');
      a.href = url;
      a.download = `orbital-history-${this.noradId}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    });
  }

  openConjunction(sat1: string, sat2: string): void {
    this.router.navigate(['/conjunction'], { queryParams: { sat1, sat2 } });
  }

  openPanel(alerts: CombinedAlerts): void {
    this.panelAlerts = alerts;
    this.panelOpen   = true;
    this.cdr.markForCheck();
  }

  closePanel(): void {
    this.panelOpen = false;
    this.cdr.markForCheck();
  }

  refreshPanel(): void {
    this.conjunctionSvc.getUnreadAlerts().subscribe(conjunctions => {
      this.panelAlerts = { conjunctions, anomalies: this.panelAlerts.anomalies };
      this.cdr.markForCheck();
    });
  }

  severityClass(severity: string): string {
    switch (severity) {
      case 'HIGH':   return 'critical';
      case 'MEDIUM': return 'warning';
      default:       return 'caution';
    }
  }

  anomalyTypeLabel(type: string): string {
    switch (type) {
      case 'ALTITUDE_CHANGE':     return 'Δ Altitude';
      case 'INCLINATION_CHANGE':  return 'Δ Inclinaison';
      case 'RAAN_DRIFT':          return 'Dérive RAAN';
      case 'ECCENTRICITY_CHANGE': return 'Δ Excentricité';
      case 'STATISTICAL':         return 'Z-score';
      default: return type;
    }
  }

  get tleAgeWarning(): boolean {
    return (this.summary?.tleAgeHours ?? 0) > 168;
  }
}


