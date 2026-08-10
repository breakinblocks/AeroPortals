# AeroPortals KubeJS support

For pack developers. Everything here works from scripts alone, no Java and no addon mod. AeroPortals
detects KubeJS on its own; if KubeJS is not installed nothing here loads and AeroPortals behaves normally.

Two things are added: an `AeroPortalsEvents` event group, and an `AeroPortals` binding you can call
from any script.

Ready-to-copy scripts covering everything below are in `docs/examples/kubejs/`:

- [`startup_scripts/aeroportals_example.js`](examples/kubejs/startup_scripts/aeroportals_example.js) — fixers and portals
- [`server_scripts/aeroportals_example.js`](examples/kubejs/server_scripts/aeroportals_example.js) — events, plus `/shipwarp` and `/shipinfo` commands

Both are menus rather than starting points: keep the sections you want and delete the rest. The server
one blocks all travel to the End if you copy it whole.

---

## Events

### `AeroPortalsEvents.register` (startup scripts)

Runs once, after all startup scripts load. This is where you teach AeroPortals about block entities and
portals from other mods.

```js
// startup_scripts/aeroportals.js
AeroPortalsEvents.register(event => {
    event.blockPosFixer('somemod:anchor', ['LinkedPos', 'HomePos'])
    event.dimensionFixer('somemod:beacon', ['TargetDim'])
})
```

**Fixers.** When a ship crosses dimensions its blocks change coordinates, because the destination may
sit at a different plot and the Overworld and Nether have different build heights. AeroPortals already
moves each block entity, but anything *inside* a block entity's saved data that remembers a position or
a dimension has to be corrected too, or the machine points at the wrong place after the trip.

| Method | What it fixes |
|---|---|
| `blockPosFixer(id, keys)` | positions saved at the top level. Handles the usual shapes: `[x, y, z]` arrays, packed longs, and `{X, Y, Z}` compounds. |
| `dimensionFixer(id, keys)` | dimension ids. Only rewrites values pointing at the dimension you left, so a link to some third dimension is left alone. |
| `nestedBlockPosFixer(id, path, keys)` | positions inside a child tag. The path is dot-separated, e.g. `'components.create:click_to_link_data'`. |
| `nestedDimensionFixer(id, path, keys)` | dimension ids inside a child tag. |
| `listBlockPosFixer(id, listKey, keys)` | positions inside every entry of a list. |
| `clearFixer(id, keys)` | deletes keys outright, for state that should not survive the trip. |

`keys` takes a list or a single string, so `event.blockPosFixer('somemod:anchor', 'LinkedPos')` is fine.

Use the F3 + I debug key or a block-entity inspection mod to see what a machine actually saves, then fix
the keys holding coordinates. Fixers only ever run when the ship really moved, so a fixer for a key
that is already correct costs nothing.

**Portals.** Register a block as a portal that carries ships:

```js
AeroPortalsEvents.register(event => {
    event.portal('my_rift', 'somemod:rift_block', ctx => {
        ctx.setDestination('minecraft:the_end', 100, 80, 100)
    })

    // several blocks can share one portal
    event.portal('crystal_gate', ['somemod:red_gate', 'somemod:blue_gate'], ctx => {
        if (ctx.srcDimension == 'minecraft:the_end') {
            ctx.landOn('minecraft:overworld', ctx.portalPos)
        } else {
            ctx.setDestination('minecraft:the_end', 0, 90, 0)
        }
    })
})
```

The callback runs when a ship touches one of those blocks. On the context object:

- `ctx.srcLevel`, `ctx.srcDimension`, `ctx.sub`, `ctx.subId`, `ctx.subPosition`
- `ctx.portalPos` is the portal block the ship touched, `ctx.portalId` is the id you registered
- `ctx.setDestination(dimension, x, y, z)` sets where the ship's centre ends up
- `ctx.landOn(dimension, pos)` puts the ship down on top of a block
- `ctx.setValidateLanding(false)` skips the blocked-landing check when you know the space is clear

Set no destination and the ship stays put, which is how you make a portal that only works under some
condition. Script-registered portals are checked before the built-in ones, so you can override how
AeroPortals treats a vanilla portal block if you want to.

### `AeroPortalsEvents.preTransfer` (server scripts)

Fires before anything moves. Cancel it to stop the trip, or move the destination.

```js
// server_scripts/aeroportals.js
AeroPortalsEvents.preTransfer(event => {
    if (event.dstDimension == 'minecraft:the_end' && event.chainSize > 1) {
        event.cancel()
    }
})

AeroPortalsEvents.preTransfer(event => {
    event.offsetDestination(0, 20, 0)   // always arrive 20 blocks higher
})
```

- `event.sub`, `event.subId`, `event.chain`, `event.chainSize` describe the ship and everything docked
  or roped to it that is travelling with it
- `event.srcLevel`, `event.dstLevel`, `event.srcDimension`, `event.dstDimension`
- `event.destination`, `event.setDestination(x, y, z)`, `event.offsetDestination(x, y, z)`
- `event.label` says what triggered the trip: `nether`, `end`, `aether`, `ars_nouveau`, `draconic`,
  `deeperdarker`, `ender-gateway`, `create_teleporters`, `onboard-portal`, `pina_colada`, `telepastries`,
  `ae2-spatial-store`, `ae2-spatial-recall`, `command`, `kubejs`, or a portal id you registered
- `event.cancel()` stops the trip and leaves the ship where it is

Every way a ship can change dimension goes through this event, including the command and the AE2
spatial storage path, so one listener covers all of them.

### `AeroPortalsEvents.transfer` (server scripts)

Fires once per ship, after it has arrived and the crew are back on deck.

```js
AeroPortalsEvents.transfer(event => {
    event.srcLevel.tell(Text.gold('A ship left for ' + event.dstDimension))
})
```

- `event.sub`, `event.subId`, `event.srcLevel`, `event.dstLevel`, `event.srcDimension`, `event.dstDimension`
- `event.translation` is how far the ship moved, `event.plotShift` is how far its blocks moved
- `event.remapPlotPos(pos)` converts a position from before the trip to after it

---

## The `AeroPortals` binding

Available in any script.

```js
AeroPortals.isPortalBlock(block)          // does anything treat this block state as a portal
AeroPortals.portalTypeOf(block)           // which portal claims it, or null
AeroPortals.portalTypes()                 // every registered portal id

AeroPortals.subLevelOf(entity)            // the ship a player or mob is standing on, or null
AeroPortals.subLevelsIn(level)            // every ship in a dimension
AeroPortals.subLevelById(level, uuid)
AeroPortals.chainOf(sub)                  // the ship plus everything docked or roped to it

AeroPortals.positionOf(sub)
AeroPortals.boundsOf(sub)
AeroPortals.levelOf(sub)

AeroPortals.teleport(sub, 'minecraft:the_nether', 0, 100, 0)
AeroPortals.teleport(sub, 'minecraft:the_nether', 0, 100, 0, false)   // skip the landing check
```

`AeroPortals.teleport` runs the same path as a portal, so `preTransfer` and `transfer` still fire and a
blocked landing still cancels the trip unless you pass `false`.

Example, sending whoever is at the wheel somewhere on command:

```js
ServerEvents.commands(event => {
    event.register(
        Commands.literal('scuttle')
            .requires(src => src.hasPermission(2))
            .executes(ctx => {
                const player = ctx.source.player
                const sub = AeroPortals.subLevelOf(player)
                if (sub === null) {
                    player.tell('You are not standing on a ship')
                    return 0
                }
                AeroPortals.teleport(sub, 'minecraft:the_end', 100, 80, 100)
                return 1
            })
    )
})
```

---

## Notes

- Registration in `AeroPortalsEvents.register` is rebuilt from scratch each time it runs, so editing
  your startup scripts and restarting will not stack duplicate fixers.
- Fixers run on the server thread while the ship is being rebuilt at the destination. Keep them short,
  and do not try to read blocks from the world inside one; the ship is mid-flight through the move.
- If a script throws, AeroPortals logs it and carries on with the teleport rather than stranding the ship.
