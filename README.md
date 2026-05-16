# AeroPortals

A NeoForge 1.21.1 mod that lets [Sable](https://www.curseforge.com/minecraft/mc-mods/sable) SubLevels — the physics-driven ship structures used by [Create: Aeronautics](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics) — pass through Nether portals.

## Status

**Proof of concept.** Detection, cross-dimension transfer, and rider rebinding are implemented. Not yet tested end-to-end with a real airship in a multiplayer environment.

## How it works

Every few server ticks, AeroPortals scans loaded `SubLevel`s in every dimension. When a SubLevel's world-space AABB overlaps a `minecraft:nether_portal` block:

1. Riding players are captured with their local offsets and view angles.
2. The SubLevel is serialized via `SubLevelSerializer.toData`, its pose is rewritten to the scaled destination position (respecting `DimensionType.coordinateScale`), and any source-side allocation is freed.
3. The SubLevel is loaded into the destination dimension via `SubLevelSerializer.fullyLoad`, falling back to a free plot slot if the original collides.
4. Riders are teleported to the destination dimension at the same local offset on the new SubLevel.
5. A per-SubLevel cooldown prevents immediate bounce-back.

## Requirements

| Mod | Range |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x (≥ 21.1.219 required by Sable) |
| [Sable](https://www.curseforge.com/minecraft/mc-mods/sable) | 1.0+ (required) |
| [Create](https://www.curseforge.com/minecraft/mc-mods/create) | 6.0+ (optional — for actual airships) |

## Building

```
./gradlew build
```

Produces `aeroportals-1.21.1-<version>.jar` in `build/libs/`.

## License

MIT — see [LICENSE.md](LICENSE.md).
