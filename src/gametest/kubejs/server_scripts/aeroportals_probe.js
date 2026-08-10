// Probe script for the AeroPortals gametest suite. Exercises the server event surface.
AeroPortalsEvents.preTransfer(event => {
    if (event.label == 'kubejs-veto-probe') {
        event.cancel()
    }
})

AeroPortalsEvents.transfer(event => {
    console.log('AeroPortals moved sub ' + event.subId + ' from ' + event.srcDimension + ' to ' + event.dstDimension)
})
