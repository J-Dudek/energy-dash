import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Logo permanent de la barre du haut : un clic ramène à l'accueil (et en haut de la page si on y est déjà). */
@Component({
  selector: 'app-brand-link',
  imports: [RouterLink],
  template: `
    <a routerLink="/" class="brand-link" aria-label="Accueil Conso maison" (click)="toTop()">
      <img src="icons/logo.png" alt="" width="30" height="44" />
    </a>
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: none;
    }

    .brand-link {
      display: inline-flex;
      padding: 4px 6px;
      border-radius: 10px;
      transition: background 0.15s;

      &:hover {
        background: var(--surface-hover);
      }

      &:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: 2px;
      }
    }

    img {
      display: block;
      height: 44px;
      width: auto;
    }
  `,
})
export class BrandLink {
  protected toTop(): void {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
}
