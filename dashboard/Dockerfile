FROM node:26-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
ENV NG_CLI_ANALYTICS=false
# --ignore-scripts : aucune dépendance n'a besoin d'exécuter du code à l'installation, autant fermer cette porte (chaîne d'approvisionnement)
RUN npm ci --ignore-scripts
COPY . .
RUN npx ng build --configuration production

FROM nginx:1.30-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/dashboard/browser /usr/share/nginx/html
# config.json est régénéré au démarrage à partir des variables d'environnement
COPY docker-entrypoint.d/40-config-json.sh /docker-entrypoint.d/40-config-json.sh
RUN chmod +x /docker-entrypoint.d/40-config-json.sh
EXPOSE 80
