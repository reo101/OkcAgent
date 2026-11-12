{
  description = "OkcAgent Kotlin Android development environment";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
    systems.url = "github:nix-systems/default";
  };

  outputs =
    inputs:
    inputs.flake-parts.lib.mkFlake { inherit inputs; } {
      systems = import inputs.systems;

      perSystem =
        { pkgs, ... }:
        {
          devShells.default = pkgs.mkShell {
            buildInputs = [
              # JDK for Kotlin/Android
              pkgs.jdk17

              # Build tools
              pkgs.gradle

              # Kotlin compiler
              pkgs.kotlin

              # Android SDK and NDK
              pkgs.android-tools
            ];

            shellHook = ''
              echo "Kotlin Android development environment loaded"
              echo "JDK version: $(java -version 2>&1 | head -n 1)"
              echo "Gradle version: $(gradle --version | head -n 1)"
            '';
          };
        };
    };
}
