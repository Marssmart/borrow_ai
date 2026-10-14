import ComposeApp
import Foundation
import SwiftUI

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            serviceVariantId: ProcessInfo.processInfo.environment["BORROW_AI_SERVICE_VARIANT"]
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
