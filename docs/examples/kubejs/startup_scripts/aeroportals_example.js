// Copy to: kubejs/startup_scripts/aeroportals_example.js
// Runs once at game start.
//
// Every 'somemod:' id below is made up. Replace them with real ones or
// delete the section; a fixer naming a block entity that does not exist
// is harmless but does nothing.

AeroPortalsEvents.register(event => {

    // ----------------------------------------------------------------
    // Machines that remember a position
    // ----------------------------------------------------------------
    // A machine storing coordinates internally points at the wrong place
    // once its ship changes dimension. Name the keys and AeroPortals
    // corrects them mid-move.

    event.blockPosFixer('somemod:anchor', ['LinkedPos', 'HomePos'])
    event.blockPosFixer('somemod:cable', 'ControllerPos')

    // Multiblocks usually keep a controller position on every part.
    event.blockPosFixer('somemod:tank_wall', ['MasterPos'])
    event.blockPosFixer('somemod:tank_valve', ['MasterPos'])

    // Buried one level down, e.g. { "Link": { "Target": [x, y, z] } }
    event.nestedBlockPosFixer('somemod:relay', 'Link', ['Target'])

    // A list of entries that each carry a position.
    event.listBlockPosFixer('somemod:router', 'Destinations', ['Pos'])

    // ----------------------------------------------------------------
    // Machines that remember a dimension
    // ----------------------------------------------------------------
    // Only values naming the dimension you left get rewritten, so a link
    // deliberately pointing somewhere else survives untouched.

    event.dimensionFixer('somemod:beacon', ['TargetDim'])
    event.nestedDimensionFixer('somemod:relay', 'Link', ['Dim'])

    // ----------------------------------------------------------------
    // State that should not survive the trip
    // ----------------------------------------------------------------
    // Drop a saved beam target or a cached path so the machine starts
    // clean at the destination rather than firing at stale coordinates.

    event.clearFixer('somemod:laser', ['BeamTarget', 'CachedPath'])

    // ----------------------------------------------------------------
    // A portal of your own
    // ----------------------------------------------------------------
    // The callback runs when a ship touches the block. Set a destination
    // to send it; set nothing and the ship stays put.

    event.portal('example_one_way_rift', 'somemod:rift_block', ctx => {
        ctx.setDestination('minecraft:the_end', 100, 90, 100)
    })

    // Several blocks can share one portal, and the trip can depend on
    // where the ship currently is.
    event.portal('example_two_way_gate', ['somemod:red_gate', 'somemod:blue_gate'], ctx => {
        if (ctx.srcDimension == 'minecraft:the_end') {
            ctx.landOn('minecraft:overworld', ctx.portalPos)
        } else {
            ctx.setDestination('minecraft:the_end', 0, 90, 0)
        }
    })

    // A portal that only opens at night, and lands the ship without the
    // usual blocked-landing check because the pad is known to be clear.
    event.portal('example_night_gate', 'somemod:moon_gate', ctx => {
        let time = ctx.srcLevel.dayTime % 24000
        if (time < 13000 || time > 23000) {
            return
        }
        ctx.setValidateLanding(false)
        ctx.setDestination('somemod:moon', 0, 120, 0)
    })
})
