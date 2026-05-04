import { Component, signal, computed, OnInit, inject, ChangeDetectorRef, ChangeDetectionStrategy } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ActivatedRoute, RouterLink, RouterLinkActive } from '@angular/router';
import { combineLatest, catchError, of } from 'rxjs';
import { OrbitService, GroundTrackParams } from '../../services/orbit.service';
import { SatellitePosition } from '../../models/satellite-position.model';
import { MapComponent } from '../../components/map/map.component';
import { TleFormComponent } from '../../components/tle-form/tle-form.component';
import { AlertBadgeComponent, CombinedAlerts } from '../../components/alert-badge/alert-badge.component';
import { AlertPanelComponent } from '../../components/alert-panel/alert-panel.component';
import { ConjunctionService } from '../../services/conjunction.service';
import { AnomalyService } from '../../services/anomaly.service';
import { ConjunctionAlert } from '../../models/conjunction.model';
import { AnomalyAlert } from '../../models/satellite.model';

@Component({
  selector: 'app-orbit-page',
  standalone: true,
  imports: [DecimalPipe, MapComponent, TleFormComponent, RouterLink, RouterLinkActive, AlertBadgeComponent, AlertPanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './orbit-page.component.html',
  styleUrl: './orbit-page.component.scss'
})
export class OrbitPageComponent implements OnInit {
  track    = signal<SatellitePosition[]>([]);
  error    = signal<string | null>(null);
  loading  = signal(false);
  avgAltitude = computed(() => {
    const t = this.track();
    if (!t.length) return 0;
    return t.reduce((sum, p) => sum + p.altitude, 0) / t.length;
  });

  panelOpen    = false;
  panelAlerts: CombinedAlerts = { conjunctions: [], anomalies: [] };

  private readonly cdr                = inject(ChangeDetectorRef);
  private readonly conjunctionService = inject(ConjunctionService);
  private readonly anomalyService     = inject(AnomalyService);

  constructor(private orbitService: OrbitService, private route: ActivatedRoute) {}

  ngOnInit(): void {
    const name = this.route.snapshot.queryParamMap.get('name');
    if (name) {
      this.onPropagate({ name, duration: 90, step: 60 });
    }
  }

  onPropagate(params: GroundTrackParams): void {
    this.error.set(null);
    this.loading.set(true);
    this.orbitService.getGroundTrack(params).subscribe({
      next: (data) => { this.track.set([...data]); this.loading.set(false); },
      error: (err) => { this.error.set(`Erreur : ${err.status ?? ''} ${err.message}`); this.loading.set(false); }
    });
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
    combineLatest([
      this.conjunctionService.getUnreadAlerts().pipe(catchError(() => of([] as ConjunctionAlert[]))),
      this.anomalyService.getUnreadAlerts().pipe(catchError(() => of([] as AnomalyAlert[])))
    ]).subscribe(([conjunctions, anomalies]) => {
      this.panelAlerts = { conjunctions, anomalies };
      this.cdr.markForCheck();
    });
  }
}
