import Engine
import GLKit
import QuartzCore

/// Hosts the shared Kotlin `DemoApp` in a GLKView. GLKit draws on the main thread,
/// so gestures can call into the app directly.
final class RendererViewController: GLKViewController {
    private var app: DemoApp!
    private var lastPan = CGPoint.zero

    override func loadView() {
        view = GLKView(frame: UIScreen.main.bounds)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        guard let context = EAGLContext(api: .openGLES3) else {
            fatalError("OpenGL ES 3 is not available")
        }
        let glView = view as! GLKView
        glView.context = context
        glView.delegate = self
        glView.drawableDepthFormat = .format24
        glView.drawableMultisample = .multisample4X
        preferredFramesPerSecond = 60

        EAGLContext.setCurrent(context)
        app = DemoApp(gl: IosGl(), assets: BundleAssetSource(directory: "assets"))
        app.surfaceCreated()

        glView.addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(pan(_:))))
        glView.addGestureRecognizer(UIPinchGestureRecognizer(target: self, action: #selector(pinch(_:))))
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(doubleTap))
        doubleTap.numberOfTapsRequired = 2
        glView.addGestureRecognizer(doubleTap)
    }

    override func glkView(_ view: GLKView, drawIn rect: CGRect) {
        app.surfaceChanged(width: Int32(view.drawableWidth), height: Int32(view.drawableHeight))
        app.drawFrame(timeSeconds: CACurrentMediaTime())
    }

    @objc private func pan(_ gesture: UIPanGestureRecognizer) {
        let translation = gesture.translation(in: view)
        if gesture.state == .began { lastPan = .zero }
        let dx = translation.x - lastPan.x
        let dy = translation.y - lastPan.y
        lastPan = translation
        app.orbit(dxFraction: Float(dx / view.bounds.width), dyFraction: Float(dy / view.bounds.height))
    }

    @objc private func pinch(_ gesture: UIPinchGestureRecognizer) {
        app.zoom(factor: Float(gesture.scale))
        gesture.scale = 1
    }

    @objc private func doubleTap() {
        app.toggleLid()
    }
}
