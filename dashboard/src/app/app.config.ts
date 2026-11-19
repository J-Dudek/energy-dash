import type { ApplicationConfig } from '@angular/core';
import { LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import localeFr from '@angular/common/locales/fr';

registerLocaleData(localeFr);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    { provide: LOCALE_ID, useValue: 'fr' },
  ],
};
