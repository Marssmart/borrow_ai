import Foundation
import Vision

guard CommandLine.arguments.count == 2 else {
    fatalError("Usage: assert-ios-smoke-screen.swift <screenshot>")
}

let screenshotURL = URL(fileURLWithPath: CommandLine.arguments[1])
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = false

try VNImageRequestHandler(url: screenshotURL).perform([request])

let recognizedText = (request.results ?? [])
    .compactMap { $0.topCandidates(1).first?.string }
    .joined(separator: "\n")
    .uppercased()
let expectedText = ["DEMO DATA", "TAP OR HOLD"]
let missingText = expectedText.filter { !recognizedText.contains($0) }

guard missingText.isEmpty else {
    fputs(
        "Missing expected text in iOS Simulator screen: \(missingText.joined(separator: ", "))\n" +
            "Recognized text:\n\(recognizedText)\n",
        stderr,
    )
    exit(EXIT_FAILURE)
}

print("iOS Simulator showed the demo badge and capture control.")
