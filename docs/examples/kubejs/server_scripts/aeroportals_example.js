// Copy to: kubejs/server_scripts/aeroportals_example.js
// Reloads with /reload.
//
// This is a menu, not a starting point. Copied whole it will block every
// trip to the End and every convoy heading to the Nether. Keep the two or
// three sections you actually want and delete the rest.

// ----------------------------------------------------------------
// Stopping a trip
// ----------------------------------------------------------------
// preTransfer fires before anything moves, for every route a ship can
// take: portals, the command, AE2 spatial storage, drinks and cakes.

AeroPortalsEvents.preTransfer(event => {
    if (event.dstDimension == 'minecraft:the_end') {
        event.cancel()
    }
})

// Ships docked or roped together travel as one group. This keeps big
// convoys out of the Nether without banning single ships.
AeroPortalsEvents.preTransfer(event => {
    if (event.dstDimension == 'minecraft:the_nether' && event.chainSize > 3) {
        event.cancel()
    }
})

// ----------------------------------------------------------------
// Moving where a ship comes out
// ----------------------------------------------------------------

AeroPortalsEvents.preTransfer(event => {
    if (event.label == 'nether') {
        event.offsetDestination(0, 20, 0)
    }
})

AeroPortalsEvents.preTransfer(event => {
    if (event.label == 'end') {
        event.setDestination(200, 100, 200)
    }
})

// ----------------------------------------------------------------
// Reacting once a ship has arrived
// ----------------------------------------------------------------

AeroPortalsEvents.transfer(event => {
    event.dstLevel.players.forEach(player => {
        if (AeroPortals.subLevelOf(player) == event.sub) {
            player.tell(Text.gold('Arrived in ' + event.dstDimension))
        }
    })
})

AeroPortalsEvents.transfer(event => {
    console.log('ship ' + event.subId + ': ' + event.srcDimension + ' -> ' + event.dstDimension
        + ' (moved ' + event.translation + ')')
})

// ----------------------------------------------------------------
// Sending a ship yourself
// ----------------------------------------------------------------
// AeroPortals.teleport runs the same path as a portal, so preTransfer
// and transfer still fire and a blocked landing still cancels the trip.

ServerEvents.commandRegistry(event => {
    const { commands: Commands, arguments: Arguments } = event

    event.register(
        Commands.literal('shipwarp')
            .requires(source => source.hasPermission(2))
            .then(Commands.argument('dimension', Arguments.DIMENSION.create(event))
                .executes(ctx => {
                    const player = ctx.source.player
                    if (player === null) {
                        return 0
                    }

                    const sub = AeroPortals.subLevelOf(player)
                    if (sub === null) {
                        player.tell(Text.red('Stand on a ship first'))
                        return 0
                    }

                    const target = Arguments.DIMENSION.getResult(ctx, 'dimension')
                    const here = AeroPortals.positionOf(sub)
                    AeroPortals.teleport(sub, target, here.x, here.y, here.z)
                    return 1
                })
            )
    )
})

// ----------------------------------------------------------------
// Looking around
// ----------------------------------------------------------------

ServerEvents.commandRegistry(event => {
    const { commands: Commands } = event

    event.register(
        Commands.literal('shipinfo')
            .executes(ctx => {
                const player = ctx.source.player
                if (player === null) {
                    return 0
                }

                const sub = AeroPortals.subLevelOf(player)
                if (sub === null) {
                    player.tell(Text.red('Stand on a ship first'))
                    return 0
                }

                player.tell('id: ' + sub.uniqueId)
                player.tell('at: ' + AeroPortals.positionOf(sub))
                player.tell('travelling with: ' + (AeroPortals.chainOf(sub).size() - 1) + ' other ship(s)')
                player.tell('ships in this dimension: ' + AeroPortals.subLevelsIn(player.level).size())
                return 1
            })
    )
})
