import {
  Component, OnDestroy, AfterViewInit,
  ViewChild, ElementRef, inject, output, ChangeDetectorRef, ChangeDetectionStrategy
} from '@angular/core';
import { DatePipe } from '@angular/common';
import * as L from 'leaflet';
import { interval, Subscription, switchMap, startWith, catchError, of } from 'rxjs';
import { OrbitService } from '../../services/orbit.service';
import { SatellitePosition } from '../../models/satellite-position.model';
import { Router } from '@angular/router';

/** Intervalle de refresh de la carte live en millisecondes */
const REFRESH_INTERVAL_MS = 60_000;

@Component({
  selector: 'app-map-live',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './map-live.component.html',
  styleUrl:    './map-live.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class MapLiveComponent implements AfterViewInit, OnDestroy {
  @ViewChild('mapContainer', { static: true }) mapContainer!: ElementRef<HTMLDivElement>;

  /** Émet le nom du satellite quand l'utilisateur clique "Voir ground track" */
  readonly satelliteSelected = output<string>();

  private readonly orbitService = inject(OrbitService);
  private readonly router       = inject(Router);
  private readonly cdr          = inject(ChangeDetectorRef);

  private map?: L.Map;
  private markerLayer?: L.LayerGroup;
  private refreshSub?: Subscription;

  satelliteCount = 0;
  lastRefresh: Date | null = null;
  loading = true;
  error: string | null = null;

  ngAfterViewInit(): void {
    this.initMap();
    this.startRefresh();
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
    this.map?.remove();
  }

  private initMap(): void {
    this.map = L.map(this.mapContainer.nativeElement, {
      center: [0, 0],
      zoom: 2,
      worldCopyJump: true
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      maxZoom: 18
    }).addTo(this.map);

    this.markerLayer = L.layerGroup().addTo(this.map);

    // Laisse le layout CSS se stabiliser avant que Leaflet calcule les dimensions
    setTimeout(() => this.map?.invalidateSize(), 0);
  }

  private startRefresh(): void {
    this.refreshSub = interval(REFRESH_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() =>
        this.orbitService.getAllPositions('stations').pipe(
          catchError(err => {
            this.error   = `Erreur de chargement : ${err.status ?? ''} ${err.message}`;
            this.loading = false;
            this.cdr.markForCheck();
            return of([]);
          })
        )
      )
    ).subscribe({
      next: (positions) => {
        if (positions.length > 0) {
          this.renderMarkers(positions);
          this.satelliteCount = positions.length;
          this.error          = null;
        }
        this.lastRefresh = new Date();
        this.loading     = false;
        this.cdr.markForCheck();
      }
    });
  }

  private renderMarkers(positions: SatellitePosition[]): void {
    if (!this.map || !this.markerLayer) {
      console.warn('[MapLive] renderMarkers appelé avant initMap — ignoré');
      return;
    }

    console.log(`[MapLive] Rendu de ${positions.length} satellites`);
    this.markerLayer.clearLayers();

    for (const sat of positions) {
      const marker = L.marker([sat.latitude, sat.longitude], {
        icon: L.divIcon({
          className: '',
          html: `<div style="
            width:10px;height:10px;
            background:#00d4ff;
            border:1.5px solid #fff;
            border-radius:50%;
            box-shadow:0 0 6px #00d4ff99;
          "></div>`,
          iconSize:   [10, 10],
          iconAnchor: [5, 5]
        })
      });

      marker.bindPopup(this.buildPopup(sat), { maxWidth: 220 });
      marker.on('popupopen', () => this.onPopupOpen(sat, marker));
      this.markerLayer.addLayer(marker);
    }
  }

  private buildPopup(sat: SatellitePosition): string {
    return `
      <div class="sat-popup">
        <strong>${sat.name}</strong>
        <table>
          <tr><td>Lat</td><td>${sat.latitude.toFixed(3)}°</td></tr>
          <tr><td>Lon</td><td>${sat.longitude.toFixed(3)}°</td></tr>
          <tr><td>Alt</td><td>${sat.altitude.toFixed(1)} km</td></tr>
          <tr><td>Epoch</td><td>${new Date(sat.epoch).toUTCString()}</td></tr>
        </table>
        <button class="popup-btn" id="track-btn-${sat.name.replace(/\s/g, '_')}">
          🛤 Voir ground track
        </button>
      </div>
    `;
  }

  private onPopupOpen(sat: SatellitePosition, marker: L.Marker): void {
    setTimeout(() => {
      const btnId = `track-btn-${sat.name.replace(/\s/g, '_')}`;
      document.getElementById(btnId)?.addEventListener('click', () => {
        this.satelliteSelected.emit(sat.name);
        marker.closePopup();
      });
    }, 50);
  }
}

