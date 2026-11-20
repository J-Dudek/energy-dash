FROM node:26-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
ENV NG_CLI_ANALYTICS=false
# --ignore-scripts : aucune dépendance n'a besoin d'exécuter du code à l'installation, autant fermer cette porte (chaîne d'approvisionnement)
RUN npm ci --ignore-scripts
COPY . .
RUN npx ng build --configuration production

# Variante « unprivileged » : nginx tourne en utilisateur 101 (jamais root), sur le port 8080, sans aucune capability.
FROM nginxinc/nginx-unprivileged:1.31-alpine
COPY --chown=0:0 --chmod=644 nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chmod=a+rX /src/dist/dashboard/browser /usr/share/nginx/html
# config.json est régénéré au démarrage à partir des variables d'environnement.
# Modes forcés ici (755/644) : ils ne dépendent pas des droits du fichier sur l'hôte (copie vers le NAS, zip, umask).
COPY --chown=0:0 --chmod=755 docker-entrypoint.d/40-config-json.sh /docker-entrypoint.d/40-config-json.sh
EXPOSE 8080
# Utilisateur de l'image (nginx, uid 101) : explicite pour que le contrôle soit lisible et vérifiable.
USER 101

