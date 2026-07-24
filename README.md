# AeroPortals

Sail your [Create: Aeronautics](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics) airship through a portal and the whole ship goes with you. Players, passengers, decorations, and item frames travel together. AeroPortals is the bridge between Create's airships ([Sable](https://www.curseforge.com/minecraft/mc-mods/sable) physics) and Minecraft's portal system.

> Status: public beta. The core flow works end-to-end and is covered by 39 automated tests, but expect rough edges. See [Known limitations](#known-limitations).

## Warning

[Sable](https://www.curseforge.com/minecraft/mc-mods/sable) is a deeply intrusive physics mod and stability is never guaranteed. **Always back up your worlds before flying through portals.** If something goes wrong, please report it on our [issue tracker](https://github.com/breakinblocks/AeroPortals/issues) with logs and a reproduction case. We will do our best to accommodate new feature requests alongside bug fix updates.

## What it does

- **Nether portals** Fly your airship into a nether portal and you and the ship arrive on the other side. Wide ship? Build a wide portal. AeroPortals matches the source portal's dimensions at the destination and generates one if needed. The linking portal is always placed inside the playable layer of the Nether, never on top of the bedrock roof, even if your source portal was at a high Y in the Overworld.

- **End portals** Sail onto an end portal and the ship lands at the vanilla End spawn point. The 5x5 obsidian spawn pad is rebuilt if missing. Travelling back from the End drops you near world spawn.

- **Onboard portal jump drives** (off by default; enable `onboard_portal_jumps` in the server config). Build a nether portal into your ship and light it mid-flight: after a short countdown the whole ship jumps to the other dimension, portal and all, like a spaceship performing a jump. A matching portal is linked or built at the destination beside where you arrive. Break or extinguish the onboard portal during the countdown to abort, and re-light it whenever you want to jump again.

- **Non-destructive landings** If your destination is blocked by terrain or builds, AeroPortals will tell you in chat which block is in the way and cancel the teleport. Your ship stays where it is, and the destination side is never modified. Clear the space and try again. Server admins who would rather have big ships carve their own landing zone can enable `clear_destination_blocks` in the server config: blocking blocks at the destination are then destroyed (without drops) instead of cancelling the trip. Portal blocks, portal frames, and unbreakable blocks are always left intact.

- **Aether portals** (when [The Aether](https://www.curseforge.com/minecraft/mc-mods/aether) is installed). Same idea as nether portals but with glowstone frames and the Aether dimension.

- **Ars Nouveau warp portals** (when [Ars Nouveau](https://www.curseforge.com/minecraft/mc-mods/ars-nouveau) is installed). Use a Warp Scroll to configure the portal's destination, then fly your ship into it. The ship lands on top of the configured warp position.

- **Draconic Evolution portals** (when [Draconic Evolution](https://www.curseforge.com/minecraft/mc-mods/draconic-evolution) is installed). Build a Dislocator Receptacle portal with a configured Dislocator and fly your ship through. The ship lands on the receptacle's stored target.

- **Deeper and Darker portals** (when [Deeper and Darker](https://www.curseforge.com/minecraft/mc-mods/deeperdarker) is installed). Same idea as nether portals but with reinforced deepslate frames, leading to The Otherside.

- **Pina colada teleport** (when [Tropicraft](https://www.curseforge.com/minecraft/mc-mods/tropicraft) is installed). Drink a pina colada while sailing your airship and you and the ship travel to the Tropics. Drink another in the Tropics to come home.

- **TelePastries cakes** (when [TelePastries](https://www.curseforge.com/minecraft/mc-mods/telepastries) is installed). Right-click any TelePastries cake (Overworld, Nether, End, Twilight, Lost City, or the three configurable custom cakes) while on your airship and the ship travels to that cake's dimension with you.

- **Create: Ender Gateway portals** (when [Create: Ender Gateway](https://modrinth.com/mod/create-ender-gateway) is installed). Fly your ship into a linked gateway and it comes out at the partner gateway on the other side, in either direction between the Overworld and the End.

- **Create: Teleporters portals** (when [Create: Teleporters](https://www.curseforge.com/minecraft/mc-mods/create-teleporters) is installed). Fly your ship into a custom portal frame and it travels to the portal's configured destination, whether that is a set of TP Link coordinates or another linked portal, in any dimension.

- **Dimension stacking** (when [Stackable Planar Dimensions](https://www.curseforge.com/minecraft/mc-mods/stackable-planar-dimensions) or [Forgiving World](https://www.curseforge.com/minecraft/mc-mods/forgiving-world) is installed). Fly your airship down through the floor or up past the ceiling of a stacked dimension and the whole ship crosses into the adjoining dimension with everyone aboard, using the stack layout, heights, and coordinate scaling from that mod's config.

- **AE2 spatial storage** (when [Applied Energistics 2](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2) is installed). Park your airship inside a Spatial IO Port capture area and it travels into the spatial storage cell with the rest of the region, then comes back out wherever the cell is deployed. The whole ship must fit inside the pylon area or it is left behind; docked or roped-together ships travel as a group and must all fit. While stored, the ship is held in stasis (no gravity inside the storage dimension) and resumes normal flight when deployed.

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

### `/aeroportals teleport <dimension> [x y z]` (op only)

OP-level command (permission level 2). Teleports you and the airship you're riding to the named dimension. Autocompletes the dimensions available to your server:

- `overworld`, `nether`, `end` always
- `aether` when Aether is installed
- `tropicraft` when Tropicraft is installed
- `otherside` when Deeper and Darker is installed
- You can also type any custom dimension as `namespace:path` directly

Without coordinates, the ship lands at a sensible spot in the destination: the obsidian platform for the End, somewhere safe above the surface for everything else.

Add three coordinates after the dimension to pick the landing spot yourself, for example `/aeroportals teleport kubejs:deep_space -1000 ~ -1000`. Coordinates are absolute positions in the destination dimension; the command does not apply nether-style coordinate scaling to them. Each coordinate also accepts `~` or `~offset`, relative to the spot the command would have picked on its own, so `~ ~ ~` is the same as leaving them off. The Y coordinate is where the bottom of your airship (or your feet, without a ship) ends up. Note that `execute positioned` does not affect this command; use the explicit coordinates instead.

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
| [Deeper and Darker](https://www.curseforge.com/minecraft/mc-mods/deeperdarker) | 1.3+ | optional |
| [Applied Energistics 2](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2) | 19.2+ | optional |
| [Stackable Planar Dimensions](https://www.curseforge.com/minecraft/mc-mods/stackable-planar-dimensions) | 1.9+ | optional |
| [Forgiving World](https://www.curseforge.com/minecraft/mc-mods/forgiving-world) | 4.7+ | optional |
| [Create: Teleporters](https://www.curseforge.com/minecraft/mc-mods/create-teleporters) | 2.0.2+ | optional |
| [Create: Ender Gateway](https://modrinth.com/mod/create-ender-gateway) | 1.1+ | optional |

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
| `teleport.clear_velocity_on_arrival` | `false` | If `true`, ships arrive from a teleport standing still. By default they keep their momentum. |

### Crash safety

AeroPortals writes the ship snapshot to disk before moving it. If the server crashes mid-teleport, the ship is recovered on next startup instead of being lost.

## Known limitations

- **Ropes and joints don't survive teleport.** Ships connected by Sable rope/docking links travel together and stay in formation, but the physical connection itself breaks. Re-tie the rope or re-dock at the destination.
- **Other modded portal types aren't auto-supported.** Twilight Forest, Mystcraft, etc. aren't recognized. Vanilla nether/end, plus Aether, Ars Nouveau, Tropicraft, Draconic Evolution, Deeper and Darker, Create: Teleporters, Create: Ender Gateway, AE2 spatial storage, and dimension stacking (Stackable Planar Dimensions / Forgiving World) are the supported travel methods today.
- **Speed slightly drops per teleport.** Each portal trip costs about 10% of your velocity (Sable's reload setting). Tune `sub_level_velocity_retained_on_load` in Sable's config if you want full preservation.

## Want support for another portal or dimension?

Open an issue on the [GitHub issue tracker](https://github.com/breakinblocks/AeroPortals/issues). We're open to adding support for additional portal mods and custom dimensions: just let us know which mod you'd like to see and we'll take a look.

## For mod developers

AeroPortals fires `SubLevelTransferEvent` on the NeoForge event bus after each teleport, with the new SubLevel, source/destination dimensions, and translation vector. Useful if your mod tracks cross-SubLevel positions (e.g. docking pairs, partner references) that need updating after a move.

## License

MIT, see [LICENSE.md](LICENSE.md).
