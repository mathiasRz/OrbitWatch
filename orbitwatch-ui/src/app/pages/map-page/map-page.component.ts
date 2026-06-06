import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component,
  computed, inject, OnInit, signal
} from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute, RouterLink, RouterLinkActive } from '@angular/router';
import { combineLatest, catchError, of } from 'rxjs';
import { MapLiveComponent }        from '../../components/map-live/map-live.component';
import { MapComponent }            from '../../components/map/map.component';
import { ConjunctionMapComponent } from '../../components/conjunction-map/conjunction-map.component';
import { TleFormComponent }        from '../../components/tle-form/tle-form.component';
import { ConjunctionFormComponent } from '../../components/conjunction-form/conjunction-form.component';
import { AlertBadgeComponent, CombinedAlerts } from '../../components/alert-badge/alert-badge.component';
import { AlertPanelComponent }     from '../../components/alert-panel/alert-panel.component';
import { OrbitService, GroundTrackParams } from '../../services/orbit.service';
import { ConjunctionService }      from '../../services/conjunction.service';
import { AnomalyService }          from '../../services/anomaly.service';
import { SatellitePosition }       from '../../models/satellite-position.model';
import { ConjunctionReport, ConjunctionAlert } from '../../models/conjunction.model';
import { AnomalyAlert }            from '../../models/satellite.model';

export type MapMode = 'live' | 'orbit' | 'conjunction';

@Component({
  selector: 'app-map-page',
  standalone: true,
  imports: [
    CommonModule, DatePipe, DecimalPipe,
    RouterLink, RouterLinkActive,
    MapLiveComponent, MapComponent, ConjunctionMapComponent,
    TleFormComponent, ConjunctionFormComponent,
    AlertBadgeComponent, AlertPanelComponent
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './map-page.component.html',
  styleUrl: './map-page.component.scss'
})
export class MapPageComponent implements OnInit {

  // ── Mode actif ──────────────────────────────────────────────────────────
  mode: MapMode = 'live';

  // ── Ground track state ───────────────────────────────────────────────────
  track        = signal<SatellitePosition[]>([]);
  trackLoading = signal(false);
  trackError   = signal<string | null>(null);
  avgAltitude  = computed(() => {
    const t = this.track();
    if (!t.length) return 0;
    return t.reduce((s, p) => s + p.altitude, 0) / t.length;
  });

  // ── Conjunction state ────────────────────────────────────────────────────
  conjReport:  ConjunctionReport | null = null;
  conjLoading = false;
  conjError:   string | null = null;

  // ── Alerts ───────────────────────────────────────────────────────────────
  panelOpen    = false;
  panelAlerts: CombinedAlerts = { conjunctions: [], anomalies: [] };

  private readonly route              = inject(ActivatedRoute);
  private readonly cdr                = inject(ChangeDetectorRef);
  private readonly orbitService       = inject(OrbitService);
  private readonly conjunctionService = inject(ConjunctionService);
  private readonly anomalyService     = inject(AnomalyService);

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    const mode   = params.get('mode');
    const name   = params.get('name');

    if (mode === 'conjunction') {
      this.mode = 'conjunction';
    } else if (mode === 'orbit') {
      this.mode = 'orbit';
      if (name) this.onPropagate({ name, duration: 90, step: 60 });
    }
  }

  setMode(m: MapMode): void {
    this.mode = m;
    this.cdr.markForCheck();
  }

  // ── Live : satellite sélectionné depuis la carte → passe en mode orbit ──
  onSatelliteSelected(name: string): void {
    this.mode = 'orbit';
    this.onPropagate({ name, duration: 90, step: 60 });
    this.cdr.markForCheck();
  }

  // ── Ground track ─────────────────────────────────────────────────────────
  onPropagate(params: GroundTrackParams): void {
    this.trackError.set(null);
    this.trackLoading.set(true);
    this.orbitService.getGroundTrack(params).subscribe({
      next:  data => { this.track.set([...data]); this.trackLoading.set(false); },
      error: err  => {
        this.trackError.set(`Erreur : ${err.status ?? ''} ${err.message}`);
        this.trackLoading.set(false);
      }
    });
  }

  // ── Conjunction ──────────────────────────────────────────────────────────
  onReportReady(report: ConjunctionReport): void {
    this.conjReport = report;
    this.cdr.markForCheck();
  }

  onConjLoading(v: boolean): void {
    this.conjLoading = v;
    this.cdr.markForCheck();
  }

  onConjError(msg: string | null): void {
    this.conjError = msg;
    this.cdr.markForCheck();
  }

  severityClass(distanceKm: number): string {
    if (distanceKm < 1) return 'critical';
    if (distanceKm < 5) return 'warning';
    return 'caution';
  }

  // ── Alerts ───────────────────────────────────────────────────────────────
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
    combineLatest([
      this.conjunctionService.getUnreadAlerts().pipe(catchError(() => of([] as ConjunctionAlert[]))),
      this.anomalyService.getUnreadAlerts().pipe(catchError(() => of([] as AnomalyAlert[])))
    ]).subscribe(([conjunctions, anomalies]) => {
      this.panelAlerts = { conjunctions, anomalies };
      this.cdr.markForCheck();
    });
  }
}
