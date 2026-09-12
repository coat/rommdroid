#!/usr/bin/env kotlin
// Regenerate every launcher-icon asset from design/logo/rommdroid-icon.svg.
//
// Emits:
//   app/src/main/res/drawable/ic_launcher_background.xml   adaptive background layer
//   app/src/main/res/drawable/ic_launcher_foreground.xml   adaptive foreground layer
//   app/src/main/res/drawable/ic_launcher_monochrome.xml   themed-icon layer (API 33+)
//   app/src/main/res/mipmap-*/ic_launcher.png              legacy square icon
//   app/src/main/res/mipmap-*/ic_launcher_round.png        legacy round icon
//   design/logo/rommdroid-play-512.png                     Play Store listing icon
//
// Run from anywhere inside the checkout:
//   kotlin design/logo/generate-icons.main.kts
//
// Needs only the JDK. The PNGs are rasterised with Java2D rather than an SVG
// renderer, so the master is limited to what this script understands: <rect>
// (with rx), <path> using M/L/H/V/C/Q/Z, and one <linearGradient> backdrop.

import java.awt.Color
import java.awt.LinearGradientPaint
import java.awt.Paint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

System.setProperty("java.awt.headless", "true")

val SVG = "http://www.w3.org/2000/svg"
val ROOT: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").exists() }
    ?: error("run this from inside the rommdroid checkout")
val MASTER = ROOT.resolve("design/logo/rommdroid-icon.svg")
val RES = ROOT.resolve("app/src/main/res")

// The master canvas maps onto the adaptive icon's 72dp safe zone, so the art
// survives every launcher mask. 108dp viewport = 768 units, art inset by 128.
val CANVAS = 512
val VIEWPORT = 768
val INSET = 128
val DENSITIES = listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)

/** A flattened SVG shape: its role, VectorDrawable path data, fill, and Java2D geometry. */
class Fig(val role: String?, val pathData: String, val fill: String, val shape: Shape)

fun num(v: Double): String =
    if (v == Math.rint(v) && Math.abs(v) < 1e6) v.toLong().toString()
    else BigDecimal(v).round(MathContext(6)).stripTrailingZeros().toPlainString()

fun Element.attr(name: String, default: String? = null): String =
    getAttribute(name).ifEmpty { default ?: error("<$localName> is missing '$name'") }

fun Element.children(): Sequence<Element> =
    generateSequence(firstChild) { it.nextSibling }.filterIsInstance<Element>()

/** Rounded rect as VectorDrawable-safe path data (no rx attribute there). */
fun rrectPath(x: Double, y: Double, w: Double, h: Double, r: Double): String {
    val hx = w - 2 * r
    val vy = h - 2 * r
    return "M${num(x + r)},${num(y)} h${num(hx)} a${num(r)},${num(r)} 0 0 1 ${num(r)},${num(r)} " +
        "v${num(vy)} a${num(r)},${num(r)} 0 0 1 -${num(r)},${num(r)} h-${num(hx)} " +
        "a${num(r)},${num(r)} 0 0 1 -${num(r)},-${num(r)} v-${num(vy)} " +
        "a${num(r)},${num(r)} 0 0 1 ${num(r)},-${num(r)} z"
}

/** Parse SVG path data into Java2D geometry. Arcs aren't supported: use <rect rx> for those. */
fun path(d: String): Path2D {
    val tokens = Regex("[A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
    val p = Path2D.Double()
    var i = 0
    var cmd = ' '
    var cx = 0.0; var cy = 0.0   // current point
    var sx = 0.0; var sy = 0.0   // subpath start
    fun n() = tokens[i++].toDouble()
    while (i < tokens.size) {
        if (tokens[i][0].isLetter()) cmd = tokens[i++][0]
        else if (cmd == 'Z' || cmd == 'z') error("path data has numbers after Z: $d")
        val ox = if (cmd.isLowerCase()) cx else 0.0
        val oy = if (cmd.isLowerCase()) cy else 0.0
        when (cmd.uppercaseChar()) {
            'M' -> { cx = ox + n(); cy = oy + n(); p.moveTo(cx, cy); sx = cx; sy = cy; cmd = if (cmd == 'm') 'l' else 'L' }
            'L' -> { cx = ox + n(); cy = oy + n(); p.lineTo(cx, cy) }
            'H' -> { cx = ox + n(); p.lineTo(cx, cy) }
            'V' -> { cy = oy + n(); p.lineTo(cx, cy) }
            'C' -> { val x1 = ox + n(); val y1 = oy + n(); val x2 = ox + n(); val y2 = oy + n(); cx = ox + n(); cy = oy + n(); p.curveTo(x1, y1, x2, y2, cx, cy) }
            'Q' -> { val x1 = ox + n(); val y1 = oy + n(); cx = ox + n(); cy = oy + n(); p.quadTo(x1, y1, cx, cy) }
            'Z' -> { p.closePath(); cx = sx; cy = sy }
            else -> error("unsupported path command '$cmd' in: $d")
        }
    }
    return p
}

/** Flatten an SVG element into figures, document order. Role and fill inherit from enclosing groups. */
fun shapes(el: Element, role: String? = null, fill: String? = null): List<Fig> = el.children().flatMap { c ->
    val r = c.getAttribute("data-role").ifEmpty { null } ?: role
    val f = c.getAttribute("fill").ifEmpty { null } ?: fill
    when (c.localName) {
        "g" -> shapes(c, r, f)
        "rect" -> {
            val x = c.attr("x", "0").toDouble()
            val y = c.attr("y", "0").toDouble()
            val w = c.attr("width").toDouble()
            val h = c.attr("height").toDouble()
            val rx = minOf(c.attr("rx", "0").toDouble(), w / 2, h / 2)
            listOf(Fig(r, rrectPath(x, y, w, h, rx), f ?: "#000000", RoundRectangle2D.Double(x, y, w, h, 2 * rx, 2 * rx)))
        }
        "path" -> listOf(Fig(r, c.attr("d"), f ?: "#000000", path(c.attr("d"))))
        else -> emptyList()
    }
}.toList()

fun vector(body: String): String =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
    "<!-- Generated by design/logo/generate-icons.main.kts - do not edit. -->\n" +
    "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
    "    android:width=\"108dp\"\n" +
    "    android:height=\"108dp\"\n" +
    "    android:viewportWidth=\"$VIEWPORT\"\n" +
    "    android:viewportHeight=\"$VIEWPORT\">\n" +
    "$body\n" +
    "</vector>\n"

fun write(file: File, text: String) {
    file.parentFile.mkdirs()
    file.writeText(text)
    println("  ${file.relativeTo(ROOT)}")
}

fun write(file: File, image: BufferedImage) {
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
    println("  ${file.relativeTo(ROOT)} (${image.width}px)")
}

val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(MASTER)
val svg = doc.documentElement
val flat = shapes(svg)
val bg = flat.first { it.role == "bg" }
val fg = flat.filter { it.role != null && it.role != "bg" }

val grad = doc.getElementsByTagNameNS(SVG, "linearGradient").item(0) as Element
val stops = grad.children().filter { it.localName == "stop" }
    .map { it.attr("offset") to it.attr("stop-color") }.toList()

println("vector drawables:")
val items = stops.joinToString("\n") { (o, c) -> "                <item android:offset=\"$o\" android:color=\"$c\"/>" }
write(RES.resolve("drawable/ic_launcher_background.xml"), vector(
    "    <path android:pathData=\"M0,0h${VIEWPORT}v${VIEWPORT}h-${VIEWPORT}z\">\n" +
    "        <aapt:attr xmlns:aapt=\"http://schemas.android.com/aapt\"\n" +
    "                   name=\"android:fillColor\">\n" +
    "            <gradient\n" +
    "                android:type=\"linear\"\n" +
    "                android:startX=\"$INSET\" android:startY=\"$INSET\"\n" +
    "                android:endX=\"${VIEWPORT - INSET}\" android:endY=\"${VIEWPORT - INSET}\">\n" +
    "$items\n" +
    "            </gradient>\n" +
    "        </aapt:attr>\n" +
    "    </path>"))

val paths = fg.joinToString("\n") {
    "        <path\n" +
    "            android:pathData=\"${it.pathData}\"\n" +
    "            android:fillColor=\"${it.fill}\"/>"
}
write(RES.resolve("drawable/ic_launcher_foreground.xml"), vector(
    "    <group android:translateX=\"$INSET\" android:translateY=\"$INSET\">\n" +
    "$paths\n" +
    "    </group>"))

// Themed icons are a single tinted silhouette: cartridge shell with the
// arrow and the connector pins punched out via even-odd winding.
val solid = fg.filter { it.role in setOf("body", "arrow", "pins") }.joinToString(" ") { it.pathData }
write(RES.resolve("drawable/ic_launcher_monochrome.xml"), vector(
    "    <group android:translateX=\"$INSET\" android:translateY=\"$INSET\">\n" +
    "        <path\n" +
    "            android:pathData=\"$solid\"\n" +
    "            android:fillType=\"evenOdd\"\n" +
    "            android:fillColor=\"#FFFFFF\"/>\n" +
    "    </group>"))

val adaptive =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
    "<!-- Generated by design/logo/generate-icons.main.kts - do not edit. -->\n" +
    "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
    "    <background android:drawable=\"@drawable/ic_launcher_background\"/>\n" +
    "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\"/>\n" +
    "    <monochrome android:drawable=\"@drawable/ic_launcher_monochrome\"/>\n" +
    "</adaptive-icon>\n"
for (name in listOf("ic_launcher.xml", "ic_launcher_round.xml")) write(RES.resolve("mipmap-anydpi-v26/$name"), adaptive)

// Raster fallbacks. The backdrop's corner radius is the only difference
// between the square, round and Play Store cuts.
val backdrop = bg.shape as RoundRectangle2D

// The gradient's x1..y2 are fractions of the backdrop's bounding box.
fun gx(name: String, default: String) = (backdrop.x + grad.attr(name, default).toDouble() * backdrop.width).toFloat()
fun gy(name: String, default: String) = (backdrop.y + grad.attr(name, default).toDouble() * backdrop.height).toFloat()
val gradient = LinearGradientPaint(gx("x1", "0"), gy("y1", "0"), gx("x2", "1"), gy("y2", "0"),
    stops.map { it.first.toFloat() }.toFloatArray(), stops.map { Color.decode(it.second) }.toTypedArray())

fun paint(fill: String): Paint = if (fill == "url(#${grad.attr("id")})") gradient else Color.decode(fill)

fun render(px: Int, radius: Double): BufferedImage {
    val image = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.scale(px.toDouble() / CANVAS, px.toDouble() / CANVAS)
    g.paint = paint(bg.fill)
    g.fill(RoundRectangle2D.Double(backdrop.x, backdrop.y, backdrop.width, backdrop.height, 2 * radius, 2 * radius))
    for (fig in fg) {
        g.paint = paint(fig.fill)
        g.fill(fig.shape)
    }
    g.dispose()
    return image
}

println("raster icons:")
for ((suffix, radius) in listOf("" to backdrop.arcWidth / 2, "_round" to CANVAS / 2.0)) {
    for ((density, px) in DENSITIES) write(RES.resolve("mipmap-$density/ic_launcher$suffix.png"), render(px, radius))
}
write(MASTER.resolveSibling("rommdroid-play-512.png"), render(512, 0.0))  // Play applies its own mask
