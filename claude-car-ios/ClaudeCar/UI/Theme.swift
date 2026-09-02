import SwiftUI

// Warm dark palette, terracotta accent — carried over from the head-unit client
// so both ends of claude-car look like one app. Always dark: glare beats white
// panels in a car.
extension Color {
    static let ccInk = Color(red: 0.086, green: 0.071, blue: 0.055)        // #16120E
    static let ccPanel = Color(red: 0.141, green: 0.118, blue: 0.090)      // #241E17
    static let ccPanelHigh = Color(red: 0.196, green: 0.165, blue: 0.125)  // #322A20
    static let ccTerracotta = Color(red: 0.851, green: 0.467, blue: 0.341) // #D97757
    static let ccBone = Color(red: 0.929, green: 0.894, blue: 0.847)       // #EDE4D8
    static let ccDim = Color(red: 0.659, green: 0.608, blue: 0.541)        // #A89B8A
    static let ccToolChip = Color(red: 0.227, green: 0.196, blue: 0.153)   // #3A3227
    static let ccError = Color(red: 0.886, green: 0.341, blue: 0.294)      // #E2574B
    static let ccOk = Color(red: 0.482, green: 0.682, blue: 0.435)         // #7BAE6F
}
