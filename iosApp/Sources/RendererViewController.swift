import GLRendererDemo
import GLKit
import QuartzCore

/// Hosts the shared Kotlin `DemoApp` in a GLKView. GLKit draws on the main thread,
/// so gestures can call into the app directly.
///
/// The view has no depth buffer and no MSAA: the renderer draws into its own framebuffers,
/// antialiases with FXAA and only writes the finished image to GLKView's framebuffer.
final class RendererViewController: GLKViewController {
    private var app: DemoApp!
    private var lastPan = CGPoint.zero
    private let status = UILabel()

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
        preferredFramesPerSecond = 60

        EAGLContext.setCurrent(context)
        app = DemoApp(gl: IosGl(), assets: BundleAssetSource(directory: "assets"))
        app.surfaceCreated()

        status.font = .preferredFont(forTextStyle: .subheadline)
        status.textColor = UIColor.white.withAlphaComponent(0.85)
        status.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(status)
        NSLayoutConstraint.activate([
            status.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            status.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
        ])
        updateStatus()

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(doubleTap))
        doubleTap.numberOfTapsRequired = 2
        let tap = UITapGestureRecognizer(target: self, action: #selector(tap))
        tap.require(toFail: doubleTap)
        glView.addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(pan(_:))))
        glView.addGestureRecognizer(UIPinchGestureRecognizer(target: self, action: #selector(pinch(_:))))
        glView.addGestureRecognizer(doubleTap)
        glView.addGestureRecognizer(tap)
        glView.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(longPress(_:))))
    }

    override func glkView(_ view: GLKView, drawIn rect: CGRect) {
        app.surfaceChanged(width: Int32(view.drawableWidth), height: Int32(view.drawableHeight))
        app.drawFrame(timeSeconds: CACurrentMediaTime())
    }

    private func updateStatus() {
        status.text = app.status
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

    @objc private func tap() {
        app.togglePipeline()
        updateStatus()
    }

    @objc private func doubleTap() {
        app.toggleLid()
    }

    @objc private func longPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }
        app.togglePause()
        updateStatus()
    }
}
