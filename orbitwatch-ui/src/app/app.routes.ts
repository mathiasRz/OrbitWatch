import { Routes } from '@angular/router';
import { OrbitPageComponent } from './pages/orbit-page/orbit-page.component';
import { MapPageComponent } from './pages/map-page/map-page.component';
import { ConjunctionPageComponent } from './pages/conjunction-page/conjunction-page.component';
import { SatelliteProfilePageComponent } from './pages/satellite-profile-page/satellite-profile-page.component';

export const routes: Routes = [
  { path: '',                           redirectTo: 'map', pathMatch: 'full' },
  { path: 'map',                        component: MapPageComponent },
  { path: 'orbit',                      component: OrbitPageComponent },
  { path: 'conjunction',                component: ConjunctionPageComponent },
  // IMPORTANT : route statique 'byname' AVANT la route paramétrique ':noradId'
  { path: 'satellite/byname/:name',     component: SatelliteProfilePageComponent },
  { path: 'satellite/:noradId',         component: SatelliteProfilePageComponent }
];
