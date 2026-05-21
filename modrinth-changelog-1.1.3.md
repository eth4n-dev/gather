# Gather 1.1.3 for Minecraft 1.21.11

This update focuses on making goals faster to add, easier to manage, and less annoying during normal play.

## New

- Added JEI support. When JEI is installed, you can hover an item in the JEI item list or bookmarks and press `G` to add it to Gather.
- Added a new Add Goal popup for JEI items. It lets you choose the amount before adding the goal.
- If you have more than one Gather list, the Add Goal popup lets you choose which list to add the item to.
- Added goal-added feedback with a small popup and sound, so it is clearer when an item was added successfully.
- Added "Any Wood" goal choices for common wood items:
  - Any Log
  - Any Wood Planks
  - Any Wood Slab
  - Any Wood Stairs
  - Any Wood Fence
  - Any Wood Door
  - Any Wood Trapdoor
- Added goal sorting in the Gather menu. You can keep goals in date order or sort them A-Z.
- Added an in-game update notice card, with buttons to open the Gather page or hide future update notices.
- Added an Updates toggle in settings for turning update notices on or off.

## Improved

- Improved outline settings text so range, outline count, and load speed are easier to understand.
- Improved recipe breakdowns for items that can be made from multiple valid ingredients.
- Improved counting for some wood and tag-based items, so Gather is better at accepting matching item variants.
- Improved HUD feedback when goals become ready or complete.
- Improved crafting and trade helper counting to reduce repeated inventory checks.

## Fixed

- Fixed the JEI Add Goal card letting the mouse interact with item slots behind it.
- Fixed item highlights and tooltips showing through the Add Goal card in container screens.
- Fixed several text input problems in the Gather menu, including held Backspace not repeating correctly.
- Fixed movement keys leaking through while typing in Gather menu text fields.
- Fixed some goal-add flows not showing confirmation feedback.
- Fixed cases where newly added goals did not respect the selected "need more" vs "total wanted" counting mode.
- Fixed several small menu layout issues around compact screens and long labels.
