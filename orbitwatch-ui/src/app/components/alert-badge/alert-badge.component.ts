import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  inject,
  OnDestroy,
  OnInit,
  Output
} from '@angular/core';
import { interval, Subject, switchMap, catchError, of, combineLatest, startWith } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { ConjunctionService } from '../../services/conjunction.service';
import { AnomalyService } from '../../services/anomaly.service';
import { ConjunctionAlert } from '../../models/conjunction.model';
import { AnomalyAlert } from '../../models/satellite.model';

export interface CombinedAlerts {
  conjunctions: ConjunctionAlert[];
  anomalies: AnomalyAlert[];
}

/**
 * Badge de notification combiné (rapprochements + anomalies).
 * Polling toutes les 30 s. Émet (badgeClicked) avec les deux listes.
 */
@Component({
  selector: 'app-alert-badge',
  standalone: true,
  imports: [],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alert-badge.component.html',
  styleUrl: './alert-badge.component.scss'
})
export class AlertBadgeComponent implements OnInit, OnDestroy {

  @Output() badgeClicked = new EventEmitter<CombinedAlerts>();

  conjunctionAlerts: ConjunctionAlert[] = [];
  anomalyAlerts: AnomalyAlert[] = [];

  get count(): number { return this.conjunctionAlerts.length + this.anomalyAlerts.length; }

  private readonly conjunctionService = inject(ConjunctionService);
  private readonly anomalyService     = inject(AnomalyService);
  private readonly cdr                = inject(ChangeDetectorRef);
  private readonly destroy$           = new Subject<void>();

  ngOnInit(): void {
    interval(30_000).pipe(
      startWith(0),
      takeUntil(this.destroy$),
      switchMap(() => combineLatest([
        this.conjunctionService.getUnreadAlerts().pipe(catchError(() => of([] as ConjunctionAlert[]))),
        this.anomalyService.getUnreadAlerts().pipe(catchError(() => of([] as AnomalyAlert[])))
      ]))
    ).subscribe(([conjunctions, anomalies]) => {
      this.conjunctionAlerts = conjunctions;
      this.anomalyAlerts     = anomalies;
      this.cdr.markForCheck();
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onClick(): void {
    this.badgeClicked.emit({
      conjunctions: this.conjunctionAlerts,
      anomalies: this.anomalyAlerts
    });
  }
}
