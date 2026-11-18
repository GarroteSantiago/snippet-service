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
| `REDIS_HOST`, `REDIS_PORT` | Redis de los streams (default `localhost:6379`) |
| `STREAMS_CONSUMER_NAME` | Opcional: nombre de consumidor en el grupo; por defecto el hostname del contenedor |

| `AUTH0_DOMAIN` | Dominio del tenant (`<tenant>.auth0.com`, sin `https://`; no un custom domain) para la Management API |
| `AUTH0_M2M_CLIENT_ID`, `AUTH0_M2M_CLIENT_SECRET` | App machine-to-machine de Auth0 autorizada en la Management API con `read:users` |

| `WS_ALLOWED_ORIGINS` | Orígenes que pueden abrir el WebSocket de ejecución (default `*`) |

## Qué trae la plantilla

- `common/security`: todo exige un JWT válido de Auth0 salvo `/health`.
- `common/logging`: `X-Correlation-Id` y el usuario (`sub`) en el MDC de cada línea de log.
- `common/http`: los `RestClient` reenvían el correlation id y el token del usuario a otros servicios.
- `common/error`: `NotFoundException`, `ForbiddenException`, `ConflictException` y errores de validación como Problem Details.
- Migraciones con Flyway en `src/main/resources/db/migration` (`V1__descripcion.sql`, ...): `V1` snippets, `V2` reglas, `V3` jobs y `parserVersion`. Hibernate solo valida el esquema.

## Snippets

- Metadatos en la tabla `snippet` (`V1__create_snippet.sql`); el contenido en el Asset Service (`snippets/{id}`, y la versión formateada en `snippets-formatted/{id}`); los permisos en el Permission Service.
- **Autor.** Se guarda `owner_id` (el `sub`) y `owner_name` al crear: el primero de los claims `name`, `nickname` o `email` que traiga el token, si no el `sub`. Los access tokens de Auth0 no traen el nombre salvo que una Action lo agregue.
- **404 o 403.** Sin ningún permiso sobre el snippet, `404`, igual que si no existiera (no se filtra qué ids existen). Con `READ` pidiendo algo de `OWNER`, `403`.
- **Fallas a mitad de camino.** Crear guarda metadatos, contenido y `OWNER` en ese orden; el permiso es lo que lo hace visible. Si falla un paso se deshacen los anteriores. Actualizar guarda los metadatos y después el contenido; si falla el contenido se restauran los metadatos. Borrar elimina primero los metadatos y después, sin cortar si fallan, el contenido y los permisos (lo que quede huérfano no es accesible).
- Un servicio que no responde o responde algo inesperado se devuelve como `502`. Los clientes HTTP tienen timeouts de `spring.http.client.*`.
- La extensión de cada lenguaje se configura en `languages.extensions` (el Runner no la informa).

## Reglas

- `GET /rules/{format|lint}`: las reglas guardadas del usuario (tabla `user_rules`, `V2`) o, si no guardó nada, el catálogo por defecto del Runner. El `id` de cada regla es su `name`.
- `PUT /rules/{format|lint}`: valida, guarda y responde `202` con lo guardado. Acá se rechazan nombres repetidos o desconocidos (contra el catálogo del Runner), `isActive` faltante y valores que no sean string, número o null; rangos, opciones y reglas opuestas los valida el Runner, corriendo las reglas sobre un snippet de prueba (`RunnerClient.validateRules`), y su `400` se devuelve como `400`.
- Después de guardar, `lint` pasa los snippets del usuario a `pending` y publica un `LINT` por snippet; `format` publica un `FORMAT` por snippet. El contenido se lee del Asset Service dentro del mismo request (con el token del usuario).
- `POST /format` formatea con las reglas de formato del usuario.

## Procesamiento en Redis Streams

Según `contracts/redis-streams.md`. `processing/SnippetJobs` publica en `snippet:jobs`; `processing/ResultHandler` consume `snippet:results` (grupo `snippet-service`, un consumidor por hostname). `common/streams` (cliente de Redis y `StreamWorker`) es igual al del Runner.

- **Qué se publica.** Crear: `LINT`. Cambiar contenido o versión: `LINT` y `FORMAT`. Reglas: ver arriba. Cambio de `parserVersion`: `VALIDATE` por snippet. `LINT` y `FORMAT` llevan las reglas del owner en ese momento.
- **Último job.** Antes de publicar se guarda el `jobId` como último de su tipo para el snippet (`snippet_job`, `V3`). Un resultado con otro `jobId` es viejo: se ignora y se confirma. `LINT` y `VALIDATE` deciden los dos el `compliance`, así que publicar uno olvida el otro.
- **Resultados.** `LINT` OK: `compliant` o `not-compliant` con las violaciones; FAILED: `failed`. `FORMAT` OK: el contenido va a `snippets-formatted/{id}`; FAILED: solo log. `VALIDATE` válido: publica un `LINT`; inválido: `not-compliant` con los diagnostics como violaciones (`rule` null), sin borrar el snippet; FAILED: `failed`.
- **Ack, reclamo y descarte.** `XACK` recién después de guardar; si falla (Postgres o Asset Service caídos) queda pendiente. Cada 30 s, `XAUTOCLAIM` de lo pendiente hace más de 60 s; con más de 3 entregas se descarta: `LINT` y `VALIDATE` dejan el snippet en `failed` (si siguen siendo el último job).
- Si no se puede publicar un `LINT` o `VALIDATE`, el snippet queda en `failed` (si no, quedaría `pending` para siempre); un `FORMAT` que no se puede publicar solo se loguea.
- **US-18.** Al arrancar y cada `parser-version.check-interval` (5 min) se consulta `GET /versions` del Runner. Si `parserVersion` cambió respecto del guardado en `parser_state` (`V3`), se guarda (un `UPDATE` atómico: con varias réplicas publica una sola) y se publica un `VALIDATE` por snippet.
- **Llamadas sin usuario.** El consumidor y el chequeo de `parserVersion` llaman al Runner y al Asset Service sin un JWT de usuario. Pasan todas por `common/http/BackgroundCalls`, que hoy no agrega token: el Runner (que exige JWT) les va a contestar `401` hasta que ahí se agregue un token M2M de Auth0 (client credentials).
- `streams.enabled: false` apaga el consumidor y el chequeo (se sigue publicando); así corren los tests sin Redis. `ProcessingIntegrationTest` usa Postgres y Redis con Testcontainers (corre en CI); la lógica también está cubierta sin Docker con un Redis en memoria (`support/FakeStreamClient`). El dev shell trae `redis` para probar a mano.
- Los `TEST` (US-16) son de la etapa 7: se marcan con `TODO(etapa 7)` donde se publican y donde se procesan sus resultados (hoy se confirman sin hacer nada).

## Tests de snippets

- Tabla `test_case` (`V4__create_test_case.sql`): nombre, `inputs` y `expected_outputs` (jsonb), `last_result` (`success`/`fail`, null si nunca corrió) y `last_run_at`. La FK a `snippet` es `ON DELETE CASCADE`: borrar el snippet borra sus tests.
- Mismas reglas de acceso que el snippet (`snippet/SnippetAccess.kt`): listar y correr piden `READ`; crear, editar y borrar, `OWNER`. Un test de otro snippet es `404`.
- Correr un test lee el contenido del Asset Service y llama a `POST /test` del Runner con la versión del snippet. Correr uno guardado guarda `last_result`; el ad hoc (`/tests/run`) no guarda nada. Editar un test borra su último resultado.
- Para US-16: `TestCaseService.testsOfSnippet` (los tests a publicar) y `TestCaseService.recordResult` (guarda el resultado por id de test, sin chequear permisos).

## Compartir (US-07)

- `GET /users?name=&page=&page_size=` busca en la Management API de Auth0 (search engine v3): usuarios cuyo `name`, `nickname` o `email` empieza con `name` (sin `name`, todos). El que pregunta se excluye en la misma query, así `count` es correcto. Cada usuario sale con `id` = `user_id` y `name` = `name`, si no `nickname`, si no `email`. El texto se escapa antes de armar la query.
- `POST /snippets/{id}/share` con `{"userId": "..."}` da `READ` en el Permission Service y devuelve el snippet (con contenido). Solo el `OWNER`: con `READ`, `403`; sin acceso, `404`. Compartir con uno mismo o con un usuario que Auth0 no conoce es `400`. Si el usuario ya tenía acceso (el Permission Service responde `409`) no cambia nada y responde `200` igual (idempotente).
- Token de la Management API: Client Credentials (`POST https://{AUTH0_DOMAIN}/oauth/token`, audience `https://{AUTH0_DOMAIN}/api/v2/`), guardado en memoria hasta un minuto antes de que venza. Un `401` de Auth0 lo descarta. A Auth0 nunca se le manda el token del usuario.
- Sin `AUTH0_DOMAIN`, `AUTH0_M2M_CLIENT_ID` o `AUTH0_M2M_CLIENT_SECRET` el servicio arranca igual; `/users` y compartir responden `502`. Una falla o un rate limit (`429`) de Auth0 también es `502`.

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
