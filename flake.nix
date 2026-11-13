{
  description = "OkcAgent Android build tools and host protocol tests";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts = {
      url = "github:hercules-ci/flake-parts";
      inputs.nixpkgs-lib.follows = "nixpkgs";
    };
    systems.url = "github:nix-systems/default";
  };

  outputs =
    inputs:
    inputs.flake-parts.lib.mkFlake { inherit inputs; } {
      systems = import inputs.systems;

      perSystem =
        { pkgs, system, ... }:
        let
          sdk =
            (pkgs.androidenv.composeAndroidPackages {
              platformVersions = [ "33" ];
              buildToolsVersions = [ "31.0.0" ];
              includeEmulator = false;
              includeSystemImages = false;
              includeNDK = false;
            }).androidsdk;
        in
        {
          _module.args.pkgs = import inputs.nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          checks.agent-runtime =
            pkgs.runCommand "check-okcagent-runtime"
              {
                nativeBuildInputs = [
                  pkgs.python3
                  pkgs.jdk11
                  pkgs.kotlin
                ];
              }
              ''
                export HOME="$TMPDIR"
                python3 ${./.}/tests/test-agent-runtime.py
                touch "$out"
              '';

          devShells.default = pkgs.mkShell {
            packages = [
              pkgs.jdk11
              pkgs.python3
              pkgs.kotlin
              pkgs.unzip
            ];
            JAVA_HOME = pkgs.jdk11.home;
            ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
            ANDROID_HOME = "${sdk}/libexec/android-sdk";
          };
        };
    };
}
