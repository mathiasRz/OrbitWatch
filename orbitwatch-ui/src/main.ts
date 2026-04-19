import * as L from 'leaflet';
// leaflet.heat (et tout plugin Leaflet) s'enregistre sur le L global.
// On l'expose sur window ICI, avant que n'importe quel composant charge ses imports.
(window as any)['L'] = L;

import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
