# Contenedores rootless con Podman, solo para este repo. Lo carga el dev shell de Nix.
#
# - Nada se instala en el sistema: Podman viene del dev shell y no corre como root.
# - Imágenes, contenedores y configuración viven en .podman/ (ignorado por git).
#   Para borrar todo: podman unshare rm -rf .podman
# - docker-compose y Testcontainers hablan con Podman por su API compatible con Docker,
#   en un socket dentro de /run/user/<uid> (se borra al cerrar sesión). El servicio se
#   apaga solo después de 15 minutos sin uso y el próximo dev shell lo vuelve a levantar.
#
# Uso (desde flake.nix): source dev/podman-env.sh <nombre-del-repo>

_podman_name="$1"
_podman_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
_podman_home="$_podman_root/.podman"

mkdir -p "$_podman_home/conf"
[ -f "$_podman_home/conf/policy.json" ] ||
  echo '{"default":[{"type":"insecureAcceptAnything"}]}' >"$_podman_home/conf/policy.json"
[ -f "$_podman_home/conf/registries.conf" ] ||
  echo 'unqualified-search-registries = ["docker.io"]' >"$_podman_home/conf/registries.conf"
# Se reescribe siempre: si el repo se mueve, las rutas siguen siendo las correctas
cat >"$_podman_home/conf/storage.conf" <<EOF
[storage]
driver = "overlay"
graphroot = "$_podman_home/storage"
runroot = "$_podman_home/run"
EOF

export CONTAINERS_POLICY="$_podman_home/conf/policy.json"
export CONTAINERS_REGISTRIES_CONF="$_podman_home/conf/registries.conf"
export CONTAINERS_STORAGE_CONF="$_podman_home/conf/storage.conf"
export DOCKER_HOST="unix://${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman-$_podman_name.sock"
# Ryuk (el limpiador de Testcontainers) necesita privilegios que Podman rootless no da;
# los contenedores de cada test se borran igual al terminar.
export TESTCONTAINERS_RYUK_DISABLED=true

if ! podman --url "$DOCKER_HOST" info >/dev/null 2>&1; then
  (podman system service --time=900 "$DOCKER_HOST" >"$_podman_home/service.log" 2>&1 &)
  for _ in $(seq 1 50); do
    podman --url "$DOCKER_HOST" info >/dev/null 2>&1 && break
    sleep 0.1
  done
fi

unset _podman_name _podman_root _podman_home
