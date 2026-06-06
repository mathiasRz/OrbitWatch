import { Routes } from '@angular/router';
import { MapPageComponent } from './pages/map-page/map-page.component';
import { SatelliteProfilePageComponent } from './pages/satellite-profile-page/satellite-profile-page.component';

export const routes: Routes = [
  { path: '',                           redirectTo: 'globe', pathMatch: 'full' },
  { path: 'map',                        component: MapPageComponent },
  { path: 'satellite/byname/:name',     component: SatelliteProfilePageComponent },
  { path: 'satellite/:noradId',         component: SatelliteProfilePageComponent },
  { path: 'globe', loadComponent: () => import('./pages/globe-page/globe-page.component').then(m => m.GlobePageComponent) },
  { path: '**',                         redirectTo: 'globe' }
];
