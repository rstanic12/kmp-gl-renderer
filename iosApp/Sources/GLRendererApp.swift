import SwiftUI

@main
struct GLRendererApp: App {
    var body: some Scene {
        WindowGroup {
            RendererView()
                .ignoresSafeArea()
                .overlay(alignment: .bottom) {
                    Text("Drag to orbit · pinch to zoom · double-tap to open the lid")
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.65))
                        .padding(.bottom, 24)
                }
                .preferredColorScheme(.dark)
        }
    }
}

struct RendererView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> RendererViewController { RendererViewController() }
    func updateUIViewController(_ controller: RendererViewController, context: Context) {}
}
