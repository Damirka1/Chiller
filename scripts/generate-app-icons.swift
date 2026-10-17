// Original Chiller brand geometry; run on macOS: swift scripts/generate-app-icons.swift <output.png>
// Platform .icns/.ico containers are generated from this PNG, without redrawing the artwork.
import AppKit
let output = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "app-desktop/src/main/resources/chiller-icon.png"
let size = 1024
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSColor.clear.setFill(); NSRect(x: 0, y: 0, width: size, height: size).fill()
func color(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> NSColor { NSColor(srgbRed: r, green: g, blue: b, alpha: a) }
let tile = NSBezierPath(roundedRect: NSRect(x: 96, y: 96, width: 832, height: 832), xRadius: 184, yRadius: 184)
NSGraphicsContext.saveGraphicsState()
let shadow = NSShadow(); shadow.shadowColor = color(0.06, 0.18, 0.13, 0.20); shadow.shadowBlurRadius = 22; shadow.shadowOffset = NSSize(width: 0, height: -10); shadow.set()
color(0.72, 0.85, 0.78).setFill(); tile.fill()
NSGraphicsContext.restoreGraphicsState()
NSGradient(starting: color(0.86, 0.95, 0.90), ending: color(0.65, 0.81, 0.73))!.draw(in: tile, angle: -70)
NSGraphicsContext.saveGraphicsState(); tile.addClip()
let inner = NSBezierPath(roundedRect: NSRect(x: 97.5, y: 97.5, width: 829, height: 829), xRadius: 182.5, yRadius: 182.5)
color(1, 1, 1, 0.35).setStroke(); inner.lineWidth = 2; inner.stroke()
NSGraphicsContext.restoreGraphicsState()
let mark = NSBezierPath(); mark.lineCapStyle = .round; mark.lineJoinStyle = .round; mark.lineWidth = 51
let center = NSPoint(x: 512, y: 516), radius: CGFloat = 246
for i in 0..<6 {
    let a = CGFloat(i) * .pi / 3 + .pi / 2
    func point(_ distance: CGFloat, _ angle: CGFloat) -> NSPoint { NSPoint(x: center.x + cos(angle) * distance, y: center.y + sin(angle) * distance) }
    mark.move(to: center); mark.line(to: point(radius, a))
    let fork = point(radius * 0.56, a)
    for side in [-1.0, 1.0] {
        let b = a + CGFloat(side) * .pi / 3
        mark.move(to: fork); mark.line(to: NSPoint(x: fork.x + cos(b) * radius * 0.32, y: fork.y + sin(b) * radius * 0.32))
    }
}
color(0.12, 0.29, 0.23).setStroke(); mark.stroke()
NSGraphicsContext.restoreGraphicsState()
try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: output), options: .atomic)
print("Wrote \(output) (1024×1024, RGBA)")
