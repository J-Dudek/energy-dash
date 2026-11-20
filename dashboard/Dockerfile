FROM node:26-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
ENV NG_CLI_ANALYTICS=false
# --ignore-scripts : aucune dépendance n'a besoin d'exécuter du code à l'installation, autant fermer cette porte (chaîne d'approvisionnement)
RUN npm ci --ignore-scripts
COPY . .
RUN npx ng build --configuration production
# Les fichiers de public/ (sw.js, manifest, icônes) gardent des droits issus du checkout (ACL Synology) qui les rendent
# illisibles pour nginx (uid 101) : 403 « Permission denied ». On normalise : fichiers 644, dossiers 755.
RUN find dist/dashboard/browser -type d -exec chmod 755 {} + && find dist/dashboard/browser -type f -exec chmod 644 {} +

# Variante « unprivileged » : nginx tourne en utilisateur 101 (jamais root), sur le port 8080, sans aucune capability.
FROM nginxinc/nginx-unprivileged:1.31-alpine
COPY --chown=0:0 nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/dashboard/browser /usr/share/nginx/html
# config.json est régénéré au démarrage à partir des variables d'environnement (script exécutable dans git : 755)
COPY docker-entrypoint.d/40-config-json.sh /docker-entrypoint.d/40-config-json.sh
# Le contexte de build vient d'un dossier à ACL (Synology) : les droits des fichiers copiés ne sont pas fiables.
# On les fixe explicitement (en root, le temps de l'instruction) : config lisible, script exécutable.
USER 0
RUN chmod 644 /etc/nginx/conf.d/default.conf \
 && chmod 755 /docker-entrypoint.d/40-config-json.sh
EXPOSE 8080
# Utilisateur de l'image (nginx, uid 101) : explicite pour que le contrôle soit lisible et vérifiable.
USER 101

