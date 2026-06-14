import {
  Component, OnDestroy, AfterViewInit,
  ViewChild, ElementRef, inject, Input,
  NgZone, ChangeDetectionStrategy, ChangeDetectorRef
} from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { interval, Subscription, switchMap, startWith, catchError, of, combineLatest } from 'rxjs';
import { GlobeService } from '../../services/globe.service';
import { OrbitService } from '../../services/orbit.service';
import { SatellitePosition } from '../../models/satellite-position.model';
import { CoOrbitalGroup } from '../../models/co-orbital-group.model';
import { ConjunctionAlert } from '../../models/conjunction.model';
import { ChatComponent } from '../chat/chat.component';
import { AnomalyService } from '../../services/anomaly.service';
import { AnomalyAlert } from '../../models/satellite.model';

const REFRESH_INTERVAL_MS = 60_000;

type CesiumViewer = any;

@Component({
  selector: 'app-globe',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, RouterLinkActive, ChatComponent, DatePipe],
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

  private readonly zone         = inject(NgZone);
  readonly cdr                  = inject(ChangeDetectorRef);
  private readonly globeService = inject(GlobeService);
  private readonly orbitService = inject(OrbitService);
  private readonly anomalyService = inject(AnomalyService);

  // ── Panels de droite (mutuellement exclusifs) ─────────────────────────────

  private viewer?: CesiumViewer;
  private stationsSub?: Subscription;
  private cesium?: any;

  // ── État des couches ───────────────────────────────────────────────────────
  showStations    = true;
  showDebris      = true;
  showConjunctions = true;

  // ── État UI ────────────────────────────────────────────────────────────────
  loading      = true;
  error: string | null = null;
  stationCount = 0;
  debrisCount  = 0;

  // ── Groupes co-orbitaux ───────────────────────────────────────────────────
  coOrbitalGroups: CoOrbitalGroup[] = [];
  /** Map nom satellite → groupe co-orbital */
  coOrbitalMap    = new Map<string, CoOrbitalGroup>();

  // ── Panneau conjonctions ──────────────────────────────────────────────────
  conjPanelOpen      = false;
  conjunctions: ConjunctionAlert[] = [];
  conjunctionCount   = 0;
  activeConjunctionId: number | null = null;
  conjTrackLoading   = false;
  conjTrackStatus: { sat1: number; sat2: number } | null = null;
  /** Map nom satellite → conjonctions impliquant ce satellite */
  conjMapBySat = new Map<string, ConjunctionAlert[]>();

  // ── Panneau anomalies ─────────────────────────────────────────────────────
  anomalyPanelOpen = false;
  anomalies: AnomalyAlert[]  = [];
  anomalyCount     = 0;
  /** Map nom satellite → anomalies de ce satellite */
  anomalyMap = new Map<string, AnomalyAlert[]>();

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
  private conjHighlightLayer?: any;   // trajectoires + ligne lors d'une sélection
  private coOrbitalLayer?: any;       // liaisons entre satellites co-orbitaux
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
          this.loadAnomalies();
          this.loadCoOrbitalGroups();
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
    this.conjHighlightLayer = new Cesium.CustomDataSource('conjHighlight');
    this.coOrbitalLayer   = new Cesium.CustomDataSource('coOrbital');
    this.groundTrackLayer = new Cesium.CustomDataSource('groundtrack');
    this.selectionHaloLayer = new Cesium.CustomDataSource('selectionHalo');
    this.viewer.dataSources.add(this.stationLayer);
    this.viewer.dataSources.add(this.conjunctionLayer);
    this.viewer.dataSources.add(this.conjHighlightLayer);
    this.viewer.dataSources.add(this.coOrbitalLayer);
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
        // Rafraîchir les liaisons co-orbitales avec les nouvelles positions
        this.renderCoOrbitalLinks();
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
      // Construire la map satellite → conjonctions
      const conjMap = new Map<string, ConjunctionAlert[]>();
      for (const c of alerts) {
        for (const name of [c.nameSat1, c.nameSat2]) {
          const list = conjMap.get(name) ?? [];
          list.push(c);
          conjMap.set(name, list);
        }
      }
      this.zone.runOutsideAngular(() => this.renderConjunctions(alerts));
      this.zone.run(() => {
        this.conjunctions     = alerts;
        this.conjunctionCount = alerts.length;
        this.conjMapBySat     = conjMap;
        this.cdr.markForCheck();
      });
    });
  }

  /** Charge toutes les anomalies récentes et construit la map par satellite. */
  private loadAnomalies(): void {
    this.anomalyService.getAlerts({ size: 200 }).pipe(
      catchError(err => {
        console.warn('[Globe] Erreur anomalies :', err);
        return of({ content: [] as AnomalyAlert[], totalElements: 0, totalPages: 0, number: 0, size: 0 });
      })
    ).subscribe(page => {
      const alerts = page.content ?? [];
      const map = new Map<string, AnomalyAlert[]>();
      for (const a of alerts) {
        const list = map.get(a.satelliteName) ?? [];
        list.push(a);
        map.set(a.satelliteName, list);
      }
      this.zone.run(() => {
        this.anomalies    = alerts;
        this.anomalyCount = alerts.length;
        this.anomalyMap   = map;
        this.cdr.markForCheck();
      });
    });
  }

  /** Charge les groupes co-orbitaux et met à jour la couche de liaison sur le globe. */
  private loadCoOrbitalGroups(): void {
    this.orbitService.getCoOrbitalGroups('stations').pipe(
      catchError(err => {
        console.warn('[Globe] Erreur co-orbital groups :', err);
        return of([] as CoOrbitalGroup[]);
      })
    ).subscribe(groups => {
      // Construire la map nom → groupe pour un accès O(1)
      const map = new Map<string, CoOrbitalGroup>();
      for (const g of groups) {
        for (const name of g.members) {
          map.set(name, g);
        }
      }
      this.zone.run(() => {
        this.coOrbitalGroups = groups;
        this.coOrbitalMap    = map;
        this.cdr.markForCheck();
      });
    });
  }

  /**
   * Redessine les lignes de liaison entre satellites co-orbitaux en utilisant
   * les positions actuelles chargées dans {@code this.stations}.
   * Appelé après chaque refresh des positions.
   */
  private renderCoOrbitalLinks(): void {
    if (!this.viewer || !this.coOrbitalLayer || !this.cesium || this.coOrbitalGroups.length === 0) return;
    const Cesium = this.cesium;
    this.coOrbitalLayer.entities.removeAll();

    for (const group of this.coOrbitalGroups) {
      // Récupère les positions connues pour les membres de ce groupe
      const positions = group.members
        .map(name => this.stations.find(s => s.name === name))
        .filter((s): s is SatellitePosition => s !== undefined);

      if (positions.length < 2) continue;

      // Dessine une ligne entre chaque paire consécutive (chaîne)
      for (let i = 0; i < positions.length - 1; i++) {
        const p1 = positions[i];
        const p2 = positions[i + 1];
        this.coOrbitalLayer.entities.add({
          polyline: {
            positions: [
              Cesium.Cartesian3.fromDegrees(p1.longitude, p1.latitude, p1.altitude * 1000),
              Cesium.Cartesian3.fromDegrees(p2.longitude, p2.latitude, p2.altitude * 1000),
            ],
            width: 1,
            material: new Cesium.ColorMaterialProperty(
              Cesium.Color.fromCssColorString('#ffffff22')
            ),
            depthFailMaterial: new Cesium.ColorMaterialProperty(
              Cesium.Color.fromCssColorString('#ffffff11')
            ),
          }
        });
      }
    }
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
    this.conjPanelOpen    = !this.conjPanelOpen;
    this.showConjunctions = this.conjPanelOpen;
    if (this.conjunctionLayer) this.conjunctionLayer.show = this.showConjunctions;
    if (this.conjPanelOpen) this.anomalyPanelOpen = false; // mutuellement exclusifs
    this.cdr.markForCheck();
  }

  toggleAnomalies(): void {
    this.anomalyPanelOpen = !this.anomalyPanelOpen;
    if (this.anomalyPanelOpen) this.conjPanelOpen = false; // mutuellement exclusifs
    this.cdr.markForCheck();
  }

  // ── Helpers anomalies ────────────────────────────────────────────────────

  anomalyTypeLabel(type: string): string {
    const labels: Record<string, string> = {
      'ALTITUDE_CHANGE':    '↕ Altitude',
      'INCLINATION_CHANGE': '↗ Inclinaison',
      'RAAN_DRIFT':         '↻ Dérive RAAN',
      'ECCENTRICITY_CHANGE':'⬡ Excentricité',
      'STATISTICAL':        '📊 Statistique',
    };
    return labels[type] ?? type;
  }

  anomalySeverityClass(severity: string): string {
    if (severity === 'HIGH')   return 'anomaly-item--high';
    if (severity === 'MEDIUM') return 'anomaly-item--medium';
    return 'anomaly-item--low';
  }

  /** Vol vers le satellite concerné par une anomalie. */
  flyToAnomalySat(satName: string): void {
    const sat = this.stations.find(s => s.name === satName);
    if (!sat) return;
    this.selectedSat  = sat;
    this.satPanelOpen = true;
    this.zone.runOutsideAngular(() => {
      this.updateSelectionHalo(sat);
      this.flyToSatellite(satName);
    });
    this.cdr.markForCheck();
  }

  /** Sélectionne une conjonction : charge les deux tracés orbitaux + met en avant la ligne. */
  selectConjunction(alert: ConjunctionAlert): void {
    // Désélection si on clique sur la même
    if (this.activeConjunctionId === alert.id) {
      this.activeConjunctionId = null;
      this.conjTrackStatus     = null;
      this.conjHighlightLayer?.entities.removeAll();
      this.cdr.markForCheck();
      return;
    }

    this.activeConjunctionId = alert.id;
    this.conjTrackLoading    = true;
    this.conjTrackStatus     = null;
    this.conjHighlightLayer?.entities.removeAll();
    this.cdr.markForCheck();

    // Epoch centré sur le TCA → satellite au milieu du tracé
    const tcaMs      = new Date(alert.tca).getTime();
    const epochStart = new Date(tcaMs - 45 * 60 * 1000).toISOString();

    combineLatest([
      this.orbitService.getGroundTrack({ name: alert.nameSat1, epoch: epochStart, duration: 90, step: 60 })
        .pipe(catchError(err => { console.warn(`[Conj] Tracé ${alert.nameSat1} échec :`, err.status ?? err.message); return of([] as SatellitePosition[]); })),
      this.orbitService.getGroundTrack({ name: alert.nameSat2, epoch: epochStart, duration: 90, step: 60 })
        .pipe(catchError(err => { console.warn(`[Conj] Tracé ${alert.nameSat2} échec :`, err.status ?? err.message); return of([] as SatellitePosition[]); })),
    ]).subscribe(([track1, track2]) => {
      console.info(`[Conj] ${alert.nameSat1} → ${track1.length} pts | ${alert.nameSat2} → ${track2.length} pts`);
      if (track1.length > 0 && track2.length > 0) {
        const midPt1 = track1[Math.floor(track1.length / 2)];
        const midPt2 = track2[Math.floor(track2.length / 2)];
        console.info(`[Conj] Positions au TCA — ${alert.nameSat1}: (${midPt1.latitude.toFixed(2)}, ${midPt1.longitude.toFixed(2)}, ${midPt1.altitude.toFixed(0)} km) | ${alert.nameSat2}: (${midPt2.latitude.toFixed(2)}, ${midPt2.longitude.toFixed(2)}, ${midPt2.altitude.toFixed(0)} km)`);
      }
      this.zone.runOutsideAngular(() => this.renderConjunctionHighlight(alert, track1, track2));
      this.zone.run(() => {
        this.conjTrackLoading = false;
        this.conjTrackStatus  = { sat1: track1.length, sat2: track2.length };
        this.cdr.markForCheck();
      });
    });

    // Vol immédiat vers le point de conjonction
    this.flyToConjunction(alert);
  }

  private renderConjunctionHighlight(
    alert: ConjunctionAlert,
    track1: SatellitePosition[],
    track2: SatellitePosition[]
  ): void {
    if (!this.viewer || !this.conjHighlightLayer || !this.cesium) return;
    const Cesium = this.cesium;
    this.conjHighlightLayer.entities.removeAll();

    const toPts = (pts: SatellitePosition[]) =>
      pts.map(p => Cesium.Cartesian3.fromDegrees(p.longitude, p.latitude, p.altitude * 1000));

    /** Ajoute des marqueurs (losanges) tous les STEP points pour différencier les tracés */
    const addMarkers = (pts: SatellitePosition[], color: string, step = 6) => {
      for (let i = 0; i < pts.length; i += step) {
        this.conjHighlightLayer!.entities.add({
          position: Cesium.Cartesian3.fromDegrees(pts[i].longitude, pts[i].latitude, pts[i].altitude * 1000),
          point: {
            pixelSize: 5,
            color:        Cesium.Color.fromCssColorString(color),
            outlineColor: Cesium.Color.fromCssColorString('#00000066'),
            outlineWidth: 1,
            disableDepthTestDistance: Number.POSITIVE_INFINITY,
          }
        });
      }
    };

    // ── Tracé satellite 1 : cyan ───────────────────────────────────────────
    if (track1.length > 1) {
      const mid = Math.floor(track1.length / 2);
      this.conjHighlightLayer.entities.add({
        polyline: {
          positions: toPts(track1.slice(0, mid + 1)),
          width: 1.5,
          material: new Cesium.PolylineDashMaterialProperty({
            color: Cesium.Color.fromCssColorString('#006888'), dashLength: 12
          }),
        }
      });
      this.conjHighlightLayer.entities.add({
        polyline: {
          positions: toPts(track1.slice(mid)),
          width: 2.5,
          material: new Cesium.PolylineDashMaterialProperty({
            color: Cesium.Color.fromCssColorString('#00d4ff'), dashLength: 16
          }),
        }
      });
      addMarkers(track1, '#00d4ff');
    }

    // ── Tracé satellite 2 : magenta ───────────────────────────────────────
    if (track2.length > 1) {
      const mid = Math.floor(track2.length / 2);
      this.conjHighlightLayer.entities.add({
        polyline: {
          positions: toPts(track2.slice(0, mid + 1)),
          width: 1.5,
          material: new Cesium.PolylineDashMaterialProperty({
            color: Cesium.Color.fromCssColorString('#882266'), dashLength: 12
          }),
        }
      });
      this.conjHighlightLayer.entities.add({
        polyline: {
          positions: toPts(track2.slice(mid)),
          width: 2.5,
          material: new Cesium.PolylineDashMaterialProperty({
            color: Cesium.Color.fromCssColorString('#ff44cc'), dashLength: 16
          }),
        }
      });
      addMarkers(track2, '#ff44cc');
    }

    // ── Ligne de conjonction mise en avant ────────────────────────────────
    const pos1 = Cesium.Cartesian3.fromDegrees(alert.lon1, alert.lat1, alert.alt1 * 1000);
    const pos2 = Cesium.Cartesian3.fromDegrees(alert.lon2, alert.lat2, alert.alt2 * 1000);

    const lineColor = alert.distanceKm < 1
      ? '#ff2222'
      : alert.distanceKm < 5 ? '#ff8800' : '#ffcc00';

    // Halo blanc derrière la ligne
    this.conjHighlightLayer.entities.add({
      polyline: {
        positions: [pos1, pos2],
        width: 8,
        material: new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#ffffff18')),
        depthFailMaterial: new Cesium.ColorMaterialProperty(Cesium.Color.TRANSPARENT),
      }
    });
    // Ligne principale colorée par sévérité
    this.conjHighlightLayer.entities.add({
      polyline: {
        positions: [pos1, pos2],
        width: 3,
        material: new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString(lineColor)),
        depthFailMaterial: new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString(lineColor + '66')),
      }
    });

    // ── Points satellites aux extrémités ─────────────────────────────────
    // Sat1 — cyan
    this.conjHighlightLayer.entities.add({
      position: pos1,
      point: {
        pixelSize: 12,
        color:        Cesium.Color.fromCssColorString('#00d4ff'),
        outlineColor: Cesium.Color.WHITE,
        outlineWidth: 2,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
      label: {
        text:         alert.nameSat1,
        font:         '11px sans-serif',
        fillColor:    Cesium.Color.fromCssColorString('#00d4ff'),
        outlineColor: Cesium.Color.BLACK,
        outlineWidth: 2,
        style:        Cesium.LabelStyle.FILL_AND_OUTLINE,
        pixelOffset:  new Cesium.Cartesian2(0, -20),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      }
    });
    // Sat2 — magenta
    this.conjHighlightLayer.entities.add({
      position: pos2,
      point: {
        pixelSize: 12,
        color:        Cesium.Color.fromCssColorString('#ff44cc'),
        outlineColor: Cesium.Color.WHITE,
        outlineWidth: 2,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
      label: {
        text:         alert.nameSat2,
        font:         '11px sans-serif',
        fillColor:    Cesium.Color.fromCssColorString('#ff44cc'),
        outlineColor: Cesium.Color.BLACK,
        outlineWidth: 2,
        style:        Cesium.LabelStyle.FILL_AND_OUTLINE,
        pixelOffset:  new Cesium.Cartesian2(0, -20),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      }
    });
  }

  /** Vole face à la conjonction : caméra perpendiculaire au segment, vue de côté. */
  flyToConjunction(alert: ConjunctionAlert): void {
    if (!this.viewer || !this.cesium) return;
    const Cesium = this.cesium;

    this.zone.runOutsideAngular(() => {
      const pos1 = Cesium.Cartesian3.fromDegrees(alert.lon1, alert.lat1, alert.alt1 * 1000);
      const pos2 = Cesium.Cartesian3.fromDegrees(alert.lon2, alert.lat2, alert.alt2 * 1000);

      // Midpoint entre les deux satellites
      const mid = new Cesium.Cartesian3(
        (pos1.x + pos2.x) / 2,
        (pos1.y + pos2.y) / 2,
        (pos1.z + pos2.z) / 2
      );

      // Vecteur de conjonction normalisé (sat1 → sat2)
      const conjVec = Cesium.Cartesian3.normalize(
        Cesium.Cartesian3.subtract(pos2, pos1, new Cesium.Cartesian3()),
        new Cesium.Cartesian3()
      );

      // Radiale terrestre au midpoint (centre Terre → midpoint) = "up" local
      const radial = Cesium.Cartesian3.normalize(
        Cesium.Cartesian3.clone(mid),
        new Cesium.Cartesian3()
      );

      // Vecteur latéral ⊥ au segment ET à la radiale
      // → la caméra sera sur le côté, regardant le segment de face
      const sideVec = Cesium.Cartesian3.normalize(
        Cesium.Cartesian3.cross(conjVec, radial, new Cesium.Cartesian3()),
        new Cesium.Cartesian3()
      );

      // Distance caméra : assez grande pour voir les deux tracés (min 800 km)
      const conjDistM = Cesium.Cartesian3.distance(pos1, pos2);
      const camDistM  = Math.max(conjDistM * 8, 800_000);

      // Position caméra : midpoint + décalage latéral
      const camPos = Cesium.Cartesian3.add(
        mid,
        Cesium.Cartesian3.multiplyByScalar(sideVec, camDistM, new Cesium.Cartesian3()),
        new Cesium.Cartesian3()
      );

      // Direction de regard : caméra → midpoint
      const direction = Cesium.Cartesian3.normalize(
        Cesium.Cartesian3.subtract(mid, camPos, new Cesium.Cartesian3()),
        new Cesium.Cartesian3()
      );

      this.viewer!.camera.flyTo({
        destination: camPos,
        orientation: { direction, up: radial },
        duration: 2,
      });
    });
  }

  conjSeverityLabel(distKm: number): string {
    if (distKm < 1)  return 'CRITIQUE';
    if (distKm < 5)  return 'ÉLEVÉ';
    return 'MODÉRÉ';
  }

  conjSeverityClass(distKm: number): string {
    if (distKm < 1)  return 'conj-item--critical';
    if (distKm < 5)  return 'conj-item--high';
    return 'conj-item--moderate';
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
