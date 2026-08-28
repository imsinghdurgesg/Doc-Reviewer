# review-web production image. Architecture §28.1.
#
# Versions are pinned rather than floating on :lts / :alpine, per §28.1 — a floating base means the
# image that passed review is not the image that ships next month.
FROM node:22.14-alpine AS build

WORKDIR /workspace

# Dependencies first, as their own layer: package.json changes far less often than source, so a
# source edit does not re-run npm ci.
COPY package.json package-lock.json ./
# `npm ci` not `npm install` — installs exactly the lockfile, and fails if the two disagree rather
# than silently resolving something new.
RUN npm ci

COPY . .

# The quality gate runs INSIDE the image build (§31), so a type error, lint failure, or broken test
# fails `docker compose up --build` instead of shipping.
RUN npm run typecheck \
    && npm run test \
    && npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 8080

# Foreground: a container whose main process daemonises exits immediately and Docker reports it as
# healthy-then-gone.
CMD ["nginx", "-g", "daemon off;"]
