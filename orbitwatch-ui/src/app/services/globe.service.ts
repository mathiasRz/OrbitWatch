import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { OrbitService } from './orbit.service';
import { ConjunctionService } from './conjunction.service';
import { SatellitePosition } from '../models/satellite-position.model';
import { ConjunctionAlert } from '../models/conjunction.model';

@Injectable({ providedIn: 'root' })
export class GlobeService {
  private readonly orbitService       = inject(OrbitService);
  private readonly conjunctionService = inject(ConjunctionService);

  /** Positions instantanées des stations spatiales actives (polling recommandé 60s) */
  getStations(): Observable<SatellitePosition[]> {
    return this.orbitService.getAllPositions('stations');
  }

  /** Positions instantanées des débris (chargement unique recommandé) */
  getDebris(): Observable<SatellitePosition[]> {
    return this.orbitService.getAllPositions('debris');
  }

  /** Alertes de conjunction actives (non acquittées) */
  getConjunctionAlerts(): Observable<ConjunctionAlert[]> {
    return this.conjunctionService.getAlerts(0, 100).pipe(
      map(page => page.content ?? [])
    );
  }

  /**
   * Ground track orbital centré sur la position live du satellite.
   * L'epoch du satellite live est décalé de –45 min pour que le satellite
   * apparaisse au milieu de son tracé (passé + futur visibles).
   */
  getGroundTrack(sat: SatellitePosition): Observable<SatellitePosition[]> {
    // Centrer : partir 45 min avant la position live pour avoir 45 min de passé + 45 min de futur
    const epochMs    = new Date(sat.epoch).getTime();
    const epochStart = new Date(epochMs - 45 * 60 * 1000).toISOString();
    return this.orbitService.getGroundTrack({
      name:     sat.name,
      duration: 90,
      step:     60,
      epoch:    epochStart
    });
  }
}

