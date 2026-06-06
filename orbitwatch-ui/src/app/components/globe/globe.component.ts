import {
  Component, OnDestroy, AfterViewInit,
  ViewChild, ElementRef, inject, Input,
  NgZone, ChangeDetectionStrategy, ChangeDetectorRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { interval, Subscription, switchMap, startWith, catchError, of, combineLatest } from 'rxjs';
import { GlobeService } from '../../services/globe.service';
import { SatellitePosition } from '../../models/satellite-position.model';
import { ConjunctionAlert } from '../../models/conjunction.model';
import { AlertBadgeComponent, CombinedAlerts } from '../alert-badge/alert-badge.component';
import { AlertPanelComponent } from '../alert-panel/alert-panel.component';
import { ChatComponent } from '../chat/chat.component';
import { ConjunctionService } from '../../services/conjunction.service';
import { AnomalyService } from '../../services/anomaly.service';
import { AnomalyAlert } from '../../models/satellite.model';

const REFRESH_INTERVAL_MS = 60_000;

type CesiumViewer = any;

@Component({
  selector: 'app-globe',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, RouterLinkActive, AlertBadgeComponent, AlertPanelComponent, ChatComponent],
  templateUrl: './globe.component.html',
  styleUrl: './globe.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GlobeComponent implements AfterViewInit, OnDestroy {
  @ViewChild('cesiumContainer', { static: true }) container!: ElementRef<HTMLDivElement>;
  @ViewChild('chatWidget') chatWidget?: ChatComponent;

  /** Nom du satellite à cibler via fly-to (passé depuis GlobePageComponent via query param ?satellite=) */
  @Input() targetSatellite?: string;
  /** Question à soumettre automatiquement au widget chat à l'arrivée (query param ?q=) */
  @Input() initialChatQuery?: string;

  private readonly zone        = inject(NgZone);
  readonly cdr                 = inject(ChangeDetectorRef);
  private readonly globeService = inject(GlobeService);
  private readonly conjunctionService = inject(ConjunctionService);
  private readonly anomalyService     = inject(AnomalyService);

  // ── Panel alertes ──────────────────────────────────────────────────────────
  panelOpen    = false;
  panelAlerts: CombinedAlerts = { conjunctions: [], anomalies: [] };

  private viewer?: CesiumViewer;
  private stationsSub?: Subscription;
  private cesium?: any;

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

  // ── Ground track ───────────────────────────────────────────────────────────
  groundTrackLoading   = false;
  activeGroundTrackSat: string | null = null;

  // ── Panneau satellites ────────────────────────────────────────────────────
  stations:      SatellitePosition[] = [];
  selectedSat:   SatellitePosition | null = null;
  satPanelOpen   = true;
  satFilter      = '';

  // ── Widget chat ───────────────────────────────────────────────────────────
  chatOpen   = false;
  chatWidth  = 380;
  chatHeight = 520;

  private isResizing    = false;
  private resizeDir: 'top' | 'left' | 'corner' = 'top';
  private resizeStartX  = 0;
  private resizeStartY  = 0;
  private resizeStartW  = 380;
  private resizeStartH  = 520;

  get filteredStations(): SatellitePosition[] {
    const f = this.satFilter.trim().toLowerCase();
    if (!f) return this.stations;
    return this.stations.filter(s => s.name.toLowerCase().includes(f));
  }

  /** Query param pour le lien IA d'un satellite */
  aiQuery(satName: string): string {
    return `Analyse le satellite ${satName} et ses anomalies récentes`;
  }

  // ── Références aux couches ─────────────────────────────────────────────────
  private stationLayer?: any;
  private debrisLayer?: any;
  private conjunctionLayer?: any;
  private groundTrackLayer?: any;
  private selectionHaloLayer?: any;    // anneau de sélection satellite

  /** Évite les déclenchements multiples du reset vue globale */
  private autoResetPending = false;

  ngAfterViewInit(): void {
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
          // Ouvrir le widget chat si un query param `q` a été transmis
          if (this.initialChatQuery) {
            this.openChatWithRawQuery(this.initialChatQuery);
          }
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

    Cesium.Ion.defaultAccessToken = '';

    const osmLayer = new Cesium.ImageryLayer(
      new Cesium.UrlTemplateImageryProvider({
        url:         'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
        subdomains:  ['a', 'b', 'c'],
        credit:      new Cesium.Credit('© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'),
        maximumLevel: 19,
      })
    );

    this.viewer = new Cesium.Viewer(this.container.nativeElement, {
      terrainProvider:  new Cesium.EllipsoidTerrainProvider(),
      baseLayer:        osmLayer,
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

    this.stationLayer    = new Cesium.CustomDataSource('stations');
    this.conjunctionLayer = new Cesium.CustomDataSource('conjunctions');
    this.groundTrackLayer = new Cesium.CustomDataSource('groundtrack');
    this.selectionHaloLayer = new Cesium.CustomDataSource('selectionHalo');
    this.viewer.dataSources.add(this.stationLayer);
    this.viewer.dataSources.add(this.conjunctionLayer);
    this.viewer.dataSources.add(this.groundTrackLayer);
    this.viewer.dataSources.add(this.selectionHaloLayer);

    this.debrisLayer = this.viewer.scene.primitives.add(
      new Cesium.PointPrimitiveCollection()
    );

    // Clic sur un satellite dans le globe → sélection + fly-to
    this.viewer.screenSpaceEventHandler.setInputAction((click: any) => {
      const picked = this.viewer!.scene.pick(click.position);
      if (Cesium.defined(picked) && picked.id && this.stationLayer?.entities.contains(picked.id)) {
        const satName: string = picked.id.id;
        this.zone.run(() => this.selectSatelliteByName(satName));
      }
    }, Cesium.ScreenSpaceEventType.LEFT_CLICK);

    // Dézoom → reset vue globale si altitude caméra > 10 000 km et un satellite est sélectionné
    this.viewer.camera.moveEnd.addEventListener(() => {
      if (this.autoResetPending) return;
      const altitudeM = this.viewer!.camera.positionCartographic.height;
      if (altitudeM > 10_000_000 && this.selectedSat) {
        this.autoResetPending = true;
        this.viewer!.camera.flyTo({
          destination: Cesium.Cartesian3.fromDegrees(0, 10, 22_000_000),
          orientation: { heading: 0, pitch: -Math.PI / 2, roll: 0 },
          duration: 1.5,
          complete: () => { this.autoResetPending = false; }
        });
        this.zone.run(() => {
          this.selectedSat = null;
          this.updateSelectionHalo(null);
          this.cdr.markForCheck();
        });
      }
    });
  }

  // ── Chargement des couches ─────────────────────────────────────────────────

  private loadStations(): void {
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
      this.zone.runOutsideAngular(() => {
        this.renderStations(positions);
        // Rafraîchir le halo si la position du satellite sélectionné a changé
        const updatedSat = this.selectedSat
          ? positions.find(s => s.name === this.selectedSat!.name) ?? null
          : null;
        this.updateSelectionHalo(updatedSat);
      });
      this.zone.run(() => {
        // Mettre à jour la référence selectedSat avec la nouvelle position
        if (this.selectedSat) {
          const refreshed = positions.find(s => s.name === this.selectedSat!.name);
          if (refreshed) this.selectedSat = refreshed;
        }
        this.stations     = positions;
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

    if (this.targetSatellite) {
      this.flyToSatellite(this.targetSatellite);
    }
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
      let color: any;
      if (sat.altitude < 600) {
        color = Cesium.Color.fromCssColorString('#00ff88');
      } else if (sat.altitude < 1200) {
        color = Cesium.Color.fromCssColorString('#ffa500');
      } else {
        color = Cesium.Color.fromCssColorString('#ff4444');
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
      this.conjunctionLayer.entities.add({
        polyline: {
          positions: [
            Cesium.Cartesian3.fromDegrees(alert.lon1, alert.lat1, alert.alt1 * 1000),
            Cesium.Cartesian3.fromDegrees(alert.lon2, alert.lat2, alert.alt2 * 1000),
          ],
          width:    2,
          material: new Cesium.ColorMaterialProperty(
            alert.distanceKm < 1
              ? Cesium.Color.fromCssColorString('#ff0000')
              : alert.distanceKm < 5
                ? Cesium.Color.fromCssColorString('#ff8800')
                : Cesium.Color.fromCssColorString('#ffff00')
          ),
          depthFailMaterial: new Cesium.ColorMaterialProperty(
            Cesium.Color.fromCssColorString('#ffffff55')
          ),
        }
      });
    }

    this.conjunctionLayer.show = this.showConjunctions;
  }

  // ── Panel alertes ────────────────────────────────────────────────────────

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

  // ── Panneau satellites ────────────────────────────────────────────────────

  toggleSatPanel(): void {
    this.satPanelOpen = !this.satPanelOpen;
    this.cdr.markForCheck();
  }

  onFilterChange(value: string): void {
    this.satFilter = value;
    this.cdr.markForCheck();
  }

  /** Sélection depuis la liste (clic sur un item). */
  selectSatellite(sat: SatellitePosition): void {
    if (this.selectedSat?.name === sat.name) {
      this.selectedSat = null;
      this.zone.runOutsideAngular(() => this.updateSelectionHalo(null));
    } else {
      this.selectedSat = sat;
      this.zone.runOutsideAngular(() => this.updateSelectionHalo(sat));
      this.zone.runOutsideAngular(() => this.flyToSatellite(sat.name));
    }
    this.cdr.markForCheck();
  }

  /** Sélection depuis le globe (clic sur billboard). */
  selectSatelliteByName(satName: string): void {
    const sat = this.stations.find(s => s.name === satName);
    if (!sat) return;
    if (this.selectedSat?.name === satName) {
      this.selectedSat = null;
      this.zone.runOutsideAngular(() => this.updateSelectionHalo(null));
    } else {
      this.selectedSat = sat;
      this.satPanelOpen = true;
      this.zone.runOutsideAngular(() => this.updateSelectionHalo(sat));
    }
    this.cdr.markForCheck();
  }

  /** Met à jour le halo de sélection dans CesiumJS (anneau cyan autour du satellite). */
  private updateSelectionHalo(sat: SatellitePosition | null): void {
    if (!this.selectionHaloLayer || !this.cesium) return;
    const Cesium = this.cesium;
    this.selectionHaloLayer.entities.removeAll();
    if (!sat) return;

    const pos = Cesium.Cartesian3.fromDegrees(sat.longitude, sat.latitude, sat.altitude * 1000);

    // Halo externe (glow)
    this.selectionHaloLayer.entities.add({
      position: pos,
      point: {
        pixelSize: 44,
        color:     Cesium.Color.fromCssColorString('#00d4ff18'),
        outlineColor: Cesium.Color.TRANSPARENT,
        outlineWidth: 0,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      }
    });
    // Anneau interne (contour net)
    this.selectionHaloLayer.entities.add({
      position: pos,
      point: {
        pixelSize: 24,
        color:     Cesium.Color.TRANSPARENT,
        outlineColor: Cesium.Color.fromCssColorString('#00d4ff'),
        outlineWidth: 2,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      }
    });
  }

  /** Revient à une vue globale centrée sur la Terre. */
  resetToEarthView(): void {
    if (!this.viewer || !this.cesium) return;
    this.zone.runOutsideAngular(() => {
      this.viewer!.camera.flyTo({
        destination: this.cesium.Cartesian3.fromDegrees(0, 10, 22_000_000),
        orientation: { heading: 0, pitch: -Math.PI / 2, roll: 0 },
        duration: 1.5,
      });
    });
    this.selectedSat = null;
    this.zone.runOutsideAngular(() => this.updateSelectionHalo(null));
    this.cdr.markForCheck();
  }

  // ── Widget chat ───────────────────────────────────────────────────────────

  toggleChat(): void {
    this.chatOpen = !this.chatOpen;
    this.cdr.markForCheck();
  }

  /** Ouvre le widget et soumet une question brute (depuis query param ?q= ou navigation externe). */
  openChatWithRawQuery(query: string): void {
    this.chatOpen = true;
    this.cdr.markForCheck();
    setTimeout(() => {
      if (this.chatWidget) {
        if (this.chatWidget.messages.length === 0) {
          this.chatWidget.prefillAndSend(query);
        } else {
          this.chatWidget.prefill(query);
        }
      }
    }, 200);
  }

  /** Ouvre le widget et pré-remplit + soumet une question (depuis le panel satellite). */
  openChatWithQuery(satName: string): void {
    const query = this.aiQuery(satName);
    this.chatOpen = true;
    this.cdr.markForCheck();
    // Attendre que le composant soit rendu et l'historique chargé
    setTimeout(() => {
      if (this.chatWidget) {
        if (this.chatWidget.messages.length === 0) {
          this.chatWidget.prefillAndSend(query);
        } else {
          this.chatWidget.prefill(query);
        }
      }
    }, 80);
  }

  /** Démarre un redimensionnement par drag sur le bord haut, gauche ou le coin. */
  startResize(event: MouseEvent, dir: 'top' | 'left' | 'corner'): void {
    event.preventDefault();
    event.stopPropagation();
    this.isResizing   = true;
    this.resizeDir    = dir;
    this.resizeStartX = event.clientX;
    this.resizeStartY = event.clientY;
    this.resizeStartW = this.chatWidth;
    this.resizeStartH = this.chatHeight;

    // Exécuter hors zone Angular pour les performances
    this.zone.runOutsideAngular(() => {
      const onMove = (e: MouseEvent) => {
        if (!this.isResizing) return;
        // Widget ancré en bas-droite : déplacer le bord haut vers le haut = plus grand
        const dx = this.resizeStartX - e.clientX; // positif = déplace vers la gauche = plus large
        const dy = this.resizeStartY - e.clientY; // positif = déplace vers le haut = plus grand

        let newW = this.resizeStartW;
        let newH = this.resizeStartH;

        if (dir === 'top'    || dir === 'corner') {
          newH = Math.max(320, Math.min(window.innerHeight - 140, this.resizeStartH + dy));
        }
        if (dir === 'left'   || dir === 'corner') {
          newW = Math.max(300, Math.min(window.innerWidth  - 60,  this.resizeStartW + dx));
        }

        this.chatWidth  = newW;
        this.chatHeight = newH;
        this.zone.run(() => this.cdr.markForCheck());
      };

      const onUp = () => {
        this.isResizing = false;
        document.removeEventListener('mousemove', onMove);
        document.removeEventListener('mouseup',   onUp);
      };

      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup',   onUp);
    });
  }

  // ── Ground track ─────────────────────────────────────────────────────────

  toggleGroundTrack(satName: string): void {
    if (this.groundTrackLoading) return;
    if (this.activeGroundTrackSat === satName) {
      this.clearGroundTrack();
      return;
    }

    // Retrouver la position live pour aligner l'epoch du tracé sur la position affichée
    const sat = this.stations.find(s => s.name === satName);
    if (!sat) return;

    this.clearGroundTrack();
    this.activeGroundTrackSat = satName;
    this.groundTrackLoading   = true;
    this.cdr.markForCheck();

    this.globeService.getGroundTrack(sat).pipe(
      catchError(err => {
        console.error('[Globe] Erreur ground track :', err);
        return of([] as SatellitePosition[]);
      })
    ).subscribe(positions => {
      this.zone.runOutsideAngular(() => this.renderGroundTrack(positions));
      this.zone.run(() => {
        this.groundTrackLoading = false;
        if (positions.length === 0) this.activeGroundTrackSat = null;
        this.cdr.markForCheck();
      });
    });
  }

  private renderGroundTrack(positions: SatellitePosition[]): void {
    if (!this.viewer || !this.groundTrackLayer || !this.cesium || positions.length < 2) return;
    const Cesium = this.cesium;

    this.groundTrackLayer.entities.removeAll();

    // Le satellite actif est au milieu du tracé (~index 45 sur 90 points)
    const midIndex = Math.floor(positions.length / 2);

    const pastPositions   = positions.slice(0, midIndex + 1);
    const futurePositions = positions.slice(midIndex);

    const toPts = (pts: SatellitePosition[]) =>
      pts.map(p => Cesium.Cartesian3.fromDegrees(p.longitude, p.latitude, p.altitude * 1000));

    // Trajectoire passée — tirets gris-bleu atténués
    this.groundTrackLayer.entities.add({
      polyline: {
        positions:  toPts(pastPositions),
        width:      1.5,
        material:   new Cesium.PolylineDashMaterialProperty({
          color:      Cesium.Color.fromCssColorString('#3a6a8a'),
          dashLength: 12.0,
        }),
        clampToGround: false,
      }
    });

    // Trajectoire future — tirets cyan vifs
    this.groundTrackLayer.entities.add({
      polyline: {
        positions:  toPts(futurePositions),
        width:      2,
        material:   new Cesium.PolylineDashMaterialProperty({
          color:      Cesium.Color.fromCssColorString('#00d4ff'),
          dashLength: 16.0,
        }),
        clampToGround: false,
      }
    });

    // Marqueur "position actuelle" — halo cyan pulsé sur le point central
    const midPos = positions[midIndex];
    this.groundTrackLayer.entities.add({
      position: Cesium.Cartesian3.fromDegrees(midPos.longitude, midPos.latitude, midPos.altitude * 1000),
      ellipse: {
        semiMajorAxis: 60_000,
        semiMinorAxis: 60_000,
        material:      Cesium.Color.fromCssColorString('#00d4ff33'),
        outline:       true,
        outlineColor:  Cesium.Color.fromCssColorString('#00d4ff'),
        outlineWidth:  2,
        height:        midPos.altitude * 1000,
      }
    });
  }

  clearGroundTrack(): void {
    this.groundTrackLayer?.entities.removeAll();
    this.activeGroundTrackSat = null;
    this.groundTrackLoading   = false;
    this.cdr.markForCheck();
  }

  // ── Navigation vers un satellite ─────────────────────────────────────────

  private flyToSatellite(name: string): void {
    if (!this.viewer || !this.stationLayer || !this.cesium) return;
    const entity = this.stationLayer.entities.getById(name);
    if (entity) {
      this.viewer.flyTo(entity, {
        duration: 2,
        offset: new this.cesium.HeadingPitchRange(0, -0.5, 2_000_000)
      });
    }
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

  private buildSatelliteIcon(): string {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">
      <circle cx="12" cy="12" r="5" fill="#00d4ff" stroke="white" stroke-width="1.5"/>
      <circle cx="12" cy="12" r="9" fill="none" stroke="#00d4ff55" stroke-width="1"/>
    </svg>`;
    return 'data:image/svg+xml;base64,' + btoa(svg);
  }
}
