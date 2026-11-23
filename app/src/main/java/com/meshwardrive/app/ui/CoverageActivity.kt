package com.meshwardrive.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meshwardrive.app.R
import com.meshwardrive.app.data.CoverageGrid
import com.meshwardrive.app.data.NodeEntry
import com.meshwardrive.app.data.Prediction
import com.meshwardrive.app.data.RxRow
import com.meshwardrive.app.data.Scoring
import com.meshwardrive.app.data.SessionMeta
import com.meshwardrive.app.data.Settings
import com.meshwardrive.app.data.WardriveDb
import com.meshwardrive.app.data.WardriveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de cobertura: pensada para consultar, no para medir. Mapa de calor de lo medido (sin los
 * puntos sueltos) y, encima de lo que falta, una capa de predicción siempre distinguible de lo real.
 * Al tocar cualquier celda se abre su ficha. Respeta el filtro de wardrives y el de fechas.
 */
class CoverageActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var db: WardriveDb
    private lateinit var dem: DemStore
    private lateinit var map: MapView
    private var folder: FolderOverlay? = null
    private var centered = false

    private var sizeM = 150
    private var measured: Map<CoverageGrid.Key, CoverageGrid.Cell> = emptyMap()
    private var estimates: Map<CoverageGrid.Key, Prediction.Est> = emptyMap()
    private var heardByKey: Map<CoverageGrid.Key, List<Int>> = emptyMap()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> uri?.let { exportImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        MapTheme.applyNightMode(Settings(this).theme)
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
        setContentView(R.layout.activity_coverage)
        settings = Settings(this)
        db = WardriveDb.get(this)
        dem = DemStore(this)

        map = findViewById(R.id.covMap)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(13.0)
        WardriveState.location.value?.let { map.controller.setCenter(GeoPoint(it.latitude, it.longitude)) }
            ?: map.controller.setCenter(GeoPoint(40.4168, -3.7038))
        applyOfflineMap()
        MapTheme.applyToMap(map)
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) showCell(p)
                return p != null
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))

        findViewById<Button>(R.id.covClose).setOnClickListener { finish() }
        findViewById<Button>(R.id.covHelp).setOnClickListener { showHelp() }
        findViewById<android.view.View>(R.id.covLegendCard).setOnClickListener { showHelp() }
        findViewById<Button>(R.id.covLayers).setOnClickListener { showLayers() }
        findViewById<Button>(R.id.covDem).setOnClickListener { showDemDialog() }
        findViewById<Button>(R.id.covExport).setOnClickListener {
            exportLauncher.launch("coverage-map-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.png")
        }
        updateLegend()
        recompute()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    // ------------------------------------------------------------------ datos

    /** Igual que en la pestaña Mapa: wardrives elegidos y filtro de fechas (desde el inicio de cada uno). */
    private fun visible(id: String, meta: Map<String, SessionMeta>): Boolean {
        if (WardriveState.running.value && id == WardriveState.session.value) return true
        val f = settings.sessionFilter
        if (f != null && id !in f) return false
        val from = settings.dateFrom
        val to = settings.dateTo
        if (from > 0 || to > 0) {
            val start = runCatching { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(id)!!.time }.getOrNull()
                ?: meta[id]?.created ?: 0L
            if (start > 0) {
                if (from > 0 && start < from) return false
                if (to > 0 && start >= to) return false
            }
        }
        return true
    }

    private fun recompute() {
        val info = findViewById<TextView>(R.id.covInfo)
        info.setText(R.string.working)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                val meta = db.sessionMeta()
                val sc = Scoring.context(settings.scoringMode, meta)
                val size = settings.cellSizeM
                val pings = db.allPings().filter { visible(it.session, meta) }
                val rx = db.allRx(onlyWithFix = true).filter { visible(it.session, meta) }
                val nodes = db.allNodes()
                val cells = CoverageGrid.index(pings, size, sc)
                val support = Prediction.supportingNodes(pings, nodes, WardriveState.conn.value.myNodeNum)
                val hasDem = dem.tileCount() > 0
                val est = if (settings.covPrediction) {
                    Prediction.estimate(cells, support, size, if (hasDem) { lat, lon -> dem.elevation(lat, lon) } else null)
                } else emptyList()
                val heard = HashMap<CoverageGrid.Key, MutableSet<Int>>()
                for (x in rx) {
                    if (x.hops != 0) continue
                    val k = CoverageGrid.keyOf(x.lat!!, x.lon!!, size.toDouble())
                    heard.getOrPut(k) { LinkedHashSet() }.add(x.from)
                }
                Result(size, cells, est.associateBy { it.cell.key }, heard.mapValues { it.value.toList() }, support, nodes, hasDem)
            }
            sizeM = r.size
            measured = r.cells
            estimates = r.est
            heardByKey = r.heard
            render(r.support, r.nodes)
            val real = measured.values.count { it.state == CoverageGrid.State.COVERED || it.state == CoverageGrid.State.NO_COVERAGE }
            info.text = getString(
                R.string.coverage_info, real, estimates.size, sizeM,
                getString(if (r.hasDem) R.string.dem_yes else R.string.dem_no),
            )
            if (!centered && measured.isNotEmpty()) {
                centered = true
                val lats = measured.values.map { it.latC }
                val lons = measured.values.map { it.lonC }
                map.post {
                    runCatching {
                        map.zoomToBoundingBox(BoundingBox(lats.max(), lons.max(), lats.min(), lons.min()).increaseByScale(1.3f), false)
                    }
                }
            }
        }
    }

    private class Result(
        val size: Int,
        val cells: Map<CoverageGrid.Key, CoverageGrid.Cell>,
        val est: Map<CoverageGrid.Key, Prediction.Est>,
        val heard: Map<CoverageGrid.Key, List<Int>>,
        val support: List<Prediction.Node>,
        val nodes: List<NodeEntry>,
        val hasDem: Boolean,
    )

    // ------------------------------------------------------------------ dibujo

    private fun estColor(e: Prediction.Est): Int = when (e.level) {
        Prediction.Level.LIKELY -> CoverageGrid.GOOD
        Prediction.Level.UNCERTAIN -> CoverageGrid.MARGINAL
        Prediction.Level.UNLIKELY -> CoverageGrid.FAIL
    }

    private fun render(support: List<Prediction.Node>, nodes: List<NodeEntry>) {
        folder?.let { map.overlays.remove(it) }
        val f = FolderOverlay()
        val density = resources.displayMetrics.density
        if (settings.covPrediction && estimates.isNotEmpty()) {
            f.add(HatchedCellsOverlay(estimates.values.map { it.cell to estColor(it) }, density))
        }
        if (settings.covMeasured) {
            val pts = measured.values.mapNotNull { c ->
                when (c.state) {
                    CoverageGrid.State.PENDING -> null
                    else -> HeatmapOverlay.HeatPoint(c.latC, c.lonC, CoverageGrid.cellColor(c))
                }
            }
            if (pts.isNotEmpty()) f.add(HeatmapOverlay(pts, sizeM.toFloat(), density))
            val changed = measured.values.filter { it.trend != CoverageGrid.Trend.NONE }
            if (changed.isNotEmpty() && settings.showTrends) f.add(TrendOverlay(changed, density))
        }
        // Nodos en los que se apoya la predicción (te oyeron y tienen posición)
        val byNum = nodes.associateBy { it.num }
        for (s in support) {
            val m = Marker(map)
            m.position = GeoPoint(s.lat, s.lon)
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_node)?.let { m.icon = it }
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.title = byNum[s.num]?.label ?: "!%08x".format(s.num)
            m.snippet = getString(R.string.cov_node_range, String.format(Locale.getDefault(), "%.1f", s.rangeM / 1000.0))
            f.add(m)
        }
        map.overlays.add(minOf(1, map.overlays.size), f)
        folder = f
        map.invalidate()
    }

    private fun updateLegend() {
        val box = findViewById<LinearLayout>(R.id.covLegend)
        box.removeAllViews()
        val items = listOf(
            CoverageGrid.GOOD to getString(R.string.cov_leg_good),
            CoverageGrid.FAIL to getString(R.string.cov_leg_none),
            CoverageGrid.UNCONFIRMED to "?",
            CoverageGrid.GOOD to "▨ " + getString(R.string.cov_leg_est),
        )
        val gap = (8 * resources.displayMetrics.density).toInt()
        for ((color, label) in items) {
            val s = SpannableString("● $label")
            s.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            box.addView(TextView(this).apply { text = s; textSize = 11f; setPadding(0, 0, gap, 0) })
        }
        box.addView(TextView(this).apply { text = "ⓘ"; textSize = 13f })
    }

    // ------------------------------------------------------------------ ficha

    private fun showCell(g: GeoPoint) {
        val key = CoverageGrid.keyOf(g.latitude, g.longitude, sizeM.toDouble())
        val cell = measured[key]
        val est = if (cell == null || !cell.measured) estimates[key] else null
        val heard = heardByKey[key]?.map { WardriveState.nodeLabel(it) }
        lifecycleScope.launch {
            val ground = withContext(Dispatchers.IO) { runCatching { dem.elevation(g.latitude, g.longitude) }.getOrNull() }
            val text = CellInfo.describe(this@CoverageActivity, cell, est, heard, ground)
            MaterialAlertDialogBuilder(this@CoverageActivity)
                .setTitle(
                    when {
                        cell != null && cell.measured -> R.string.ci_title_real
                        est != null -> R.string.ci_title_est
                        else -> R.string.cell_title
                    }
                )
                .setMessage(text)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    // ------------------------------------------------------------------ capas, relieve, ayuda

    private fun showLayers() {
        val names = arrayOf(getString(R.string.cov_layer_measured), getString(R.string.cov_layer_prediction))
        val checked = booleanArrayOf(settings.covMeasured, settings.covPrediction)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.layers_title)
            .setMultiChoiceItems(names, checked) { _, which, on ->
                if (which == 0) settings.covMeasured = on else settings.covPrediction = on
            }
            .setPositiveButton(R.string.close) { _, _ -> recompute() }
            .show()
    }

    private fun showDemDialog() {
        val n = dem.tileCount()
        val mb = dem.sizeBytes() / (1024.0 * 1024.0)
        val msg = getString(R.string.dem_msg, n, String.format(Locale.US, "%.1f", mb))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dem_title)
            .setMessage(msg)
            .setPositiveButton(R.string.dem_download) { _, _ -> downloadVisibleDem() }
            .setNeutralButton(R.string.dem_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dem.clear() }
                    recompute()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun downloadVisibleDem() {
        val bb = map.boundingBox
        val tiles = dem.tilesFor(bb.lonWest, bb.latSouth, bb.lonEast, bb.latNorth)
        val missing = dem.missing(tiles)
        if (missing.isEmpty()) return toast(getString(R.string.dem_have))
        if (tiles.size > DemStore.MAX_TILES) return toast(getString(R.string.dem_too_big))
        val bar = LinearProgressIndicator(this).apply { isIndeterminate = false; max = missing.size }
        val dlg: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dem_downloading)
            .setView(LinearLayout(this).apply {
                val p = (20 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, 0)
                addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching { dem.download(tiles) { i, _ -> runOnUiThread { bar.progress = i } } }.getOrDefault(0)
            }
            dlg.dismiss()
            toast(if (done > 0) getString(R.string.dem_done, done) else getString(R.string.dem_failed))
            recompute()
        }
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.coverage_help_title)
            .setMessage(R.string.coverage_help_text)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ------------------------------------------------------------------ mapa offline y exportar

    private fun applyOfflineMap() {
        val files = File(filesDir, "maps").listFiles { f -> f.isFile && f.name.endsWith(".map") }?.sortedBy { it.name }
        if (!settings.useOfflineMap || files.isNullOrEmpty()) return
        runCatching {
            MapsForgeTileSource.createInstance(application)
            val source = MapsForgeTileSource.createFromFiles(files.toTypedArray())
            map.setTileProvider(MapsForgeTileProvider(SimpleRegisterReceiver(applicationContext), source, null))
        }
    }

    private fun exportImage(uri: Uri) {
        map.post {
            if (map.width == 0 || map.height == 0) return@post toast(getString(R.string.map_not_ready))
            val bmp = Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            MapTheme.withLightMap(map) { map.draw(c) }
            val d = resources.displayMetrics.density
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f * d }
            val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
            val title = "MeshWardrive · " + getString(R.string.coverage_title)
            val sub = findViewById<TextView>(R.id.covInfo).text.toString()
            val attr = getString(R.string.osm_attr) + " · " + getString(R.string.dem_attr)
            val pad = 8f * d
            val w = maxOf(bold.measureText(title), text.measureText(sub), text.measureText(attr)) + 2 * pad
            val h = 3 * 18f * d + 2 * pad
            val top = bmp.height - h - pad
            c.drawRoundRect(RectF(pad, top, pad + w, top + h), 6f * d, 6f * d, bg)
            var y = top + pad + 12f * d
            c.drawText(title, 2 * pad, y, bold)
            y += 18f * d
            c.drawText(sub, 2 * pad, y, text)
            y += 18f * d
            c.drawText(attr, 2 * pad, y, text)
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            ?: error("cannot open the file")
                    }
                }
                bmp.recycle()
                toast(if (ok.isSuccess) getString(R.string.image_saved) else getString(R.string.error_generic, ok.exceptionOrNull()?.message ?: "?"))
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/**
 * Celdas estimadas: rayadas y translúcidas, para que nunca se confundan con lo medido.
 */
class HatchedCellsOverlay(private val cells: List<Pair<CoverageGrid.Cell, Int>>, density: Float) : Overlay() {

    private val stripes: BitmapShader
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val a = Point()
    private val b = Point()
    private val gp = GeoPoint(0.0, 0.0)

    init {
        val s = (8 * density).toInt().coerceAtLeast(6)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1.6f * density }
        c.drawLine(0f, s.toFloat(), s.toFloat(), 0f, p)
        c.drawLine(-s / 2f, s / 2f, s / 2f, -s / 2f, p)
        c.drawLine(s / 2f, s * 1.5f, s * 1.5f, s / 2f, p)
        stripes = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paint.shader = stripes
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = mapView.projection
        for ((c, color) in cells) {
            gp.setCoords(c.lat1, c.lon0)
            pj.toPixels(gp, a)
            gp.setCoords(c.lat0, c.lon1)
            pj.toPixels(gp, b)
            if (b.x < 0 || b.y < 0 || a.x > mapView.width || a.y > mapView.height) continue
            val r = RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
            tint.color = (color and 0x00FFFFFF) or (0x30 shl 24)
            canvas.drawRect(r, tint)
            paint.colorFilter = PorterDuffColorFilter((color and 0x00FFFFFF) or (0xB0 shl 24), PorterDuff.Mode.SRC_IN)
            canvas.drawRect(r, paint)
        }
    }
}
