import {
  Component, OnDestroy, AfterViewInit,
  ViewChild, ElementRef, inject, output, ChangeDetectorRef, ChangeDetectionStrategy
} from '@angular/core';
import { DatePipe } from '@angular/common';
import * as L from 'leaflet';
import 'leaflet.heat';
import { interval, Subscription, switchMap, startWith, catchError, of } from 'rxjs';
import { OrbitService } from '../../services/orbit.service';
import { DebrisHeatmapService } from '../../services/debris-heatmap.service';
import { SatellitePosition } from '../../models/satellite-position.model';
import { HeatmapCell } from '../../models/heatmap-cell.model';
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

  private readonly orbitService       = inject(OrbitService);
  private readonly heatmapService     = inject(DebrisHeatmapService);
  private readonly router             = inject(Router);
  private readonly cdr                = inject(ChangeDetectorRef);

  private map?: L.Map;
  private markerLayer?: L.LayerGroup;
  private heatLayer?: any;
  private refreshSub?: Subscription;

  satelliteCount   = 0;
  lastRefresh: Date | null = null;
  loading          = true;
  error: string | null = null;
  heatmapActive    = false;
  heatmapLoading   = false;

  ngAfterViewInit(): void {
    this.initMap();
    this.startRefresh();
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
    this.map?.remove();
  }

  // ── Toggle heatmap ────────────────────────────────────────────────────────

  toggleHeatmap(): void {
    if (this.heatmapActive) {
      this.removeHeatLayer();
      this.heatmapActive = false;
      this.cdr.markForCheck();
    } else {
      this.loadHeatmap();
    }
  }

  private loadHeatmap(): void {
    this.heatmapLoading = true;
    this.cdr.markForCheck();

    this.heatmapService.getHeatmap().pipe(
      catchError(err => {
        console.error('[MapLive] Erreur heatmap :', err);
        this.heatmapLoading = false;
        this.error = `Heatmap indisponible : ${err.status ?? ''} ${err.message}`;
        this.cdr.markForCheck();
        return of([] as HeatmapCell[]);
      })
    ).subscribe(cells => {
      this.heatmapLoading = false;
      if (cells.length === 0) {
        console.warn('[MapLive] Heatmap vide — le catalogue debris est-il chargé côté backend ? (tle.celestrak.catalogs=stations,debris)');
        this.error = 'Heatmap vide — catalogue debris non chargé (redémarrer le backend)';
        this.cdr.markForCheck();
        return;
      }
      this.error = null;
      this.renderHeatLayer(cells);
      this.heatmapActive = true;
      this.cdr.markForCheck();
    });
  }

  private renderHeatLayer(cells: HeatmapCell[]): void {
    if (!this.map) return;
    this.removeHeatLayer();

    // Chaque cellule représente une BANDE de latitude (Nord ET Sud, les orbites sont symétriques).
    // On réplique chaque point toutes les 20° de longitude pour rendre la bande visible
    // sur toute la largeur de la carte (proxy de densité, pas des positions réelles).
    const STEP_LNG = 20;
    const points: [number, number, number][] = [];

    for (const c of cells) {
      for (let lng = -180; lng <= 180; lng += STEP_LNG) {
        // Bande Nord  (ex : +75°)
        points.push([c.latBandDeg, lng, c.count]);
        // Bande Sud symétrique (ex : -75°) — même densité, orbite couvre les deux hémisphères
        if (c.latBandDeg !== 0) {
          points.push([-c.latBandDeg, lng, c.count]);
        }
      }
    }

    const maxCount = Math.max(...cells.map(c => c.count), 1);

    this.heatLayer = (L as any).heatLayer(points, {
      radius:     30,
      blur:       20,
      max:        maxCount,
      minOpacity: 0.3,
      gradient:   { 0.3: '#00d4ff', 0.6: '#ffa500', 1.0: '#ff0000' }
    }).addTo(this.map);
  }

  private removeHeatLayer(): void {
    if (this.heatLayer && this.map) {
      this.map.removeLayer(this.heatLayer);
      this.heatLayer = undefined;
    }
  }

  // ── Carte + polling satellites ─────────────────────────────────────────────

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
    if (!this.map || !this.markerLayer) return;
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
    const safeName = sat.name.replace(/\s/g, '_');
    return `
      <div class="sat-popup">
        <strong>${sat.name}</strong>
        <table>
          <tr><td>Lat</td><td>${sat.latitude.toFixed(3)}°</td></tr>
          <tr><td>Lon</td><td>${sat.longitude.toFixed(3)}°</td></tr>
          <tr><td>Alt</td><td>${sat.altitude.toFixed(1)} km</td></tr>
          <tr><td>Epoch</td><td>${new Date(sat.epoch).toUTCString()}</td></tr>
        </table>
        <div style="display:flex;gap:6px;margin-top:6px;">
          <button class="popup-btn" id="track-btn-${safeName}">
            Ground track
          </button>
          <button class="popup-btn popup-btn-profile" id="profile-btn-${safeName}">
            Profil
          </button>
        </div>
      </div>
    `;
  }

  private onPopupOpen(sat: SatellitePosition, marker: L.Marker): void {
    setTimeout(() => {
      const safeName = sat.name.replace(/\s/g, '_');
      document.getElementById(`track-btn-${safeName}`)?.addEventListener('click', () => {
        this.satelliteSelected.emit(sat.name);
        marker.closePopup();
      });
      document.getElementById(`profile-btn-${safeName}`)?.addEventListener('click', () => {
        this.router.navigate(['/satellite', 'byname', sat.name]);
        marker.closePopup();
      });
    }, 50);
  }
}

