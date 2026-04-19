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
}

