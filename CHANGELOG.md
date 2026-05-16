# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Initial proof-of-concept implementation.
- Detection of Sable SubLevels overlapping `minecraft:nether_portal` blocks via per-tick AABB scan.
- Cross-dimension SubLevel teleport via `SubLevelSerializer.toData` / `fullyLoad` round-trip, with destination-side plot collision fallback.
- Rider capture and re-binding: players tracked on a SubLevel keep their local offset, yaw delta, and pitch across the dimension change.
- Per-SubLevel teleport cooldown to prevent portal bounce-back.
- Server-side config: `verbose_logging`, `scan_interval_ticks`, `max_sublevel_aabb_volume`, `portal_cooldown_ticks`.
