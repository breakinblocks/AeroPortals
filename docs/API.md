# AeroPortals addon API

For mod developers who want AeroPortals to understand their portals, their block entities, or their
entities when an airship crosses dimensions. Everything here is in `com.breakinblocks.aeroportals.api`.

## Depending on AeroPortals

Releases are on the BreakInBlocks maven, with sources attached. Snapshots go to
`https://maven.breakinblocks.com/snapshots` under the same coordinates.

```gradle
repositories {
    maven {
        url = "https://maven.breakinblocks.com/releases"
        content { includeGroup("com.breakinblocks.aeroportals") }
    }
}

dependencies {
    compileOnly("com.breakinblocks.aeroportals:aeroportals:1.21.1-1.3.0") { transitive = false }
    runtimeOnly("com.breakinblocks.aeroportals:aeroportals:1.21.1-1.3.0") { transitive = false }
}
```

The version is `<minecraft_version>-<mod_version>`. The published artifact is the whole mod jar, so
`transitive = false` matters: AeroPortals depends on Sable and Create, and you do not want those
pulled onto your compile classpath by accident.

Mark AeroPortals as an optional dependency in `neoforge.mods.toml` and guard your registration with
`ModList.get().isLoaded("aeroportals")`, so your mod still loads when it is absent.

Register during `FMLCommonSetupEvent` (inside `enqueueWork`). Registration order between mods does not
matter, except for transfer carriers, which are noted below.

---

## What happens during a transfer

1. AeroPortals notices an airship (a Sable SubLevel) overlapping a portal block, and asks the matching
   **portal type** where it should go.
2. `SubLevelPreTransferEvent` fires. Listeners can veto the move or move the destination.
3. The landing site is validated (or cleared, per server config).
4. For each SubLevel in the dependency chain: **transfer carriers** capture, the SubLevel is snapshotted,
   removed from the source dimension, and reloaded in the destination.
5. While reloading, **NBT fixers** rewrite each block entity tag before it becomes a live block entity.
6. Carriers replay in reverse registration order, riders are placed, and `SubLevelTransferEvent` fires.

If the destination load fails, the SubLevel is restored to the source dimension and carriers replay
there instead, so a failed transfer looks the same to your code as one that never started.

---

## Portal types

Implement `AeroPortalType` to teach AeroPortals about a portal block of your own.

```java
public final class MyPortalType implements AeroPortalType {
    @Override
    public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath("mymod", "rift");
    }

    @Override
    public boolean matches(BlockState state) {
        return state.is(MyBlocks.RIFT.get());
    }

    @Override
    public PortalDestination resolve(ServerLevel srcLevel, ServerSubLevel sub, BlockPos hitPos) {
        ServerLevel dst = srcLevel.getServer().getLevel(MY_DIMENSION);
        if (dst == null) return null;
        return PortalDestination.of(dst, new Vec3(0.5, 120.0, 0.5), "mymod-rift");
    }
}

AeroPortalsApi.registerPortal(new MyPortalType());
```

`resolve` returns `null` to decline the trip, which leaves the ship where it is. The position in
`PortalDestination` is the SubLevel's world centre at the destination, not a block to land on.
Use `PortalDestination.of(level, pos, false, label)` to skip landing validation when you already know
the space is clear.

`priority()` decides which type wins when two match the same block state (higher first, default 0).
`isEnabled()` is checked on every lookup, so you can gate a type behind your own config. Server
owners can also switch your type off by adding its id to `travel_methods.disabled` in
`aeroportals-server.toml`; that check happens alongside `isEnabled()` and needs nothing from you.

Related helpers:

- `AeroPortalsApi.findPortalType(BlockState)` returns the type that claims a block state, or `null`.
- `AeroPortalsApi.isPortalBlock(BlockState)` is the boolean form.
- `AeroPortalsApi.portalTypes()` lists everything registered, in priority order.

---

## Block entity NBT fixers

When a SubLevel moves, its blocks usually change coordinates: the destination plot may sit at a
different slot, and the Overworld and the Nether have different build heights. AeroPortals already
shifts the `x`/`y`/`z` of every block entity. Anything **else** in your block entity's NBT that stores a
position or a dimension id needs a fixer, or it will point at the wrong place after the trip.

Fixers run on the raw tag before the block entity is constructed, so they also reach block entities that
are stored packed and never instantiated.

```java
AeroPortalsApi.registerNbtFixer("mymod:anchor", NbtFixers.blockPos("LinkedPos"));

AeroPortalsApi.registerNbtFixer(
        Set.of("mymod:tank", "mymod:vault"),
        NbtFixers.blockPos("ControllerPos", "LastKnownPos"));
```

`NbtFixers` factories:

| Factory | Use for |
|---|---|
| `blockPos(keys...)` | positions stored as an int array, a packed long, or a compound with `X`/`Y`/`Z` (or `x`/`y`/`z`); also handles a list of any of those. Does nothing when the plot did not move. |
| `dimensionId(keys...)` | string dimension ids. Rewrites only values equal to the source dimension, so a target in some third dimension is left alone. Does nothing when the dimension did not change. |
| `nested(path, inner)` | descend into child compounds first. The path is dot-separated, e.g. `"components.create:click_to_link_data"`. |
| `each(listKey, inner)` | apply a fixer to every compound in a list. |
| `all(fixers...)` | run several fixers over the same tag. |
| `clearKeys(keys...)` | drop keys entirely, for state that should not survive the trip. |

For anything more involved, write a `BlockEntityNbtFixer` yourself. It receives the block entity's
`CompoundTag` and an `NbtFixContext`:

```java
public record NbtFixContext(
    UUID subUuid,                      // the SubLevel being moved; unchanged by the trip
    ResourceKey<Level> srcDimension,
    ResourceKey<Level> dstDimension,
    BlockPos plotShift,                // add this to any position inside the moving plot
    Vec3 worldTranslation,             // how far the ship moved in world space
    BlockPos srcRegionMin,             // may be null
    int srcRegionBlocks)
```

with `dimensionChanged()`, `moved()`, `shift(BlockPos)`, `insideSourcePlot(BlockPos)`,
`srcDimensionId()` and `dstDimensionId()`.

Two things worth knowing:

- **SubLevel UUIDs are preserved.** A tag that stores the UUID of a SubLevel stays valid, so there is no
  UUID remapping to do.
- **`plotShift` only applies to positions inside the moving plot.** If your block entity can point at a
  block out in the world, check `insideSourcePlot(pos)` first and leave outside targets alone.
- Fixers only see their **own** SubLevel's shift. A reference into a *different* SubLevel that is
  travelling in the same chain cannot be resolved here; use `SubLevelTransferEvent.remapPlotPos` after
  the move instead.

---

## Transfer carriers

A carrier moves something that is not a block: entities attached to the hull, external state keyed by
position, anything you need to take down before the move and put back after.

```java
public final class GlueCarrier implements TransferCarrier<List<AABB>> {
    @Override
    public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath("mymod", "glue");
    }

    @Override
    public List<AABB> capture(ServerLevel srcLevel, ServerSubLevel sub) {
        List<AABB> boxes = collectAndRemove(srcLevel, sub);
        return boxes.isEmpty() ? null : boxes;   // null means "nothing to carry"
    }

    @Override
    public void replay(ServerLevel dstLevel, ServerSubLevel newSub, List<AABB> captured, BlockPos plotShift) {
        for (AABB box : captured) {
            dstLevel.addFreshEntity(new MyGlue(dstLevel, box.move(plotShift.getX(), plotShift.getY(), plotShift.getZ())));
        }
    }
}

AeroPortalsApi.registerCarrier(new GlueCarrier());
```

Carriers capture in registration order and replay in **reverse** order, so a carrier registered early
gets the first look at the ship and the last word on the destination. `replay` runs on the destination
after a successful move, or back on the source if the move failed and the ship was restored; in both
cases `plotShift` is the shift that actually happened. Returning `null` from `capture` skips replay.
`isEnabled()` is checked before each capture.

---

## Events

Both events are on `NeoForge.EVENT_BUS` and both fire on the server thread.

### `SubLevelPreTransferEvent` (cancellable)

Fires once per teleport, after the travelling chain is known and before anything is moved.

```java
@SubscribeEvent
public static void onPre(SubLevelPreTransferEvent event) {
    if (event.chain().size() > 4) {
        event.cancel("too many ships docked together");
        return;
    }
    event.setDestination(event.destination().add(0.0, 10.0, 0.0));
}
```

- `sub()`, `srcLevel()`, `dstLevel()`, `label()` describe the trip.
- `chain()` is every SubLevel that will travel together (ropes, docking connectors, swivel bearings,
  and anything whose bounding box overlaps).
- `destination()` / `setDestination(Vec3)` is the primary SubLevel's world centre at the destination.
  The rest of the chain keeps its relative position.
- `cancel(String reason)` vetoes the trip and puts the reason in the log.

Every route into AeroPortals goes through this event, including the command, the dimension-stacking
compats, AE2 spatial storage, and drink or cake style teleports.

### `SubLevelTransferEvent`

Fires once per SubLevel, after it has arrived and its riders are in place. This is where you fix up
live block entities, in particular anything referencing a *different* SubLevel from the same chain.

```java
@SubscribeEvent
public static void onTransfer(SubLevelTransferEvent event) {
    for (var holder : event.newSub().getPlot().getLoadedChunks()) {
        for (BlockEntity be : holder.getChunk().getBlockEntities().values()) {
            if (be instanceof MyAnchor anchor) {
                anchor.setLinked(event.remapPlotPos(anchor.getLinked()));
            }
        }
    }
}
```

- `remapPlotPos(BlockPos)` maps a position from before the move to after it, for **any** SubLevel in the
  chain, and returns the input unchanged for positions that did not travel.
- `subUuid()`, `newSub()`, `srcLevel()`, `dstLevel()`, `translation()`, `plotShift()`.
- `chainPlotMoves()` is the raw per-SubLevel move list behind `remapPlotPos`.
