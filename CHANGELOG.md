# Changelog

## 1.1.0 - 2026-05-01

Initial public release.

- Added a shared 50ms inventory snapshot for HUD and material counting so repeated item queries reuse one player inventory plus carried-shulker scan.
- Updated chest finder matching so finder results use the same exact item, tag-aware, and wood-family-aware counting semantics as Gather totals.
- Improved Scan Settings with clearer controls and status information.
- Updated the Help screen for the current Gather feature set.
- Kept nested shulker counting in scanned containers to one supported shulker layer.

## Initial development

Internal builds before the first public release used experimental `1.0.x` and `1.2.x` version numbers. Public release numbering starts at `1.1.0`; patch/test builds should use `1.1.x`, and the next feature release should use `1.2.0`.

- Added per-world Gather lists and transfer flow.
- Added HUD layout editing.
- Added world outlines, scanned container highlighting, and xray settings.
- Added crafting table Gather goal overlay.
- Added shulker collector configuration and routing.
