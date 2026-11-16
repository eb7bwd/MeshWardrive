package com.meshwardrive.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.meshwardrive.app.R
import com.meshwardrive.app.ble.MeshBle
import com.meshwardrive.app.data.Analysis
import com.meshwardrive.app.data.CoverageExport
import com.meshwardrive.app.data.CoverageGrid
import com.meshwardrive.app.data.MODE_CHANNEL
import com.meshwardrive.app.data.MODE_DIRECT
import com.meshwardrive.app.data.PingRow
import com.meshwardrive.app.data.PingStatus
import com.meshwardrive.app.data.Settings
import com.meshwardrive.app.data.Stats
import com.meshwardrive.app.data.labelRes
import com.meshwardrive.app.data.WardriveDb
import com.meshwardrive.app.data.WardriveState
import com.meshwardrive.app.data.WardriveState.Conn
import com.meshwardrive.app.service.WardriveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var db: WardriveDb
    private lateinit var map: MapView
    private lateinit var renderer: CoverageRenderer
    private var myLocation: MyLocationNewOverlay? = null
    private var centeredOnce = false
    private var offlineActive = false
    private var focusMarker: Marker? = null
    private var selectedSession: String? = null
    private var knownSessions: List<String> = emptyList()
    private var sessionFilter: Set<String>? = null
    private var shownStats = Stats()
    private lateinit var btnSessions: Button
    private lateinit var chart: SignalChartView
    private lateinit var btnSession: Button

    private lateinit var tvConn: TextView
    private lateinit var tvGps: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvLast: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnRun: Button
    private lateinit var btnPing: Button
    private lateinit var tvConnDetail: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvDataStats: TextView
    private lateinit var tvLog: TextView
    private lateinit var pager: ViewPager

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasCorePermissions()) {
            enableMyLocation()
        } else {
            toast(getString(R.string.no_perms))
        }
    }

    private val exportPingsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { u -> export(u) { out, pings, rx, nodes, my -> Exporter.pingsCsv(out, pings, nodes, my) } }
        }
    private val exportRxLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { u -> export(u) { out, _, rx, nodes, my -> Exporter.rxCsv(out, rx, nodes, my) } }
        }
    private val exportGeoLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { u -> export(u) { out, pings, rx, nodes, my -> Exporter.geoJson(out, pings, rx, nodes, my) } }
        }

    private val exportCellsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { uri ->
            val size = settings.cellSizeM
            uri?.let { u -> export(u) { out, pings, rx, _, _ -> CoverageExport.cellsGeoJson(out, pings, rx, size) } }
        }
    private val exportKmlLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri ->
            val size = settings.cellSizeM
            uri?.let { u -> export(u) { out, pings, rx, nodes, _ -> CoverageExport.kml(out, pings, rx, nodes, size) } }
        }
    private val exportImageLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            uri?.let { exportImage(it) }
        }
    private val importMapLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importOfflineMap(it) }
        }

    // ------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid: user agent propio y caché dentro de la app (no hace falta permiso de almacenamiento)
        Configuration.getInstance().apply {
            load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        setContentView(R.layout.activity_main)
        settings = Settings(this)
        db = WardriveDb.get(this)

        tvConn = findViewById(R.id.tvConn)
        tvGps = findViewById(R.id.tvGps)
        tvStats = findViewById(R.id.tvStats)
        tvLast = findViewById(R.id.tvLast)
        btnConnect = findViewById(R.id.btnConnect)
        btnRun = findViewById(R.id.btnRun)
        btnPing = findViewById(R.id.btnPing)
        tvConnDetail = findViewById(R.id.tvConnDetail)
        tvMode = findViewById(R.id.tvMode)
        tvDataStats = findViewById(R.id.tvDataStats)
        tvLog = findViewById(R.id.tvLog)
        chart = findViewById(R.id.chart)
        chart.emptyText = getString(R.string.chart_empty)
        chart.onPick = { lat, lon, label -> focusOnMap(lat, lon, label) }
        btnSession = findViewById(R.id.btnSession)
        sessionFilter = settings.sessionFilter
        btnSessions = findViewById(R.id.btnSessions)
        btnSessions.setOnClickListener { showSessionsFilterDialog() }
        updateSessionsButton()
        btnSession.setOnClickListener { showSessionDialog() }
        setupPages(savedInstanceState == null)

        setupMap()

        btnConnect.setOnClickListener { onConnectClicked() }
        btnRun.setOnClickListener {
            val action = if (WardriveState.running.value) WardriveService.ACTION_STOP else WardriveService.ACTION_START
            WardriveService.send(this, action)
        }
        btnPing.setOnClickListener { WardriveService.send(this, WardriveService.ACTION_PING) }
        findViewById<FloatingActionButton>(R.id.fabCenter).setOnClickListener { centerOnMe() }
        click(R.id.btnLayers) { showLayersDialog() }
        click(R.id.btnTarget) { showTargetDialog() }
        click(R.id.btnExpImage) {
            // La imagen es lo que se ve en el mapa: se vuelve a esa pestaña antes de capturar
            pager.setCurrentItem(0, false)
            exportImageLauncher.launch("coverage-${stamp()}.png")
        }
        click(R.id.btnExpCells) { exportCellsLauncher.launch("coverage-cells-${stamp()}.geojson") }
        click(R.id.btnExpKml) { exportKmlLauncher.launch("coverage-${stamp()}.kml") }
        click(R.id.btnExpGeo) { exportGeoLauncher.launch("wardrive-${stamp()}.geojson") }
        click(R.id.btnExpPings) { exportPingsLauncher.launch("wardrive-pings-${stamp()}.csv") }
        click(R.id.btnExpRx) { exportRxLauncher.launch("wardrive-rx-${stamp()}.csv") }
        click(R.id.btnOffline) { showOfflineDialog() }
        click(R.id.btnClear) { confirmClear() }
        click(R.id.btnAbout) { showAbout() }
        click(R.id.btnTutorial) { startTutorial() }
        click(R.id.btnCopyLog) {
            val text = WardriveState.log.value.joinToString("\n")
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("MeshWardrive", text))
            toast(getString(R.string.log_copied))
        }

        if (settings.disclaimerAccepted) {
            askPermissions()
            if (!settings.tutorialSeen) startTutorial()
        } else {
            showDisclaimer()
        }

        observeState()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        refreshMap()
        updateStatus()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    // ------------------------------------------------------------ pestañas deslizables

    private fun click(id: Int, action: () -> Unit) {
        findViewById<View>(id).setOnClickListener { action() }
    }

    private fun setupPages(firstCreate: Boolean) {
        pager = findViewById(R.id.pager)
        val nav = findViewById<BottomNavigationView>(R.id.nav)
        val ids = intArrayOf(R.id.nav_map, R.id.nav_conn, R.id.nav_data, R.id.nav_log, R.id.nav_settings)
        val pages = ids.size

        // Las páginas ya están en el layout como hijas del ViewPager: el adaptador solo las entrega
        pager.offscreenPageLimit = pages
        pager.adapter = object : PagerAdapter() {
            override fun getCount() = pages
            override fun instantiateItem(container: ViewGroup, position: Int): Any = container.getChildAt(position)
            override fun isViewFromObject(view: View, obj: Any) = view === obj
            override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {}
        }
        nav.setOnItemSelectedListener { item ->
            val i = ids.indexOf(item.itemId)
            if (i >= 0 && pager.currentItem != i) pager.currentItem = i
            true
        }
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                if (nav.selectedItemId != ids[position]) nav.selectedItemId = ids[position]
                updateStatus()
                if (position == 0) refreshMap() // por si han cambiado ajustes (tamaño de celda…)
                if (position == 2) refreshAnalysis()
            }
        })
        if (firstCreate) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    private fun showLayersDialog() {
        val names = arrayOf(
            getString(R.string.layer_heat), getString(R.string.layer_cells), getString(R.string.layer_pings),
            getString(R.string.passive_rx), getString(R.string.layer_nodes),
        )
        val checked = booleanArrayOf(
            settings.showHeat, settings.showCells, settings.showPings, settings.showPassive, settings.showNodes,
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.layers_title)
            .setMultiChoiceItems(names, checked) { _, which, on ->
                when (which) {
                    0 -> settings.showHeat = on
                    1 -> settings.showCells = on
                    2 -> settings.showPings = on
                    3 -> settings.showPassive = on
                    4 -> settings.showNodes = on
                }
                refreshMap()
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ------------------------------------------------------------ análisis: gráfica y repetidores

    private fun refreshAnalysis() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                Triple(
                    db.allPings().filter { sessionVisible(it.session) },
                    db.allRx(onlyWithFix = false).filter { sessionVisible(it.session) },
                    db.allNodes(),
                )
            }
            val st = statsOf(data.first, data.second.size)
            val ok = st.ackRelay + st.ackDest
            val pct = if (ok + st.failed > 0) 100 * ok / (ok + st.failed) else 0
            tvDataStats.text =
                getString(R.string.data_stats, st.sent, ok, pct, st.ackRelay, st.ackDest, st.failed, st.rx)

            // La gráfica es de un solo wardrive: el más reciente de los mostrados, o el que se elija
            val sessions = Analysis.sessions(data.first, data.second)
            knownSessions = sessions
            val session = selectedSession?.takeIf { it in sessions } ?: sessions.firstOrNull()
            btnSession.text = getString(R.string.pick_session, session?.let { sessionLabel(it) } ?: "—")
            chart.series = session?.let { Analysis.series(it, data.first, data.second) }
            showRelays(Analysis.relays(data.first, data.second, data.third, WardriveState.conn.value.myNodeNum))
        }
    }

    private fun showSessionDialog() {
        val sessions = knownSessions
        if (sessions.isEmpty()) return toast(getString(R.string.chart_empty))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chart_title)
            .setItems(sessions.map { sessionLabel(it) }.toTypedArray()) { _, which ->
                selectedSession = sessions[which]
                refreshAnalysis()
            }
            .show()
    }

    private fun showRelays(relays: List<Analysis.RelayStat>) {
        val box = findViewById<LinearLayout>(R.id.relaysContainer)
        box.removeAllViews()
        val pad = (10 * resources.displayMetrics.density).toInt()
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        // Si ningún ping trae relay, el firmware no lo informa: se explica de dónde sale la lista
        findViewById<View>(R.id.tvRelaysNote).visibility =
            if (relays.isNotEmpty() && relays.none { it.pings > 0 }) View.VISIBLE else View.GONE
        if (relays.isEmpty()) {
            box.addView(TextView(this).apply {
                setPadding(0, pad, 0, pad)
                setText(R.string.relays_empty)
            })
            return
        }
        for (r in relays.take(40)) {
            val hex = "0x%02x".format(r.relayByte)
            val name = when (r.candidates.size) {
                0 -> getString(R.string.unknown_node)
                else -> r.candidates.joinToString(" / ") { it.label }
            }
            val detail = buildString {
                val lines = ArrayList<String>()
                if (r.pings > 0) lines.add(getString(R.string.relay_count, r.pings))
                if (r.direct > 0) lines.add(getString(R.string.relay_direct, r.direct))
                if (r.rxPackets > 0) lines.add(getString(R.string.relay_rx, r.rxPackets))
                append(lines.joinToString("\n"))
                r.bestSnr?.let { append("\n").append(getString(R.string.best_snr, "%.1f".format(Locale.US, it))) }
                r.avgSnr?.let { append(" · ").append(getString(R.string.avg_snr, "%.1f".format(Locale.US, it))) }
                if (r.lastTs > 0) {
                    append("\n").append(
                        getString(R.string.seen, SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(r.lastTs)))
                    )
                }
            }
            val title = "$name ($hex)"
            // Si solo hay un candidato y sabemos dónde está, se enseña el nodo; si no, donde mejor se oyó
            val only = r.candidates.singleOrNull()
            val known = only?.lat != null && only.lon != null
            val lat = if (known) only?.lat else r.bestLat
            val lon = if (known) only?.lon else r.bestLon
            box.addView(TextView(this).apply {
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(ripple.resourceId)
                text = "$title\n$detail"
                if (lat != null && lon != null) setOnClickListener { focusOnMap(lat, lon, title) }
            })
        }
    }

    /** Lleva a la pestaña Mapa y marca el punto elegido en la gráfica o en la lista de repetidores. */
    private fun focusOnMap(lat: Double, lon: Double, title: String) {
        pager.setCurrentItem(0, true)
        myLocation?.disableFollowLocation()
        val point = GeoPoint(lat, lon)
        val marker = focusMarker ?: Marker(map).also { focusMarker = it }
        map.overlays.remove(marker)
        marker.position = point
        marker.title = title
        ContextCompat.getDrawable(this, R.drawable.ic_focus)?.let { marker.icon = it }
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        map.overlays.add(marker)
        if (map.zoomLevelDouble < 16.0) map.controller.setZoom(16.0)
        map.controller.animateTo(point)
        marker.showInfoWindow()
        map.invalidate()
    }

    // ------------------------------------------------------------ aviso legal y créditos

    private fun askPermissions() {
        if (!hasCorePermissions()) permLauncher.launch(requiredPermissions()) else enableMyLocation()
    }

    /** Primer arranque: hay que aceptar el aviso para poder usar la app. */
    private fun showDisclaimer() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_text)
            .setCancelable(false)
            .setPositiveButton(R.string.accept) { _, _ ->
                settings.disclaimerAccepted = true
                askPermissions()
                if (!settings.tutorialSeen) startTutorial()
            }
            .setNegativeButton(R.string.exit) { _, _ -> finish() }
            .show()
    }

    /**
     * Recorrido por las pestañas: cada paso abre su pestaña y la explica en un cuadro abajo,
     * para que se vea la pantalla de la que se habla. Se puede saltar en cualquier paso.
     */
    private fun startTutorial(step: Int = 0) {
        val steps = listOf(
            Triple(-1, R.string.app_name, R.string.tut_welcome),
            Triple(1, R.string.tab_conn, R.string.tut_conn),
            Triple(0, R.string.tab_map, R.string.tut_map),
            Triple(2, R.string.tab_data, R.string.tut_data),
            Triple(3, R.string.tab_log, R.string.tut_log),
            Triple(4, R.string.tab_settings, R.string.tut_settings),
        )
        if (step >= steps.size) {
            settings.tutorialSeen = true
            pager.setCurrentItem(0, true)
            return
        }
        val (page, title, body) = steps[step]
        if (page >= 0) pager.setCurrentItem(page, true)
        val last = step == steps.size - 1
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(title) + "  (${step + 1}/${steps.size})")
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(if (last) R.string.close else R.string.next) { _, _ -> startTutorial(step + 1) }
        if (!last) builder.setNegativeButton(R.string.skip) { _, _ -> startTutorial(steps.size) }
        val dialog = builder.show()
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setDimAmount(0.25f)
    }

    private fun showAbout() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val tv = TextView(this).apply {
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
            text = getString(R.string.about_credits) + "\n\n— " + getString(R.string.disclaimer_title) +
                " —\n\n" + getString(R.string.disclaimer_text)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name) + " " + version + " · " + getString(R.string.author))
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ------------------------------------------------------------ mapa

    private fun setupMap() {
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(14.0)
        map.controller.setCenter(GeoPoint(40.4168, -3.7038))
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                InfoWindow.closeAllInfoWindowsOn(map)
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))
        renderer = CoverageRenderer(map)
        applyMapSource()
    }

    // ------------------------------------------------------------ mapas offline (Mapsforge)

    private fun mapsDir(): File = File(filesDir, "maps").apply { mkdirs() }

    private fun offlineMapFiles(): List<File> =
        mapsDir().listFiles { f -> f.isFile && f.name.endsWith(".map") }?.sortedBy { it.name } ?: emptyList()

    /** Usa los .map importados si están activados; si no, las teselas online de OpenStreetMap. */
    private fun applyMapSource() {
        val files = offlineMapFiles()
        if (settings.useOfflineMap && files.isNotEmpty()) {
            try {
                MapsForgeTileSource.createInstance(application)
                val source = MapsForgeTileSource.createFromFiles(files.toTypedArray())
                map.setTileProvider(MapsForgeTileProvider(SimpleRegisterReceiver(applicationContext), source, null))
                offlineActive = true
                map.invalidate()
                return
            } catch (e: Throwable) {
                toast(getString(R.string.off_open_error, e.message ?: "?"))
            }
        }
        if (offlineActive) {
            map.setTileProvider(MapTileProviderBasic(applicationContext, TileSourceFactory.MAPNIK))
            offlineActive = false
            map.invalidate()
        }
    }

    /** Los mapas offline se dibujan en teselas que quedan en caché: hay que vaciarla al cambiar de mapa. */
    private fun purgeOfflineTileCache() {
        runCatching {
            val w = SqlTileWriter()
            w.purgeCache("OSMARENDER")
            w.purgeCache("DEFAULT")
            w.onDetach()
        }
    }

    private fun showOfflineDialog() {
        val files = offlineMapFiles()
        val mb = files.sumOf { it.length() } / (1024 * 1024)
        val items = arrayOf(
            getString(R.string.off_import),
            getString(if (offlineActive) R.string.off_use_online else R.string.off_use_offline),
            getString(R.string.off_where),
            getString(R.string.off_delete, files.size, mb.toInt()),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.offline_map)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> importMapLauncher.launch(arrayOf("*/*"))
                    1 -> {
                        if (!offlineActive && files.isEmpty()) {
                            toast(getString(R.string.off_first_import))
                        } else {
                            settings.useOfflineMap = !offlineActive
                            applyMapSource()
                        }
                    }
                    2 -> runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://download.mapsforge.org/")))
                    }
                    3 -> {
                        files.forEach { it.delete() }
                        purgeOfflineTileCache()
                        settings.useOfflineMap = false
                        applyMapSource()
                        toast(getString(R.string.off_deleted))
                    }
                }
            }
            .show()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun importOfflineMap(uri: Uri) {
        val name = displayName(uri) ?: "map.map"
        if (!name.endsWith(".map", ignoreCase = true)) {
            toast(getString(R.string.off_pick_map))
            return
        }
        val safe = name.dropLast(4).replace(Regex("[^A-Za-z0-9._-]"), "_") + ".map"
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.off_importing)
            .setMessage(getString(R.string.off_copying, name))
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(mapsDir(), "$safe.part")
                    val input = contentResolver.openInputStream(uri) ?: error("cannot read the file")
                    input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                    val dst = File(mapsDir(), safe)
                    dst.delete()
                    if (!tmp.renameTo(dst)) error("cannot save the map")
                    purgeOfflineTileCache()
                }.onFailure { File(mapsDir(), "$safe.part").delete() }
            }
            dialog.dismiss()
            if (result.isSuccess) {
                settings.useOfflineMap = true
                applyMapSource()
                toast(getString(R.string.off_ready))
            } else {
                toast(getString(R.string.error_generic, result.exceptionOrNull()?.message ?: "?"))
            }
        }
    }

    // ------------------------------------------------------------ imagen del mapa

    /** Captura el mapa tal y como se ve (con sus capas) y le añade leyenda, resumen y atribución. */
    private fun captureMapImage(): Bitmap? {
        if (map.width == 0 || map.height == 0) return null
        val bmp = Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        map.draw(c)

        val d = resources.displayMetrics.density
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f * d }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
        val sw = Paint(Paint.ANTI_ALIAS_FLAG)

        val s = shownStats
        val ok = s.ackRelay + s.ackDest
        val pct = if (ok + s.failed > 0) 100 * ok / (ok + s.failed) else 0
        val title = "MeshWardrive · " +
            java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val sub = getString(R.string.image_sub, s.sent, ok, pct, s.failed, settings.cellSizeM)
        val rows = buildList {
            add(CoverageGrid.GOOD to getString(R.string.legend_good))
            add(CoverageGrid.MARGINAL to getString(R.string.legend_marginal))
            add(CoverageGrid.WEAK to getString(R.string.legend_weak))
            add(CoverageGrid.FAIL to getString(R.string.no_response))
            if (settings.showPassive) add(CoverageGrid.PASSIVE to getString(R.string.passive_rx))
        }

        val pad = 8f * d
        val lineH = 18f * d
        val box = 12f * d
        var w = maxOf(bold.measureText(title), text.measureText(sub))
        for (r in rows) w = maxOf(w, box + pad + text.measureText(r.second))
        w += 2 * pad
        val h = 2 * pad + lineH * (rows.size + 2)
        val left = pad
        val top = bmp.height - h - pad
        c.drawRoundRect(RectF(left, top, left + w, top + h), 6f * d, 6f * d, bg)
        var y = top + pad + 12f * d
        c.drawText(title, left + pad, y, bold)
        y += lineH
        c.drawText(sub, left + pad, y, text)
        for (r in rows) {
            y += lineH
            sw.color = r.first
            c.drawRect(left + pad, y - box + 2f * d, left + pad + box, y + 2f * d, sw)
            c.drawText(r.second, left + 2 * pad + box, y, text)
        }

        val small = Paint(text).apply { textSize = 10f * d }
        val attr = getString(R.string.osm_attr)
        val aw = small.measureText(attr)
        c.drawRect(bmp.width - aw - 2 * pad, bmp.height - 18f * d, bmp.width.toFloat(), bmp.height.toFloat(), bg)
        c.drawText(attr, bmp.width - aw - pad, bmp.height - 6f * d, small)
        return bmp
    }

    private fun exportImage(uri: Uri) {
        val bmp = captureMapImage() ?: return toast(getString(R.string.map_not_ready))
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } ?: error("cannot open the file")
                }
            }
            bmp.recycle()
            toast(if (ok.isSuccess) getString(R.string.image_saved) else getString(R.string.error_generic, ok.exceptionOrNull()?.message ?: "?"))
        }
    }

    private fun enableMyLocation() {
        if (myLocation != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        overlay.enableMyLocation()
        overlay.runOnFirstFix {
            runOnUiThread {
                if (!centeredOnce) {
                    centeredOnce = true
                    overlay.myLocation?.let { map.controller.animateTo(it) }
                }
            }
        }
        map.overlays.add(overlay)
        myLocation = overlay
    }

    private fun centerOnMe() {
        val o = myLocation ?: return toast(getString(R.string.waiting_gps))
        val loc = o.myLocation ?: WardriveState.location.value?.let { GeoPoint(it.latitude, it.longitude) }
        if (loc == null) return toast(getString(R.string.waiting_gps))
        map.controller.animateTo(loc)
        if (o.isFollowLocationEnabled) {
            o.disableFollowLocation()
            toast(getString(R.string.follow_off))
        } else {
            o.enableFollowLocation()
            toast(getString(R.string.follow_on))
        }
    }

    private fun refreshMap() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                Triple(
                    db.allPings().filter { sessionVisible(it.session) },
                    db.allRx(onlyWithFix = true).filter { sessionVisible(it.session) },
                    db.allNodes(),
                )
            }
            shownStats = statsOf(data.first, data.second.size)
            renderer.render(
                pings = data.first,
                rx = data.second,
                nodes = data.third,
                cellSizeM = settings.cellSizeM,
                layers = CoverageRenderer.Layers(
                    heat = settings.showHeat,
                    cells = settings.showCells,
                    pings = settings.showPings,
                    passive = settings.showPassive,
                    nodes = settings.showNodes,
                ),
            )
        }
    }

    // ------------------------------------------------------------ filtro de wardrives

    /** Un wardrive se ve si no hay filtro, si está elegido o si es el que está en marcha. */
    private fun sessionVisible(id: String): Boolean {
        val f = sessionFilter ?: return true
        return id in f || (WardriveState.running.value && id == WardriveState.session.value)
    }

    private fun statsOf(pings: List<PingRow>, rx: Int): Stats {
        val relay = pings.count { it.status == PingStatus.ACK_RELAY }
        val dest = pings.count { it.status == PingStatus.ACK_DEST }
        val failed = pings.count { it.status.isFail }
        return Stats(
            sent = pings.size, ackRelay = relay, ackDest = dest, failed = failed,
            pending = pings.size - relay - dest - failed, rx = rx,
        )
    }

    /** 20261116-221000 → 16/11/2026 22:10 */
    private fun sessionLabel(id: String): String = runCatching {
        val d = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(id)!!
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(d)
    }.getOrDefault(id)

    private fun showSessionsFilterDialog() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { db.allPings() to db.allRx(onlyWithFix = false) }
            val sessions = Analysis.sessions(data.first, data.second)
            if (sessions.isEmpty()) {
                toast(getString(R.string.chart_empty))
                return@launch
            }
            val pingCount = data.first.groupingBy { it.session }.eachCount()
            val rxCount = data.second.groupingBy { it.session }.eachCount()
            val labels = sessions.map {
                "${sessionLabel(it)}\n${pingCount[it] ?: 0} pings · ${rxCount[it] ?: 0} rx"
            }.toTypedArray()
            val current = sessionFilter
            val checked = BooleanArray(sessions.size) { current == null || sessions[it] in current }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.sessions_title)
                .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
                .setNeutralButton(R.string.sessions_all) { _, _ -> applySessionFilter(null) }
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val picked = sessions.filterIndexed { i, _ -> checked[i] }.toSet()
                    // Ninguno o todos marcados equivale a "todos": así los wardrives futuros también se ven
                    applySessionFilter(if (picked.isEmpty() || picked.size == sessions.size) null else picked)
                }
                .show()
        }
    }

    private fun applySessionFilter(filter: Set<String>?) {
        sessionFilter = filter
        settings.sessionFilter = filter
        selectedSession = null
        updateSessionsButton()
        refreshMap()
        refreshAnalysis()
    }

    private fun updateSessionsButton() {
        val f = sessionFilter
        val what = when {
            f == null -> getString(R.string.sessions_all)
            f.size == 1 -> sessionLabel(f.first())
            else -> getString(R.string.sessions_n, f.size)
        }
        btnSessions.text = getString(R.string.sessions_btn, what)
    }

    // ------------------------------------------------------------ estado

    @OptIn(FlowPreview::class)
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { WardriveState.conn.collect { updateStatus() } }
                launch { WardriveState.running.collect { updateStatus() } }
                launch { WardriveState.stats.collect { updateStatus() } }
                launch { WardriveState.lastPing.collect { updateStatus() } }
                launch { WardriveState.blocker.collect { updateStatus() } }
                launch {
                    WardriveState.dataVersion.debounce(1_500).collect {
                        refreshMap()
                        if (pager.currentItem == 2) refreshAnalysis()
                    }
                }
                launch { WardriveState.log.collect { lines -> tvLog.text = lines.asReversed().joinToString("\n") } }
                launch {
                    while (true) {
                        updateGps()
                        delay(1_000)
                    }
                }
            }
        }
    }

    private fun updateStatus() {
        val c = WardriveState.conn.value
        val running = WardriveState.running.value
        tvConn.text = when (c.conn) {
            Conn.DISCONNECTED -> "○ " + getString(R.string.st_no_node) + (c.message?.let { " · $it" } ?: "")
            Conn.CONNECTING -> "◌ " +
                (c.message ?: getString(R.string.st_connecting_to, c.deviceName ?: c.address ?: "?"))
            Conn.CONFIGURING -> "◌ " + getString(R.string.st_configuring)
            Conn.READY -> {
                val node = c.myName ?: "!%08x".format(c.myNodeNum)
                val mode = if (settings.mode == MODE_DIRECT) {
                    val t = settings.targetNode
                    getString(
                        R.string.st_direct_to,
                        if (t != 0) WardriveState.nodeLabel(t) else getString(R.string.st_pick_target),
                    )
                } else {
                    c.channelIndex?.let { getString(R.string.st_channel, c.channelName ?: "", it) }
                        ?: getString(R.string.st_missing_channel, settings.channelName)
                }
                "● $node · $mode"
            }
        }
        val s = WardriveState.stats.value
        val ok = s.ackRelay + s.ackDest
        val pct = if (ok + s.failed > 0) 100 * ok / (ok + s.failed) else 0
        tvStats.text = getString(R.string.stats_line, s.sent, ok, pct, s.failed, s.rx)
        tvLast.text = WardriveState.lastPing.value?.let { p ->
            buildString {
                append(
                    getString(
                        R.string.last_ping,
                        p.text.split(' ').take(2).joinToString(" "),
                        getString(p.status.labelRes),
                    )
                )
                p.relayNode?.let { append(" · ").append(getString(R.string.via, WardriveState.relayLabel(it))) }
                (p.ackSnr ?: p.relaySnr)?.let { append(" · SNR %.1f".format(it)) }
                (p.ackRssi ?: p.relayRssi)?.let { append(" · RSSI $it") }
            }
        } ?: getString(R.string.last_none)
        WardriveState.blocker.value?.let { tvLast.append("\n⏸ $it") }

        tvConnDetail.text = buildString {
            append(
                getString(
                    when (c.conn) {
                        Conn.DISCONNECTED -> R.string.cd_disconnected
                        Conn.CONNECTING -> R.string.cd_connecting
                        Conn.CONFIGURING -> R.string.cd_configuring
                        Conn.READY -> R.string.cd_ready
                    }
                )
            )
            c.message?.let { append("\n").append(it) }
            (c.deviceName ?: c.address)?.let { append("\n").append(getString(R.string.cd_device, it)) }
            if (c.myNodeNum != 0) {
                append("\n").append(getString(R.string.cd_node, (c.myName ?: "") + " (!%08x)".format(c.myNodeNum)))
            }
            if (c.conn == Conn.READY) {
                val idx = c.channelIndex
                append("\n")
                append(
                    if (idx != null) getString(R.string.cd_channel_found, settings.channelName, idx)
                    else getString(R.string.cd_channel_missing, settings.channelName)
                )
                append("\n").append(getString(R.string.cd_nodes, WardriveState.nodes.value.size))
            }
        }
        tvMode.text = if (settings.mode == MODE_DIRECT) {
            val t = settings.targetNode
            getString(
                R.string.mode_direct_desc,
                if (t != 0) WardriveState.nodeLabel(t) else getString(R.string.mode_direct_pick),
            )
        } else {
            getString(R.string.mode_channel_desc, settings.channelName)
        }

        btnConnect.setText(if (c.conn == Conn.DISCONNECTED) R.string.connect else R.string.disconnect)
        btnRun.setText(if (running) R.string.stop else R.string.start)
        btnRun.isEnabled = c.conn == Conn.READY || running
        btnPing.isEnabled = c.conn == Conn.READY
    }

    private fun updateGps() {
        val l = WardriveState.location.value
        tvGps.text = if (l == null) {
            getString(R.string.gps_none)
        } else {
            val age = ((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000).toInt()
            val acc = if (l.hasAccuracy()) "±${l.accuracy.toInt()} m" else "±? m"
            getString(R.string.gps_line, acc, age) +
                (if (WardriveState.running.value) " · " + getString(R.string.gps_running) else "")
        }
    }

    // ------------------------------------------------------------ conexión

    private fun onConnectClicked() {
        if (WardriveState.conn.value.conn != Conn.DISCONNECTED) {
            WardriveService.send(this, WardriveService.ACTION_DISCONNECT)
            return
        }
        if (!settings.disclaimerAccepted) {
            showDisclaimer()
            return
        }
        if (!hasCorePermissions()) {
            permLauncher.launch(requiredPermissions())
            return
        }
        showScanDialog()
    }

    @SuppressLint("MissingPermission")
    private fun showScanDialog() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast(getString(R.string.enable_bt))
            return
        }
        val found = LinkedHashMap<String, String>() // dirección -> etiqueta
        val names = HashMap<String, String?>()
        settings.lastAddress?.let { found[it] = getString(R.string.last_used, settings.lastDeviceName ?: it) }
        adapter.bondedDevices?.forEach {
            if (!found.containsKey(it.address)) found[it.address] = "${it.name ?: "?"} (${getString(R.string.paired)})"
            names[it.address] = it.name
        }
        val listAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        val addresses = ArrayList<String>()
        fun refresh() {
            addresses.clear()
            addresses.addAll(found.keys)
            listAdapter.clear()
            listAdapter.addAll(found.entries.map { "${it.value}\n${it.key}" })
            listAdapter.notifyDataSetChanged()
        }
        refresh()

        val scanner = adapter.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val uuids = r.scanRecord?.serviceUuids
                val name = r.device.name ?: r.scanRecord?.deviceName
                val isMesh = uuids?.any { it.uuid == MeshBle.SERVICE_UUID } == true ||
                    name?.contains("meshtastic", ignoreCase = true) == true
                if (!isMesh) return
                runOnUiThread {
                    found[r.device.address] = "📡 ${name ?: "Meshtastic"}  (${r.rssi} dBm)"
                    names[r.device.address] = name
                    refresh()
                }
            }
        }
        val handler = Handler(Looper.getMainLooper())
        var scanning = false
        val stopScan = Runnable {
            if (scanning) {
                scanning = false
                runCatching { scanner?.stopScan(callback) }
            }
        }
        runCatching {
            scanner?.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
            scanning = scanner != null
        }
        handler.postDelayed(stopScan, 15_000)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_node_title)
            .setAdapter(listAdapter) { _, which ->
                val addr = addresses.getOrNull(which) ?: return@setAdapter
                val name = names[addr] ?: settings.lastDeviceName.takeIf { addr == settings.lastAddress }
                settings.lastAddress = addr
                settings.lastDeviceName = name
                WardriveService.send(this, WardriveService.ACTION_CONNECT, addr, name)
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                handler.removeCallbacks(stopScan)
                stopScan.run()
            }
            .show()
        toast(getString(R.string.scanning_toast))
    }

    // ------------------------------------------------------------ menú

    private fun showTargetDialog() {
        val my = WardriveState.conn.value.myNodeNum
        val nodes = WardriveState.nodes.value.values
            .filter { it.num != my }
            .sortedByDescending { it.lastHeard }
        val labels = mutableListOf(getString(R.string.target_none))
        labels += nodes.map { it.label + "  (!" + "%08x".format(it.num) + ")" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.target_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    settings.targetNode = 0
                    settings.setMode(MODE_CHANNEL)
                    toast(getString(R.string.mode_channel_toast))
                } else {
                    val n = nodes[which - 1]
                    settings.targetNode = n.num
                    settings.setMode(MODE_DIRECT)
                    toast(getString(R.string.mode_direct_toast, n.label))
                }
                updateStatus()
            }
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.clearMeasurements() }
                    WardriveState.stats.value = withContext(Dispatchers.IO) { db.stats() }
                    WardriveState.lastPing.value = null
                    WardriveState.bumpData()
                    toast(getString(R.string.data_cleared))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun export(
        uri: android.net.Uri,
        block: (OutputStream, List<com.meshwardrive.app.data.PingRow>, List<com.meshwardrive.app.data.RxRow>,
                Map<Int, com.meshwardrive.app.data.NodeEntry>, Int) -> Unit,
    ) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val pings = db.allPings().filter { sessionVisible(it.session) }
                    val rx = db.allRx(onlyWithFix = false).filter { sessionVisible(it.session) }
                    val nodes = db.allNodes().associateBy { it.num }
                    contentResolver.openOutputStream(uri)?.use { out ->
                        block(out, pings, rx, nodes, WardriveState.conn.value.myNodeNum)
                    } ?: error("cannot open the file")
                }
            }
            toast(if (ok.isSuccess) getString(R.string.exported) else getString(R.string.error_generic, ok.exceptionOrNull()?.message ?: "?"))
        }
    }

    // ------------------------------------------------------------ utilidades

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun hasCorePermissions(): Boolean = requiredPermissions()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS && it != Manifest.permission.ACCESS_COARSE_LOCATION }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun stamp() = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
