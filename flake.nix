{
  description = "snippet-service: entorno de desarrollo";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        java = pkgs.javaPackages.compiler.openjdk21;
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            # Build (Gradle solo para regenerar el wrapper; el build usa ./gradlew)
            java
            (gradle.override { inherit java; })

            # Contenedores rootless, solo para este repo (dev/podman-env.sh)
            podman
            docker-compose

            # Utilidades
            curl
            jq
            postgresql
            redis
          ];

          JAVA_HOME = java.home;

          shellHook = ''
            source ${./dev/podman-env.sh} snippet-service
          '';
        };
      }
    );
}
