# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased] - 0.1.0-SNAPSHOT

First proof-of-concept release. Airships can now travel through portals.

### Added

#### Nether portals
- Fly your airship into a nether portal and the whole ship arrives on the other side.
- The destination portal matches the dimensions of the source portal, so wide ships get wide portals.
- If no usable portal exists at the destination, AeroPortals builds one and clears a small landing area so you don't end up entombed in a wall of basalt.

#### End portals
- Sail onto an end portal in the Overworld and the ship lands on the obsidian platform in the End. AeroPortals rebuilds the platform if it's been destroyed or you're in a custom End that lacks one.
- Travelling back from the End drops the ship near world spawn at a safe altitude.

#### Aether portals (when The Aether is installed)
- Works the same as nether portals but with glowstone frames.

#### Ars Nouveau warp portals (when Ars Nouveau is installed)
- Fly your ship into a warp portal configured with a Warp Scroll. The ship lands on top of the configured warp position.

#### Pina colada teleport (when Tropicraft is installed)
- Drinking a pina colada while sailing your airship sends you and the ship to the Tropics. Drink another in the Tropics to come home.

#### Draconic Evolution portals (when Draconic Evolution is installed)
- Build a Dislocator Receptacle portal with a configured Dislocator and fly your ship through. The ship lands on the receptacle's stored target.

#### Passengers
- Players standing on the ship travel with it.
- Decorations come along: paintings, item frames (with whatever item is inside), armor stands, all minecart variants, block/item/text displays.
- Functional bits come along: Create seats and contraptions, Create: Aeronautics propellers and sails, Botania mana/corporea bursts, snow golems, leash knots.
- Mobs, dropped items, projectiles, and falling blocks do NOT travel: they fall off like they would when the ship moves normally.
- Server admins can extend this with a one-file datapack (see the README) if they want extra entity types (cows, villagers, custom seats) to travel with airships.

#### Ship combinations
- Ships connected by ropes or docking links travel together as a unit, keeping their relative positions.
- Note that the rope or docking connection itself breaks across the trip and needs to be re-tied at the destination.

#### Operator teleport command
- `/aeroportals teleport <dimension>` directly sends your airship and you to a target dimension. Op-only.
- Suggests `overworld`, `nether`, `end`, plus `aether` and `tropicraft` when those mods are installed.
- (Ars Nouveau and Draconic Evolution portals aren't in this command because each portal has its own configured destination rather than a single dimension.)

#### Crash safety
- If the server crashes while your ship is mid-teleport, the ship is recovered automatically on next startup instead of being lost.

#### Configuration
- Server config at `config/aeroportals-server.toml` (see the README for the full table).

### Known limitations

- Ropes and physical joints between ships don't survive teleport. The ships travel together but the connection breaks. Re-tie at the destination.
- Each teleport costs about 10% of the ship's velocity (Sable's reload setting). Adjustable in Sable's config if you want full velocity preservation.
- Only Nether, End, Aether, Ars Nouveau, and Draconic Evolution portals are recognized. Twilight Forest, Mystcraft, and other modded portals don't trigger ship travel.
