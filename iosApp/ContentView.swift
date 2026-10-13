import ComposeApp
import Foundation
import SwiftUI

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            startupModeFlag: ProcessInfo.processInfo.environment["BORROW_AI_DATA_MODE"]
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
