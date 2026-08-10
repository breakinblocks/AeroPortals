// Probe script for the AeroPortals gametest suite. Exercises the startup registration surface.
AeroPortalsEvents.register(event => {
    event.blockPosFixer('aeroportals:kubejs_probe', ['ProbePos'])
    event.dimensionFixer('aeroportals:kubejs_probe', ['ProbeDim'])
    event.nestedBlockPosFixer('aeroportals:kubejs_probe', 'Child', ['NestedPos'])

    event.portal('kubejs_probe_portal', 'minecraft:jukebox', ctx => {
        ctx.setDestination('minecraft:the_nether', 0, 100, 0)
    })
})
