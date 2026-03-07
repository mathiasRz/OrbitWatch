import { Routes } from '@angular/router';
import { OrbitPageComponent } from './pages/orbit-page/orbit-page.component';

export const routes: Routes = [
  { path: '', redirectTo: 'orbit', pathMatch: 'full' },
  { path: 'orbit', component: OrbitPageComponent }
];
