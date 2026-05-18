# AeroPortals

Sail your [Create: Aeronautics](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics) airship through a portal and the whole ship goes with you. Players, passengers, decorations, and item frames travel together. AeroPortals is the bridge between Create's airships ([Sable](https://www.curseforge.com/minecraft/mc-mods/sable) physics) and Minecraft's portal system.

> Status: proof of concept. The core flow works end-to-end and is covered by 34 automated tests, but expect rough edges. See [Known limitations](#known-limitations).

## What it does

- **Nether portals** Fly your airship into a nether portal and you and the ship arrive on the other side. Wide ship? Build a wide portal. AeroPortals matches the source portal's dimensions at the destination, generates one if needed, and clears a safe landing area so you don't end up entombed in a wall of basalt.

- **End portals** Sail onto an end portal and the ship lands on the obsidian platform in the End. If the platform was destroyed (or you're in a custom End) AeroPortals rebuilds it before you arrive. Travelling back from the End drops you near world spawn.

- **Aether portals** (when [The Aether](https://www.curseforge.com/minecraft/mc-mods/aether) is installed). Same idea as nether portals but with glowstone frames and the Aether dimension.

- **Ars Nouveau warp portals** (when [Ars Nouveau](https://www.curseforge.com/minecraft/mc-mods/ars-nouveau) is installed). Use a Warp Scroll to configure the portal's destination, then fly your ship into it. The ship lands on top of the configured warp position.

- **Draconic Evolution portals** (when [Draconic Evolution](https://www.curseforge.com/minecraft/mc-mods/draconic-evolution) is installed). Build a Dislocator Receptacle portal with a configured Dislocator and fly your ship through. The ship lands on the receptacle's stored target.

- **Pina colada teleport** (when [Tropicraft](https://www.curseforge.com/minecraft/mc-mods/tropicraft) is installed). Drink a pina colada while sailing your airship and you and the ship travel to the Tropics. Drink another in the Tropics to come home.

## Who comes with you

When the ship teleports, these passengers come along:
- **Players** standing on the ship
- **Decorations and storage**: paintings, item frames (and what's inside them), armor stands, all minecart variants, block/item/text displays
- **Functional bits**: Create seats and contraptions, Create: Aeronautics propellers and sails, Botania mana/corporea bursts, snow golems, leash knots

These do NOT come along:
- Mobs (cows, zombies, villagers, ...)
- Dropped items
- Projectiles, falling blocks

This matches what already happens when you fly your ship around normally: those entities fall off. The portal teleport behaves the same way.

### Want cows on your airship?

Drop in a one-file datapack at `data/sable/tags/entity_type/retain_in_sub_level.json`:

```json
{
  "replace": false,
  "values": [
    "minecraft:cow",
    "minecraft:villager",
    "minecraft:wolf"
  ]
}
```

Multiple datapacks merge cleanly, so several mods can add to this tag without stepping on each other.

## Commands

### `/aeroportals teleport <dimension>` (op only)

OP-level command (permission level 2). Teleports you and the airship you're riding to the named dimension. Autocompletes the dimensions available to your server:

- `overworld`, `nether`, `end` always
- `aether` when Aether is installed
- `tropicraft` when Tropicraft is installed
- You can also type any custom dimension as `namespace:path` directly

The ship lands at a sensible spot in the destination: the obsidian platform for the End, somewhere safe above the surface for everything else.

## For server admins

### Requirements

| Mod | Range | Required? |
|---|---|---|
| Minecraft | 1.21.1 | yes |
| NeoForge | 21.1.230+ | yes |
| [Sable](https://www.curseforge.com/minecraft/mc-mods/sable) | 1.0+ | yes |
| [Create](https://www.curseforge.com/minecraft/mc-mods/create) | 6.0+ | optional (needed to actually build airships) |
| [The Aether](https://www.curseforge.com/minecraft/mc-mods/aether) | 1.5+ | optional |
| [Ars Nouveau](https://www.curseforge.com/minecraft/mc-mods/ars-nouveau) | 5.11+ | optional |
| [Tropicraft](https://www.curseforge.com/minecraft/mc-mods/tropicraft) | 9.8+ | optional |
| [Draconic Evolution](https://www.curseforge.com/minecraft/mc-mods/draconic-evolution) | 3.1+ | optional |

If an optional mod isn't installed, that integration is simply inactive: no errors, no warnings, no overhead.

### Config

`config/aeroportals-server.toml` is generated on first server launch.

| Key | Default | What it does |
|---|---|---|
| `detection.scan_interval_ticks` | `5` | How often the mod checks if your airship is touching a portal (in server ticks; 20 = 1 second). |
| `detection.verbose_logging` | `true` | Log each teleport at INFO level. Turn off for quieter logs. |
| `detection.max_sublevel_aabb_volume` | `200000.0` | Skip teleporting any ship larger than this volume. Sanity guard so a wildly-misconfigured ship doesn't trigger a teleport. |
| `teleport.portal_cooldown_ticks` | `200` | After teleporting, the ship is locked out of portals for this long (in ticks). Prevents a ship from immediately re-teleporting back through the destination portal. |
| `teleport.dest_portal_search_radius` | `128` | How far AeroPortals looks for an existing matching portal at the destination before deciding to build a new one. |
| `teleport.generate_matching_portal` | `true` | If `false`, the teleport aborts when no destination portal is found instead of building one. |

### Crash safety

AeroPortals writes the ship snapshot to disk before moving it. If the server crashes mid-teleport, the ship is recovered on next startup instead of being lost.

## Known limitations

- **Ropes and joints don't survive teleport.** Ships connected by Sable rope/docking links travel together and stay in formation, but the physical connection itself breaks. Re-tie the rope or re-dock at the destination.
- **Other modded portal types aren't auto-supported.** Twilight Forest, Mystcraft, etc. aren't recognized. Vanilla nether/end, plus Aether, Ars Nouveau, Tropicraft, and Draconic Evolution are the supported portals today.
- **Speed slightly drops per teleport.** Each portal trip costs about 10% of your velocity (Sable's reload setting). Tune `sub_level_velocity_retained_on_load` in Sable's config if you want full preservation.

## Want support for another portal or dimension?

Open an issue on the [GitHub issue tracker](https://github.com/breakinblocks/AeroPortals/issues). We're open to adding support for additional portal mods and custom dimensions: just let us know which mod you'd like to see and we'll take a look.

## For mod developers

AeroPortals fires `SubLevelTransferEvent` on the NeoForge event bus after each teleport, with the new SubLevel, source/destination dimensions, and translation vector. Useful if your mod tracks cross-SubLevel positions (e.g. docking pairs, partner references) that need updating after a move.

## License

MIT, see [LICENSE.md](LICENSE.md).
