import {
  Component, OnDestroy, AfterViewInit,
  ViewChild, ElementRef, inject,
  NgZone, ChangeDetectionStrategy, ChangeDetectorRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { interval, Subscription, switchMap, startWith, catchError, of } from 'rxjs';
import { GlobeService } from '../../services/globe.service';
import { SatellitePosition } from '../../models/satellite-position.model';
import { ConjunctionAlert } from '../../models/conjunction.model';

const REFRESH_INTERVAL_MS = 60_000;

// Type minimal pour éviter d'importer Cesium statiquement au niveau du module
// L'import réel est dynamique dans ngAfterViewInit
type CesiumViewer = any;

@Component({
  selector: 'app-globe',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './globe.component.html',
  styleUrl: './globe.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GlobeComponent implements AfterViewInit, OnDestroy {
  @ViewChild('cesiumContainer', { static: true }) container!: ElementRef<HTMLDivElement>;

  private readonly zone        = inject(NgZone);
  private readonly cdr         = inject(ChangeDetectorRef);
  private readonly globeService = inject(GlobeService);

  private viewer?: CesiumViewer;
  private stationsSub?: Subscription;
  private cesium?: any; // module Cesium chargé dynamiquement

  // ── État des couches ───────────────────────────────────────────────────────
  showStations    = true;
  showDebris      = true;
  showConjunctions = true;

  // ── État UI ────────────────────────────────────────────────────────────────
  loading         = true;
  error: string | null = null;
  stationCount    = 0;
  debrisCount     = 0;
  conjunctionCount = 0;

  // ── Références aux couches ─────────────────────────────────────────────────
  private stationLayer?: any;        // CustomDataSource
  private debrisLayer?: any;         // PointPrimitiveCollection
  private conjunctionLayer?: any;    // CustomDataSource

  ngAfterViewInit(): void {
    // CesiumJS est importé dynamiquement pour que Esbuild l'isole dans un chunk séparé
    // et que window.CESIUM_BASE_URL soit défini avant l'évaluation du module
    (window as any)['CESIUM_BASE_URL'] = '/cesium/';

    this.zone.runOutsideAngular(async () => {
      try {
        this.cesium = await import('cesium');
        this.initViewer();
        this.zone.run(() => {
          this.loading = false;
          this.cdr.markForCheck();
          this.loadStations();
          this.loadDebris();
          this.loadConjunctions();
        });
      } catch (err: any) {
        this.zone.run(() => {
          this.error = `Erreur initialisation CesiumJS : ${err.message}`;
          this.loading = false;
          this.cdr.markForCheck();
        });
      }
    });
  }

  ngOnDestroy(): void {
    this.stationsSub?.unsubscribe();
    if (this.viewer && !this.viewer.isDestroyed()) {
      this.viewer.destroy();
    }
  }

  // ── Initialisation du viewer ───────────────────────────────────────────────

  private initViewer(): void {
    const Cesium = this.cesium;

    Cesium.Ion.defaultAccessToken = ''; // pas de token Ion (imagerie OSM)

    // Imagerie OSM via UrlTemplateImageryProvider (API stable CesiumJS 1.104+)
    const osmLayer = new Cesium.ImageryLayer(
      new Cesium.UrlTemplateImageryProvider({
        url:         'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
        subdomains:  ['a', 'b', 'c'],
        credit:      new Cesium.Credit('© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'),
        maximumLevel: 19,
      })
    );

    this.viewer = new Cesium.Viewer(this.container.nativeElement, {
      // Terrain plat WGS84 (pas de terrain payant)
      terrainProvider:  new Cesium.EllipsoidTerrainProvider(),
      // Imagerie OSM
      baseLayer:        osmLayer,
      // Désactiver les widgets non nécessaires
      animation:        false,
      timeline:         false,
      baseLayerPicker:  false,
      geocoder:         false,
      homeButton:       false,
      sceneModePicker:  false,
      navigationHelpButton: false,
      fullscreenButton: false,
      infoBox:          false,
      selectionIndicator: false,
    });

    // Initialiser les couches
    this.stationLayer    = new Cesium.CustomDataSource('stations');
    this.conjunctionLayer = new Cesium.CustomDataSource('conjunctions');
    this.viewer.dataSources.add(this.stationLayer);
    this.viewer.dataSources.add(this.conjunctionLayer);

    // PointPrimitiveCollection pour les débris (~5000 points, plus performant que EntityCollection)
    this.debrisLayer = this.viewer.scene.primitives.add(
      new Cesium.PointPrimitiveCollection()
    );
  }

  // ── Chargement des couches ─────────────────────────────────────────────────

  private loadStations(): void {
    // Polling toutes les 60 secondes
    this.stationsSub = interval(REFRESH_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() =>
        this.globeService.getStations().pipe(
          catchError(err => {
            console.error('[Globe] Erreur stations :', err);
            return of([] as SatellitePosition[]);
          })
        )
      )
    ).subscribe(positions => {
      this.zone.runOutsideAngular(() => this.renderStations(positions));
      this.zone.run(() => {
        this.stationCount = positions.length;
        this.cdr.markForCheck();
      });
    });
  }

  private renderStations(positions: SatellitePosition[]): void {
    if (!this.viewer || !this.stationLayer || !this.cesium) return;
    const Cesium = this.cesium;

    this.stationLayer.entities.removeAll();

    for (const sat of positions) {
      this.stationLayer.entities.add({
        id:       sat.name,
        position: Cesium.Cartesian3.fromDegrees(sat.longitude, sat.latitude, sat.altitude * 1000),
        billboard: {
          image: this.buildSatelliteIcon(),
          scale: 0.35,
          verticalOrigin: Cesium.VerticalOrigin.CENTER,
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
        },
        label: {
          text:             sat.name,
          font:             '11px sans-serif',
          fillColor:        Cesium.Color.fromCssColorString('#00d4ff'),
          outlineColor:     Cesium.Color.BLACK,
          outlineWidth:     2,
          style:            Cesium.LabelStyle.FILL_AND_OUTLINE,
          pixelOffset:      new Cesium.Cartesian2(0, -20),
          verticalOrigin:   Cesium.VerticalOrigin.BOTTOM,
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
        }
      });
    }

    this.stationLayer.show = this.showStations;
  }

  private loadDebris(): void {
    this.globeService.getDebris().pipe(
      catchError(err => {
        console.error('[Globe] Erreur débris :', err);
        return of([] as SatellitePosition[]);
      })
    ).subscribe(positions => {
      this.zone.runOutsideAngular(() => this.renderDebris(positions));
      this.zone.run(() => {
        this.debrisCount = positions.length;
        this.cdr.markForCheck();
      });
    });
  }

  private renderDebris(positions: SatellitePosition[]): void {
    if (!this.viewer || !this.debrisLayer || !this.cesium) return;
    const Cesium = this.cesium;

    this.debrisLayer.removeAll();

    for (const sat of positions) {
      // Code couleur par altitude : vert < 600 km / jaune 600-1200 km / rouge > 1200 km
      let color: any;
      if (sat.altitude < 600) {
        color = Cesium.Color.fromCssColorString('#00ff88');  // vert
      } else if (sat.altitude < 1200) {
        color = Cesium.Color.fromCssColorString('#ffa500');  // orange
      } else {
        color = Cesium.Color.fromCssColorString('#ff4444');  // rouge
      }

      this.debrisLayer.add({
        position: Cesium.Cartesian3.fromDegrees(sat.longitude, sat.latitude, sat.altitude * 1000),
        color,
        pixelSize: 2,
      });
    }

    this.debrisLayer.show = this.showDebris;
  }

  private loadConjunctions(): void {
    this.globeService.getConjunctionAlerts().pipe(
      catchError(err => {
        console.error('[Globe] Erreur conjunctions :', err);
        return of([] as ConjunctionAlert[]);
      })
    ).subscribe(alerts => {
      this.zone.runOutsideAngular(() => this.renderConjunctions(alerts));
      this.zone.run(() => {
        this.conjunctionCount = alerts.length;
        this.cdr.markForCheck();
      });
    });
  }

  private renderConjunctions(alerts: ConjunctionAlert[]): void {
    if (!this.viewer || !this.conjunctionLayer || !this.cesium) return;
    const Cesium = this.cesium;

    this.conjunctionLayer.entities.removeAll();

    for (const alert of alerts) {
      // Arc 3D entre les positions des deux satellites au TCA
      this.conjunctionLayer.entities.add({
        polyline: {
          positions: [
            Cesium.Cartesian3.fromDegrees(alert.lon1, alert.lat1, alert.alt1 * 1000),
            Cesium.Cartesian3.fromDegrees(alert.lon2, alert.lat2, alert.alt2 * 1000),
          ],
          width:    2,
          material: new Cesium.ColorMaterialProperty(
            alert.distanceKm < 1
              ? Cesium.Color.fromCssColorString('#ff0000')   // rouge < 1 km
              : alert.distanceKm < 5
                ? Cesium.Color.fromCssColorString('#ff8800') // orange 1-5 km
                : Cesium.Color.fromCssColorString('#ffff00') // jaune > 5 km
          ),
          depthFailMaterial: new Cesium.ColorMaterialProperty(
            Cesium.Color.fromCssColorString('#ffffff55')
          ),
        }
      });
    }

    this.conjunctionLayer.show = this.showConjunctions;
  }

  // ── Toggles couches ───────────────────────────────────────────────────────

  toggleStations(): void {
    this.showStations = !this.showStations;
    if (this.stationLayer) this.stationLayer.show = this.showStations;
    this.cdr.markForCheck();
  }

  toggleDebris(): void {
    this.showDebris = !this.showDebris;
    if (this.debrisLayer) this.debrisLayer.show = this.showDebris;
    this.cdr.markForCheck();
  }

  toggleConjunctions(): void {
    this.showConjunctions = !this.showConjunctions;
    if (this.conjunctionLayer) this.conjunctionLayer.show = this.showConjunctions;
    this.cdr.markForCheck();
  }

  // ── Utilitaires ───────────────────────────────────────────────────────────

  /** Génère une icône SVG satellite encodée en base64 pour le billboard CesiumJS */
  private buildSatelliteIcon(): string {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">
      <circle cx="12" cy="12" r="5" fill="#00d4ff" stroke="white" stroke-width="1.5"/>
      <circle cx="12" cy="12" r="9" fill="none" stroke="#00d4ff55" stroke-width="1"/>
    </svg>`;
    return 'data:image/svg+xml;base64,' + btoa(svg);
  }
}


