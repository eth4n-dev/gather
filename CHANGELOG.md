# Changelog

## 1.3.0 - 2026-05-07

### Added

- Added favorite items in the Add Item tab so important blocks and materials stay easy to find.
- Added a Recent tab for quickly re-adding items used recently in the current world.
- Added animated Gather menu open/close effects with menu swoosh sounds.
- Added a Menu Spin setting so players can turn the spin animation on or off.
- Added a Controls screen for configuring the Gather menu key and the manual scan key separately.
- Added a separate Toggle Manual Scan keybinding so manual scan mode no longer shares the menu key.
- Added server-controlled xray permission support for multiplayer servers.
- Added `/gatherop xray on|off|status` for server operators to control Gather xray features.

### Changed

- Redesigned the Gather Settings screen into clearer Visuals, Workflow, and Preferences sections.
- Updated Workflow settings to include Scan, Imports / Exports, and Controls.
- Updated settings toggles to use clearer labels and colored ON/OFF states.
- Reworked Outline Settings presets into a single cycling preset button.
- Outline presets now show the active preset name, or Custom when values do not match a preset.
- The Gather menu now closes with the configured Gather key or with E.
- Manual scan mode now uses its own keybinding plus optional Shift/Ctrl/Alt modifiers.
- Xray / Glow settings are disabled on servers until xray permission is allowed by the server.
- Improved the Imports / Exports screen so the export view can switch back to world imports.
- Temporary import/export status messages now clear themselves after a few seconds.
- Updated Scan Settings wording and controls to reflect the separate manual scan key.

### Fixed

- Fixed the Outline Range setting so the full intended range up to 128 blocks is available.
- Fixed G-menu click sounds so custom Gather buttons match normal Minecraft UI button clicks.
- Fixed startup crash from registering the Gather keybinding category more than once.

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
