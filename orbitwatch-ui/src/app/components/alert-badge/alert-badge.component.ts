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
import { interval, Subject, switchMap, catchError, of, startWith } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { ConjunctionService } from '../../services/conjunction.service';
import { ConjunctionAlert } from '../../models/conjunction.model';

/**
 * Badge de notification pour les alertes de rapprochement.
 * Polling toutes les 30 s sur GET /alerts/unread.
 * Émet (badgeClicked) quand l'utilisateur clique sur la cloche.
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

  @Output() badgeClicked = new EventEmitter<ConjunctionAlert[]>();

  unreadAlerts: ConjunctionAlert[] = [];
  get count(): number { return this.unreadAlerts.length; }

  private readonly conjunctionService = inject(ConjunctionService);
  private readonly cdr                = inject(ChangeDetectorRef);
  private readonly destroy$           = new Subject<void>();

  ngOnInit(): void {
    // Déclenche immédiatement (startWith) puis toutes les 30 s
    interval(30_000).pipe(
      startWith(0),
      takeUntil(this.destroy$),
      switchMap(() => this.conjunctionService.getUnreadAlerts().pipe(
        catchError(() => of([] as ConjunctionAlert[]))
      ))
    ).subscribe(alerts => {
      this.unreadAlerts = alerts;
      this.cdr.markForCheck();
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
