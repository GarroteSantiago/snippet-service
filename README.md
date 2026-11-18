# snippet-service

Orquestador: snippets, tests, reglas por usuario y `compliance`. Es el único servicio expuesto detrás de NGINX.

Parte de Snippet Searcher. Contrato de la API: `contracts/snippet-service.openapi.yaml` en el repo de documentación.

## Requisitos

- [Nix](https://nixos.org) con flakes. El dev shell trae JDK 21, Gradle, docker-compose y utilidades.
- Docker (para Testcontainers y para correr el sistema completo). En NixOS: `virtualisation.docker.enable = true;` en la configuración del sistema.
- Opcional: [direnv](https://direnv.net) para entrar al dev shell y cargar `.env` al hacer `cd` (`direnv allow` la primera vez).

## Uso

```sh
nix develop                # o direnv
cp .env.example .env       # completar los valores de Auth0
./gradlew check            # ktlint + tests (los de integración necesitan Docker)
./gradlew ktlintFormat     # arreglar el formato
./gradlew bootRun          # levanta en :$PORT; /health responde sin token
```

El sistema completo se levanta desde `snippet-searcher-infra` con docker compose.

## Variables de entorno

| Variable | Para qué |
|---|---|
| `AUTH0_ISSUER_URI` | Issuer de Auth0, con la barra final |
| `AUTH0_AUDIENCE` | Audience de la API en Auth0 |
| `PORT` | Puerto HTTP (default 8080) |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Postgres de este servicio |
| `PERMISSION_SERVICE_URL`, `RUNNER_URL`, `ASSET_SERVICE_URL` | Servicios que orquesta |
| `WS_ALLOWED_ORIGINS` | Orígenes que pueden abrir el WebSocket de ejecución (default `*`) |

## Qué trae la plantilla

- `common/security`: todo exige un JWT válido de Auth0 salvo `/health`.
- `common/logging`: `X-Correlation-Id` y el usuario (`sub`) en el MDC de cada línea de log.
- `common/http`: los `RestClient` reenvían el correlation id y el token del usuario a otros servicios.
- `common/error`: `NotFoundException`, `ForbiddenException`, `ConflictException` y errores de validación como Problem Details.
- Migraciones con Flyway en `src/main/resources/db/migration` (`V1__descripcion.sql`, ...). Hibernate solo valida el esquema.

## Snippets

- Metadatos en la tabla `snippet` (`V1__create_snippet.sql`); el contenido en el Asset Service (`snippets/{id}`, y la versión formateada en `snippets-formatted/{id}`); los permisos en el Permission Service.
- **Autor.** Se guarda `owner_id` (el `sub`) y `owner_name` al crear: el primero de los claims `name`, `nickname` o `email` que traiga el token, si no el `sub`. Los access tokens de Auth0 no traen el nombre salvo que una Action lo agregue.
- **404 o 403.** Sin ningún permiso sobre el snippet, `404`, igual que si no existiera (no se filtra qué ids existen). Con `READ` pidiendo algo de `OWNER`, `403`.
- **Fallas a mitad de camino.** Crear guarda metadatos, contenido y `OWNER` en ese orden; el permiso es lo que lo hace visible. Si falla un paso se deshacen los anteriores. Actualizar guarda los metadatos y después el contenido; si falla el contenido se restauran los metadatos. Borrar elimina primero los metadatos y después, sin cortar si fallan, el contenido y los permisos (lo que quede huérfano no es accesible).
- Un servicio que no responde o responde algo inesperado se devuelve como `502`. Los clientes HTTP tienen timeouts de `spring.http.client.*`.
- La extensión de cada lenguaje se configura en `languages.extensions` (el Runner no la informa).

## Ejecución interactiva (`/ws/snippets/{id}/run`)

WebSocket plano según `contracts/websocket.md` (público como `/api/ws/...` detrás de NGINX); `run/SnippetRunHandler`.

- El handshake es público (`SecurityConfig`): el navegador no puede mandar headers, así que el primer mensaje es `start` con el JWT, que se valida con el mismo `JwtDecoder` de la API. Sin `start` válido en `snippet-run.start-timeout` (5 s) cierra con `4401`.
- Sin ningún permiso o snippet inexistente: `4404` (como el 404 HTTP); con acceso pero sin `READ`: `4403`; otro `start` en la misma sesión: `4409`.
- Los hilos del WebSocket no tienen `SecurityContext`: el chequeo de permiso y la lectura del contenido llevan el token del usuario explícito (`PermissionClient.check` y `AssetClient.get` aceptan `token`; sin él, el interceptor usa el del request HTTP como siempre).
- Abre `services.runner.url` (http→ws) `/ws/run` con `Authorization: Bearer <token del usuario>` y `X-Correlation-Id`, le manda el `start` con `content`, `version` y `correlationId`, y después reenvía los mensajes sin tocarlos en los dos sentidos. Si se cierra un lado se cierra el otro; si falla antes de empezar (Runner o servicios caídos) el cliente recibe `error` y `done error`.

## Ramas y CI

- `dev` y `prod`. Se trabaja en ramas cortas con pull request a `dev`; `prod` recibe merges desde `dev`.
- `ci.yml`: `./gradlew check` en cada pull request y push.
- `publish.yml`: en cada push a `dev` o `prod`, publica la imagen en GHCR como `ghcr.io/<owner>/snippet-service:<rama>` y `:<rama>-<sha>`.

## Migrar a GitHub

```sh
gh repo create <owner>/snippet-service --private --source . --remote origin
git push -u origin dev prod
gh repo edit <owner>/snippet-service --default-branch dev
```

No hace falta configurar secretos: CI usa el `GITHUB_TOKEN` del workflow para publicar en GHCR.
