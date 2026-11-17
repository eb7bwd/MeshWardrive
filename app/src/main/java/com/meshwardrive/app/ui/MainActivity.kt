package com.meshwardrive.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.meshwardrive.app.R
import com.meshwardrive.app.ble.MeshBle
import com.meshwardrive.app.data.Analysis
import com.meshwardrive.app.data.CoverageExport
import com.meshwardrive.app.data.CoverageGrid
import com.meshwardrive.app.data.Geo
import com.meshwardrive.app.data.MODE_CHANNEL
import com.meshwardrive.app.data.MODE_DIRECT
import com.meshwardrive.app.data.MODE_LISTEN
import com.meshwardrive.app.data.NodeEntry
import com.meshwardrive.app.data.PingRow
import com.meshwardrive.app.data.PingStatus
import com.meshwardrive.app.data.RxRow
import com.meshwardrive.app.data.SessionMeta
import com.meshwardrive.app.data.Settings
import com.meshwardrive.app.data.ShareFormat
import com.meshwardrive.app.data.Stats
import com.meshwardrive.app.data.TextSafety
import com.meshwardrive.app.data.WardriveDb
import com.meshwardrive.app.data.WardriveState
import com.meshwardrive.app.data.WardriveState.Conn
import com.meshwardrive.app.data.labelRes
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var db: WardriveDb
    private var storageDefaultColor: android.content.res.ColorStateList? = null
    private lateinit var map: MapView
    private lateinit var renderer: CoverageRenderer
    private var myLocation: MyLocationNewOverlay? = null
    private var centeredOnce = false
    private var offlineActive = false
    private var focusMarker: Marker? = null

    // Wardrives: filtro, nombres y gráfica
    private var sessionFilter: Set<String>? = null
    private var meta: Map<String, SessionMeta> = emptyMap()
    private var knownSessions: List<String> = emptyList()
    /** Wardrive de la gráfica; null = el más reciente. */
    private var chartSession: String? = null
    private var chartAll = false
    private var shownStats = Stats()

    // Filtros del mapa
    private class RelayFilter(val byte: Int, val node: NodeEntry?, val label: String)
    private var relayFilter: RelayFilter? = null
    private var compareSets: Pair<Set<String>, Set<String>>? = null

    private var pendingImport: Uri? = null

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
    private lateinit var chart: SignalChartView
    private lateinit var btnSessions: Button
    private lateinit var btnSession: Button
    private lateinit var dataTabs: MaterialButtonToggleGroup

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
            uri?.let { u -> export(u) { out, pings, _, nodes, my -> Exporter.pingsCsv(out, pings, nodes, my) } }
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
    private val importShareLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importShared(it) }
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
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(prefSync)
        sessionFilter = settings.sessionFilter

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
        btnSessions = findViewById(R.id.btnSessions)
        btnSession = findViewById(R.id.btnSession)
        dataTabs = findViewById(R.id.dataTabs)

        chart = findViewById(R.id.chart)
        chart.emptyText = getString(R.string.chart_empty)
        chart.mapHint = getString(R.string.map_show)
        chart.statusText = { getString(it.labelRes) }
        chart.onPick = { lat, lon, label -> focusOnMap(lat, lon, label) }

        setupPages(savedInstanceState == null)
        setupDataTabs()
        setupMap()
        updateSessionsButton()

        btnConnect.setOnClickListener { onConnectClicked() }
        btnRun.setOnClickListener {
            val action = if (WardriveState.running.value) WardriveService.ACTION_STOP else WardriveService.ACTION_START
            WardriveService.send(this, action)
        }
        btnPing.setOnClickListener { WardriveService.send(this, WardriveService.ACTION_PING) }
        findViewById<FloatingActionButton>(R.id.fabCenter).setOnClickListener { centerOnMe() }
        click(R.id.cardStatus) { toggleStatusCard() }
        click(R.id.btnFilterClose) { clearMapFilters() }
        click(R.id.btnLayers) { showLayersDialog() }
        click(R.id.btnTarget) { showTargetDialog() }
        click(R.id.btnSessions) { showSessionsFilterDialog() }
        click(R.id.btnSession) { showChartSessionDialog() }
        click(R.id.btnShare) { pickWardrivesToShare() }
        click(R.id.btnImport) { importShareLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
        click(R.id.btnReport) { showReport() }
        click(R.id.btnCompare) { startCompare() }
        click(R.id.btnHowCalc) { showTextDialog(getString(R.string.how_calc_btn), getString(R.string.how_calc_text)) }
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
        handleIncoming(intent)
        observeState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        refreshMap()
        updateStatus()
        checkStorage()
    }

    /** Aviso, una vez por nivel, cuando la recepción pasiva se acerca al límite y antes de que se borre nada. */
    private fun checkStorage() {
        if (!settings.disclaimerAccepted) return
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { db.rxCount() }.getOrNull() } ?: return@launch
            val level = WardriveDb.storageLevel(n)
            val prev = settings.storageShown
            if (level < prev) settings.storageShown = level
            if (level <= prev || isFinishing) return@launch
            settings.storageShown = level
            val b = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.storage_title)
                .setMessage(getString(R.string.storage_dialog_msg, WardriveDb.storagePct(n), WardriveDb.MAX_RX_ROWS))
                .setPositiveButton(R.string.storage_manage) { _, _ ->
                    pager.setCurrentItem(2, true)
                    dataTabs.check(R.id.segWardrives)
                }
                .setNegativeButton(R.string.later, null)
            if (settings.recordPassive) {
                b.setNeutralButton(R.string.storage_passive_off) { _, _ ->
                    settings.recordPassive = false
                    toast(getString(R.string.storage_passive_off_done))
                }
            }
            b.show()
        }
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    // El modo se puede cambiar en Ajustes o en Conexión: la pestaña Conexión se refresca en ambos casos
    private val prefSync = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "mode" || key == "target_node") runOnUiThread { updateStatus() }
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(prefSync)
        super.onDestroy()
    }

    // ------------------------------------------------------------ pestañas

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
                if (position == 2) refreshDataPanel()
            }
        })
        if (firstCreate) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    /** La pestaña Datos tiene tres apartados: Wardrives, Análisis y Exportar. */
    private fun setupDataTabs() {
        dataTabs.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            findViewById<View>(R.id.panelWardrives).visibility = if (id == R.id.segWardrives) View.VISIBLE else View.GONE
            findViewById<View>(R.id.panelAnalysis).visibility = if (id == R.id.segAnalysis) View.VISIBLE else View.GONE
            findViewById<View>(R.id.panelExport).visibility = if (id == R.id.segExport) View.VISIBLE else View.GONE
            refreshDataPanel()
        }
        dataTabs.check(R.id.segWardrives)
    }

    private fun refreshDataPanel() {
        when (dataTabs.checkedButtonId) {
            R.id.segWardrives -> refreshWardrives()
            R.id.segAnalysis -> refreshAnalysis()
        }
    }

    private fun showLayersDialog() {
        val names = arrayOf(
            getString(R.string.layer_heat), getString(R.string.layer_cells), getString(R.string.layer_pings),
            getString(R.string.passive_rx), getString(R.string.layer_nodes), getString(R.string.layer_route),
            getString(R.string.layer_gaps),
        )
        val checked = booleanArrayOf(
            settings.showHeat, settings.showCells, settings.showPings, settings.showPassive, settings.showNodes,
            settings.showRoute, settings.showGaps,
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
                    5 -> settings.showRoute = on
                    6 -> settings.showGaps = on
                }
                refreshMap()
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ------------------------------------------------------------ wardrives

    /** Wardrives con datos, del más reciente al más antiguo, y los nombres guardados. */
    private class WardriveList(
        val sessions: List<String>,
        val meta: Map<String, SessionMeta>,
        val pings: Map<String, Int>,
        val rx: Map<String, Int>,
    )

    private fun loadWardriveList(): WardriveList {
        val pings = db.allPings()
        val rx = db.allRx(onlyWithFix = false)
        val track = db.allTrack()
        return WardriveList(
            sessions = Analysis.sessions(pings, rx, track),
            meta = db.sessionMeta(),
            pings = pings.groupingBy { it.session }.eachCount(),
            rx = rx.groupingBy { it.session }.eachCount(),
        )
    }

    private val dateFmt get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    /** Nombre del wardrive si lo tiene; si no, su fecha. Los importados indican quién los compartió. */
    private fun sessionLabel(id: String): String {
        val m = meta[id]
        val base = m?.name
            ?: runCatching { dateFmt.format(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(id)!!) }.getOrNull()
            ?: m?.created?.takeIf { it > 0 }?.let { dateFmt.format(Date(it)) }
            ?: id
        return if (m?.importedFrom != null) "$base · ${m.importedFrom}" else base
    }

    private fun sessionDate(id: String): String? {
        val m = meta[id]
        if (m?.name == null && m?.importedFrom == null) return null
        return runCatching { dateFmt.format(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(id)!!) }.getOrNull()
            ?: m?.created?.takeIf { it > 0 }?.let { dateFmt.format(Date(it)) }
    }

    private fun refreshWardrives() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { loadWardriveList() }
            meta = list.meta
            updateSessionsButton()
            val box = findViewById<LinearLayout>(R.id.wardrivesContainer)
            box.removeAllViews()
            val rxTotal = list.rx.values.sum()
            findViewById<TextView>(R.id.tvStorage).apply {
                visibility = if (rxTotal > 0) View.VISIBLE else View.GONE
                text = getString(
                    R.string.storage_line, rxTotal, WardriveDb.MAX_RX_ROWS, WardriveDb.storagePct(rxTotal),
                ) + if (WardriveDb.storageLevel(rxTotal) > 0) "\n" + getString(R.string.storage_line_warn) else ""
                if (storageDefaultColor == null) storageDefaultColor = textColors
                if (WardriveDb.storageLevel(rxTotal) > 0) setTextColor(0xFFE65100.toInt())
                else setTextColor(storageDefaultColor)
            }
            val pad = (10 * resources.displayMetrics.density).toInt()
            val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
            if (list.sessions.isEmpty()) {
                box.addView(TextView(this@MainActivity).apply {
                    setPadding(0, pad, 0, pad)
                    setText(R.string.chart_empty)
                })
                return@launch
            }
            for (id in list.sessions) {
                val running = WardriveState.running.value && id == WardriveState.session.value
                val title = sessionLabel(id) + (if (running) "  ●" else "") + "  ⋮"
                val sub = buildString {
                    sessionDate(id)?.let { append(it).append(" · ") }
                    append(getString(R.string.wd_counts, list.pings[id] ?: 0, list.rx[id] ?: 0))
                }
                val text = SpannableString("$title\n$sub")
                text.setSpan(
                    android.text.style.StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                box.addView(TextView(this@MainActivity).apply {
                    setPadding(pad, pad, pad, pad)
                    setBackgroundResource(ripple.resourceId)
                    this.text = text
                    alpha = if (sessionVisible(id)) 1f else 0.5f
                    setOnClickListener { showWardriveActions(id) }
                })
            }
        }
    }

    private fun showWardriveActions(id: String) {
        val items = arrayOf(
            getString(R.string.wd_show_only), getString(R.string.wd_rename),
            getString(R.string.wd_share), getString(R.string.delete),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(sessionLabel(id))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        applySessionFilter(setOf(id))
                        pager.setCurrentItem(0, true)
                    }
                    1 -> renameWardrive(id)
                    2 -> shareWardrives(listOf(id))
                    3 -> confirmDeleteWardrive(id)
                }
            }
            .show()
    }

    private fun renameWardrive(id: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(meta[id]?.name.orEmpty())
            setSelection(text.length)
            filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(64))
        }
        val box = FrameLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = TextSafety.clean(input.text.toString(), 64)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.renameSession(id, name) }
                    afterWardrivesChanged()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteWardrive(id: String) {
        if (WardriveState.running.value && id == WardriveState.session.value) {
            toast(getString(R.string.cannot_delete_running))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_wd_title, sessionLabel(id)))
            .setMessage(R.string.delete_wd_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.deleteSession(id) }
                    sessionFilter?.let { f ->
                        val nf = f - id
                        applySessionFilter(nf.ifEmpty { null })
                    }
                    if (chartSession == id) chartSession = null
                    WardriveState.stats.value = withContext(Dispatchers.IO) { db.stats() }
                    afterWardrivesChanged()
                    toast(getString(R.string.data_cleared))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun afterWardrivesChanged() {
        WardriveState.bumpData()
        refreshWardrives()
        refreshAnalysis()
        refreshMap()
    }

    // ------------------------------------------------------------ compartir e importar (.mwd)

    private fun pickWardrivesToShare() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { loadWardriveList() }
            meta = list.meta
            if (list.sessions.isEmpty()) return@launch toast(getString(R.string.chart_empty))
            val labels = list.sessions.map { sessionLabel(it) }.toTypedArray()
            val checked = BooleanArray(list.sessions.size) { sessionVisible(list.sessions[it]) }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.share_pick_title)
                .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.next) { _, _ ->
                    val picked = list.sessions.filterIndexed { i, _ -> checked[i] }
                    if (picked.isNotEmpty()) shareWardrives(picked)
                }
                .show()
        }
    }

    /** Pregunta por la privacidad del archivo, lo genera y abre el menú de compartir del sistema. */
    private fun shareWardrives(ids: List<String>) {
        val opts = arrayOf(getString(R.string.share_trim), getString(R.string.share_strip))
        val checked = booleanArrayOf(true, false)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_privacy_title)
            .setMultiChoiceItems(opts, checked) { _, which, on -> checked[which] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wd_share) { _, _ ->
                buildAndShare(ids, trim = checked[0], strip = checked[1])
            }
            .show()
        toast(getString(R.string.share_privacy_msg))
    }

    private fun buildAndShare(ids: List<String>, trim: Boolean, strip: Boolean) {
        val progress = showProgress()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val wanted = ids.toSet()
                    val pings = db.allPings().filter { it.session in wanted }.groupBy { it.session }
                    val rx = db.allRx(onlyWithFix = false).filter { it.session in wanted }.groupBy { it.session }
                    val track = db.allTrack().filter { it.session in wanted }.groupBy { it.session }
                    val m = db.sessionMeta()
                    val wardrives = ids.map { id ->
                        val p = pings[id].orEmpty()
                        val r = rx[id].orEmpty()
                        val t = track[id].orEmpty()
                        val created = m[id]?.created?.takeIf { it > 0 }
                            ?: listOfNotNull(p.minOfOrNull { it.ts }, r.minOfOrNull { it.ts }, t.minOfOrNull { it.ts }).minOrNull()
                            ?: System.currentTimeMillis()
                        ShareFormat.prepare(ShareFormat.Wardrive(id, m[id]?.name, created, p, r, t), trim, strip)
                    }
                    val sharedBy = settings.shareName ?: WardriveState.conn.value.myName ?: getString(R.string.app_name)
                    val text = ShareFormat.encode(sharedBy, System.currentTimeMillis(), wardrives)
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, "meshwardrive-${stamp()}.${ShareFormat.EXTENSION}")
                    file.writeText(text, Charsets.UTF_8)
                    file
                }
            }
            progress.dismiss()
            val file = result.getOrElse {
                toast(getString(R.string.error_generic, it.message ?: "?"))
                return@launch
            }
            shareFile(file, ShareFormat.MIME)
        }
    }

    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.wd_share))) }
    }

    /** Archivo abierto o recibido desde otra app. */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        uri ?: return
        // Que no se vuelva a importar al girar la pantalla
        setIntent(Intent(this, MainActivity::class.java))
        if (settings.disclaimerAccepted) importShared(uri) else pendingImport = uri
    }

    /**
     * Importa un .mwd. El archivo se trata como no fiable: se lee con límite de tamaño, se valida
     * entero y se enseña un resumen antes de guardar nada.
     */
    private fun importShared(uri: Uri) {
        val progress = showProgress()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.use { ShareFormat.readText(it) }
                        ?: throw ShareFormat.ShareException("cannot read the file")
                    ShareFormat.decode(text, System.currentTimeMillis()) to db.rxCount()
                }
            }
            progress.dismiss()
            val (d, rxNow) = result.getOrElse {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.import_btn)
                    .setMessage(getString(R.string.import_error, it.message ?: "?"))
                    .setPositiveButton(R.string.close, null)
                    .show()
                return@launch
            }
            val times = d.wardrives.map { it.created }
            val dayFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val summary = getString(
                R.string.import_summary,
                d.sharedBy,
                d.wardrives.size,
                dayFmt.format(Date(times.min())),
                dayFmt.format(Date(times.max())),
                d.wardrives.sumOf { it.pings.size },
                d.wardrives.sumOf { it.rx.size },
                d.discarded,
            )
            // Si la importación pasaría del límite, se avisa antes: se borrarían recepciones antiguas.
            val rxAfter = rxNow + d.wardrives.sumOf { it.rx.size }
            val overflow = rxAfter - WardriveDb.MAX_RX_ROWS
            val message = if (overflow > 0) summary + "\n\n" + getString(R.string.import_over_limit, overflow)
            else summary
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.import_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.import_confirm) { _, _ -> saveImported(d) }
                .show()
        }
    }

    private fun saveImported(d: ShareFormat.Decoded) {
        val progress = showProgress()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { d.wardrives.count { db.importWardrive(it, d.sharedBy) } }
            }
            progress.dismiss()
            val n = result.getOrElse {
                toast(getString(R.string.error_generic, it.message ?: "?"))
                return@launch
            }
            toast(if (n == 0) getString(R.string.import_dup) else getString(R.string.import_done, n))
            if (n > 0) {
                // Lo importado se ve aunque haya un filtro activo
                sessionFilter?.let { f -> applySessionFilter(f + d.wardrives.map { it.id }) }
                afterWardrivesChanged()
            }
        }
    }

    // ------------------------------------------------------------ análisis

    private fun refreshAnalysis() {
        lifecycleScope.launch {
            val m = withContext(Dispatchers.IO) { db.sessionMeta() }
            val (pings, rx, nodes) = withContext(Dispatchers.IO) {
                Triple(
                    db.allPings().filter { sessionVisible(it.session) },
                    db.allRx(onlyWithFix = false).filter { sessionVisible(it.session) },
                    db.allNodes(),
                )
            }
            meta = m
            val st = statsOf(pings, rx.size)
            val ok = st.ackRelay + st.ackDest
            val pct = if (ok + st.failed > 0) 100 * ok / (ok + st.failed) else 0
            tvDataStats.text =
                getString(R.string.data_stats, st.sent, ok, pct, st.ackRelay, st.ackDest, st.failed, st.rx)

            val sessions = Analysis.sessions(pings, rx)
            knownSessions = sessions
            if (chartAll && sessions.size > 1) {
                btnSession.text = getString(R.string.pick_session, getString(R.string.chart_all))
                chart.series = Analysis.series(sessions, pings, rx)
            } else {
                val session = chartSession?.takeIf { it in sessions } ?: sessions.firstOrNull()
                btnSession.text = getString(R.string.pick_session, session?.let { sessionLabel(it) } ?: "—")
                chart.series = session?.let { Analysis.series(it, pings, rx) }
            }
            showRelays(Analysis.relays(pings, rx, nodes, WardriveState.conn.value.myNodeNum))
        }
    }

    private fun showChartSessionDialog() {
        val sessions = knownSessions
        if (sessions.isEmpty()) return toast(getString(R.string.chart_empty))
        val labels = listOf(getString(R.string.chart_all)) + sessions.map { sessionLabel(it) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chart_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    chartAll = true
                } else {
                    chartAll = false
                    chartSession = sessions[which - 1]
                }
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
            val title = "$name ($hex)  ⋮"
            box.addView(TextView(this).apply {
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(ripple.resourceId)
                text = "$title\n$detail"
                setOnClickListener { showRelayActions(r, "$name ($hex)") }
            })
        }
    }

    private fun showRelayActions(r: Analysis.RelayStat, title: String) {
        val only = r.candidates.singleOrNull()
        val known = only?.lat != null && only.lon != null
        val lat = if (known) only?.lat else r.bestLat
        val lon = if (known) only?.lon else r.bestLon
        val items = arrayOf(getString(R.string.map_show), getString(R.string.relay_coverage))
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (lat != null && lon != null) focusOnMap(lat, lon, title) else toast(getString(R.string.relay_range_unknown))
                    1 -> {
                        compareSets = null
                        relayFilter = RelayFilter(r.relayByte, only, title)
                        pager.setCurrentItem(0, true)
                        refreshMap()
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------ informe y comparación

    private fun showReport() {
        lifecycleScope.launch {
            val rep = withContext(Dispatchers.IO) {
                Analysis.report(
                    db.allPings().filter { sessionVisible(it.session) },
                    db.allRx(onlyWithFix = false).filter { sessionVisible(it.session) },
                    db.allTrack().filter { sessionVisible(it.session) },
                    settings.cellSizeM,
                )
            }
            if (rep.wardrives == 0) return@launch toast(getString(R.string.chart_empty))
            val mins = (rep.durationMs / 60_000L).toInt()
            val text = getString(
                R.string.report_text,
                rep.wardrives,
                String.format(Locale.getDefault(), "%.1f", rep.distanceM / 1000.0),
                "%d:%02d h".format(mins / 60, mins % 60),
                rep.pings,
                rep.successPct,
                rep.rxPackets,
                rep.cells,
                rep.coveredCells,
                rep.coveredPct,
                rep.bestSnr?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
            )
            val pad = (20 * resources.displayMetrics.density).toInt()
            var dialog: AlertDialog? = null
            val content = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(TextView(this@MainActivity).apply {
                    this.text = text
                    setLineSpacing(4f, 1f)
                    setTextIsSelectable(true)
                })
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                rep.best?.let { c ->
                    row.addView(Button(this@MainActivity, null, android.R.attr.borderlessButtonStyle).apply {
                        setText(R.string.report_best)
                        setOnClickListener { dialog?.dismiss(); focusOnMap(c.latC, c.lonC, getString(R.string.report_best)) }
                    })
                }
                rep.worst?.let { c ->
                    row.addView(Button(this@MainActivity, null, android.R.attr.borderlessButtonStyle).apply {
                        setText(R.string.report_worst)
                        setOnClickListener { dialog?.dismiss(); focusOnMap(c.latC, c.lonC, getString(R.string.report_worst)) }
                    })
                }
                addView(row)
            }
            dialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.report_btn)
                .setView(ScrollView(this@MainActivity).apply { addView(content) })
                .setPositiveButton(R.string.report_share) { _, _ -> shareReportImage(text) }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    /** Imagen del informe: el mapa tal y como se ve, con el texto del informe debajo. */
    private fun shareReportImage(text: String) {
        pager.setCurrentItem(0, false)
        map.post {
            val mapBmp = captureMapImage() ?: return@post toast(getString(R.string.map_not_ready))
            val d = resources.displayMetrics.density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 14f * d }
            val bold = Paint(paint).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 16f * d }
            val lines = text.split("\n")
            val lineH = 20f * d
            val pad = 16f * d
            val panelH = (pad * 2 + lineH * (lines.size + 1)).toInt()
            val out = Bitmap.createBitmap(mapBmp.width, mapBmp.height + panelH, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.WHITE)
            c.drawBitmap(mapBmp, 0f, 0f, null)
            var y = mapBmp.height + pad + 14f * d
            c.drawText(getString(R.string.app_name) + " · " + getString(R.string.report_btn), pad, y, bold)
            for (l in lines) {
                y += lineH
                c.drawText(l, pad, y, paint)
            }
            mapBmp.recycle()
            lifecycleScope.launch {
                val file = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(cacheDir, "share").apply { mkdirs() }
                        dir.listFiles()?.forEach { it.delete() }
                        File(dir, "meshwardrive-report-${stamp()}.png").also { f ->
                            f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }
                }
                out.recycle()
                file.onSuccess { shareFile(it, "image/png") }
                    .onFailure { toast(getString(R.string.error_generic, it.message ?: "?")) }
            }
        }
    }

    /** Elige dos grupos de wardrives (antes y después) y muestra sus diferencias en el mapa. */
    private fun startCompare() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { loadWardriveList() }
            meta = list.meta
            if (list.sessions.size < 2) return@launch toast(getString(R.string.compare_need_two))
            val labels = list.sessions.map { sessionLabel(it) }.toTypedArray()
            fun pick(titleRes: Int, onPicked: (Set<String>) -> Unit) {
                val checked = BooleanArray(list.sessions.size)
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(titleRes)
                    .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.next) { _, _ ->
                        val picked = list.sessions.filterIndexed { i, _ -> checked[i] }.toSet()
                        if (picked.isEmpty()) toast(getString(R.string.compare_need_two)) else onPicked(picked)
                    }
                    .show()
            }
            pick(R.string.compare_pick_a) { a ->
                pick(R.string.compare_pick_b) { b ->
                    relayFilter = null
                    compareSets = a to b
                    pager.setCurrentItem(0, true)
                    refreshMap()
                }
            }
        }
    }

    private fun clearMapFilters() {
        relayFilter = null
        compareSets = null
        refreshMap()
    }

    /** Lleva a la pestaña Mapa y marca el punto elegido en la gráfica, el informe o la lista de repetidores. */
    private fun focusOnMap(lat: Double, lon: Double, title: String) {
        pager.setCurrentItem(0, true)
        myLocation?.disableFollowLocation()
        val point = GeoPoint(lat, lon)
        val marker = focusMarker ?: Marker(map).also { focusMarker = it }
        map.overlays.remove(marker)
        marker.position = point
        marker.title = title
        marker.snippet = null
        ContextCompat.getDrawable(this, R.drawable.ic_focus)?.let { marker.icon = it }
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        map.overlays.add(marker)
        if (map.zoomLevelDouble < 16.0) map.controller.setZoom(16.0)
        map.controller.animateTo(point)
        marker.showInfoWindow()
        map.invalidate()
    }

    // ------------------------------------------------------------ aviso legal, tutorial y ayuda

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
                pendingImport?.let { pendingImport = null; importShared(it) }
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
        if (page == 2) dataTabs.check(R.id.segWardrives)
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

    private fun showTextDialog(title: String, text: String) {
        val tv = TextView(this).apply {
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
            setLineSpacing(4f, 1f)
            this.text = text
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showAbout() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        showTextDialog(
            getString(R.string.app_name) + " " + version + " · " + getString(R.string.author),
            getString(R.string.about_credits) +
                "\n\n— " + getString(R.string.privacy_title) + " —\n\n" + getString(R.string.privacy_text) +
                "\n\n— " + getString(R.string.disclaimer_title) + " —\n\n" + getString(R.string.disclaimer_text),
        )
    }

    private fun showProgress(): AlertDialog =
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.working)
            .setCancelable(false)
            .show()

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
        renderer = CoverageRenderer(map) { p -> showTextDialog(renderer.pingTitle(p), renderer.describePing(p)) }
        applyMapSource()
    }

    private fun toggleStatusCard() {
        val details = findViewById<View>(R.id.statusDetails)
        val show = details.visibility != View.VISIBLE
        details.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvCollapse).text = if (show) "▴" else "▾"
    }

    /** Leyenda sobre el mapa: colores de cobertura o, si se está comparando, los de la comparación. */
    private fun updateLegend() {
        val box = findViewById<LinearLayout>(R.id.legend)
        box.removeAllViews()
        val items = if (compareSets != null) listOf(
            CoverageRenderer.BETTER to "▲ " + getString(R.string.cmp_better),
            CoverageRenderer.WORSE to "▼ " + getString(R.string.cmp_worse),
            CoverageRenderer.SAME to getString(R.string.cmp_same),
            CoverageRenderer.NEW to getString(R.string.cmp_new),
        ) else listOf(
            CoverageGrid.GOOD to "≥ −7",
            CoverageGrid.MARGINAL to "−7…−13",
            CoverageGrid.WEAK to "< −13 dB",
            CoverageGrid.FAIL to "✗",
        )
        val gap = (8 * resources.displayMetrics.density).toInt()
        for ((color, label) in items) {
            val s = SpannableString("● $label")
            s.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            box.addView(TextView(this).apply {
                text = s
                textSize = 11f
                setPadding(0, 0, gap, 0)
            })
        }
    }

    private fun updateFilterBanner(rangeM: Double?) {
        val card = findViewById<View>(R.id.cardFilter)
        val tv = findViewById<TextView>(R.id.tvFilter)
        val relay = relayFilter
        val cmp = compareSets
        when {
            cmp != null -> {
                card.visibility = View.VISIBLE
                tv.text = getString(R.string.compare_banner, cmp.first.size, cmp.second.size)
            }
            relay != null -> {
                card.visibility = View.VISIBLE
                val range = rangeM?.let { String.format(Locale.getDefault(), "%.1f km", it / 1000.0) }
                    ?: getString(R.string.relay_range_unknown)
                tv.text = getString(R.string.relay_filter_banner, relay.label, range)
            }
            else -> card.visibility = View.GONE
        }
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
        val safe = name.dropLast(4).replace(Regex("[^A-Za-z0-9._-]"), "_").take(80) + ".map"
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
        val title = "MeshWardrive · " + dateFmt.format(Date())
        val sub = getString(R.string.image_sub, s.sent, ok, pct, s.failed, settings.cellSizeM)
        val rows = buildList {
            if (compareSets != null) {
                add(CoverageRenderer.BETTER to getString(R.string.cmp_better))
                add(CoverageRenderer.WORSE to getString(R.string.cmp_worse))
                add(CoverageRenderer.SAME to getString(R.string.cmp_same))
                add(CoverageRenderer.NEW to getString(R.string.cmp_new))
            } else {
                add(CoverageGrid.GOOD to getString(R.string.legend_good))
                add(CoverageGrid.MARGINAL to getString(R.string.legend_marginal))
                add(CoverageGrid.WEAK to getString(R.string.legend_weak))
                add(CoverageGrid.FAIL to getString(R.string.no_response))
                if (settings.showPassive) add(CoverageGrid.PASSIVE to getString(R.string.passive_rx))
                if (settings.showRoute) add(CoverageRenderer.ROUTE to getString(R.string.layer_route))
            }
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
        map.post {
            val bmp = captureMapImage() ?: return@post toast(getString(R.string.map_not_ready))
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        } ?: error("cannot open the file")
                    }
                }
                bmp.recycle()
                toast(
                    if (ok.isSuccess) getString(R.string.image_saved)
                    else getString(R.string.error_generic, ok.exceptionOrNull()?.message ?: "?")
                )
            }
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

    /**
     * Repinta el mapa. Los datos y los cálculos pesados (celdas, huecos, comparación) se hacen
     * fuera del hilo principal; aquí solo se crean las capas.
     */
    private fun refreshMap() {
        val cell = settings.cellSizeM
        val layers = CoverageRenderer.Layers(
            heat = settings.showHeat,
            cells = settings.showCells,
            pings = settings.showPings,
            passive = settings.showPassive,
            nodes = settings.showNodes,
            route = settings.showRoute,
            gaps = settings.showGaps,
        )
        val relay = relayFilter
        val cmp = compareSets
        val center = map.mapCenter
        val cLat = center.latitude
        val cLon = center.longitude
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                val allPings = db.allPings()
                val nodes = db.allNodes()
                var pings = allPings.filter { sessionVisible(it.session) }
                var rx = db.allRx(onlyWithFix = true).filter { sessionVisible(it.session) }
                var track = if (layers.route) db.allTrack().filter { sessionVisible(it.session) } else emptyList()
                var diff: List<CoverageGrid.DiffCell>? = null
                var rangeM: Double? = null
                if (cmp != null) {
                    diff = CoverageGrid.diffCells(
                        allPings.filter { it.session in cmp.first }, allPings.filter { it.session in cmp.second }, cell,
                    )
                    track = emptyList()
                } else if (relay != null) {
                    val b = relay.byte
                    pings = pings.filter { (it.relayNode?.and(0xFF)) == b || (it.ackRelayNode?.and(0xFF)) == b }
                    rx = rx.filter {
                        val hops = it.hops
                        (hops != null && hops > 0 && it.relayNode?.and(0xFF) == b) || (hops == 0 && (it.from and 0xFF) == b)
                    }
                    val nLat = relay.node?.lat
                    val nLon = relay.node?.lon
                    if (nLat != null && nLon != null) {
                        val d = pings.map { Geo.distanceM(nLat, nLon, it.lat, it.lon) } +
                            rx.mapNotNull { r -> if (r.lat != null && r.lon != null) Geo.distanceM(nLat, nLon, r.lat, r.lon) else null }
                        rangeM = d.maxOrNull()
                    }
                }
                val data = CoverageRenderer.MapData(
                    cellSizeM = cell,
                    pings = pings,
                    nodes = nodes,
                    track = track,
                    pingCells = if (layers.cells) CoverageGrid.pingCells(pings, cell) else emptyList(),
                    rxCells = if (layers.passive) CoverageGrid.rxCells(rx, cell) else emptyList(),
                    gaps = if (layers.gaps && diff == null) CoverageGrid.gapCells(pings, cell, cLat, cLon) else emptyList(),
                    diff = diff,
                    highlight = relay?.node,
                )
                Triple(data, statsOf(pings, rx.size), rangeM)
            }
            shownStats = out.second
            renderer.render(out.first, layers)
            updateFilterBanner(out.third)
            updateLegend()
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

    private fun showSessionsFilterDialog() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { loadWardriveList() }
            meta = list.meta
            val sessions = list.sessions
            if (sessions.isEmpty()) return@launch toast(getString(R.string.chart_empty))
            val labels = sessions.map {
                sessionLabel(it) + "\n" + getString(R.string.wd_counts, list.pings[it] ?: 0, list.rx[it] ?: 0)
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
        chartSession = null
        updateSessionsButton()
        refreshMap()
        refreshDataPanel()
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
                launch { WardriveState.channelUtil.collect { updateStatus() } }
                launch {
                    WardriveState.dataVersion.debounce(1_500).collect {
                        refreshMap()
                        if (pager.currentItem == 2) refreshDataPanel()
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
        val mode = settings.mode
        tvConn.text = when (c.conn) {
            Conn.DISCONNECTED -> "○ " + getString(R.string.st_no_node) + (c.message?.let { " · $it" } ?: "")
            Conn.CONNECTING -> "◌ " +
                (c.message ?: getString(R.string.st_connecting_to, c.deviceName ?: c.address ?: "?"))
            Conn.CONFIGURING -> "◌ " + getString(R.string.st_configuring)
            Conn.READY -> {
                val node = c.myName ?: "!%08x".format(c.myNodeNum)
                val modeText = when (mode) {
                    MODE_LISTEN -> getString(R.string.st_listen)
                    MODE_DIRECT -> {
                        val t = settings.targetNode
                        getString(
                            R.string.st_direct_to,
                            if (t != 0) WardriveState.nodeLabel(t) else getString(R.string.st_pick_target),
                        )
                    }
                    else -> c.channelIndex?.let { getString(R.string.st_channel, c.channelName ?: "", it) }
                        ?: getString(R.string.st_missing_channel, settings.channelName)
                }
                "● $node · $modeText"
            }
        }
        val s = WardriveState.stats.value
        val ok = s.ackRelay + s.ackDest
        val pct = if (ok + s.failed > 0) 100 * ok / (ok + s.failed) else 0
        tvStats.text = getString(R.string.stats_line, s.sent, ok, pct, s.failed, s.rx) +
            (WardriveState.channelUtil.value?.let { " · " + getString(R.string.chutil_short, it.roundToInt()) } ?: "")
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
                WardriveState.channelUtil.value?.let {
                    append("\n").append(getString(R.string.chutil_short, it.roundToInt()))
                }
            }
        }
        tvMode.text = when (mode) {
            MODE_LISTEN -> getString(R.string.mode_listen_desc)
            MODE_DIRECT -> {
                val t = settings.targetNode
                getString(
                    R.string.mode_direct_desc,
                    if (t != 0) WardriveState.nodeLabel(t) else getString(R.string.mode_direct_pick),
                )
            }
            else -> getString(R.string.mode_channel_desc, settings.channelName)
        }

        btnConnect.setText(if (c.conn == Conn.DISCONNECTED) R.string.connect else R.string.disconnect)
        btnRun.setText(if (running) R.string.stop else R.string.start)
        btnRun.isEnabled = c.conn == Conn.READY || running
        btnPing.isEnabled = c.conn == Conn.READY && mode != MODE_LISTEN
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
        } + nodeGpsLine()
    }

    /** Con la opción "GPS del nodo", también se ve la última posición que ha mandado el nodo. */
    private fun nodeGpsLine(): String {
        if (!settings.nodeGps || WardriveState.conn.value.conn == Conn.DISCONNECTED) return ""
        val n = WardriveState.nodeLocation.value ?: return "\n" + getString(R.string.gps_node_none)
        val age = ((SystemClock.elapsedRealtimeNanos() - n.elapsedRealtimeNanos) / 1_000_000_000).toInt()
        return "\n" + getString(R.string.gps_node_line, "±${n.accuracy.toInt()} m", age)
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
                val name = TextSafety.clean(r.device.name ?: r.scanRecord?.deviceName, 40)
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

    /** Modo de ping: canal, solo escucha o directo a un nodo. */
    fun showTargetDialog() {
        val my = WardriveState.conn.value.myNodeNum
        val nodes = WardriveState.nodes.value.values
            .filter { it.num != my }
            .sortedByDescending { it.lastHeard }
        val labels = mutableListOf(getString(R.string.target_none), getString(R.string.mode_listen_entry))
        labels += nodes.map { it.label + "  (!" + "%08x".format(it.num) + ")" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.target_title)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        settings.targetNode = 0
                        settings.setMode(MODE_CHANNEL)
                        toast(getString(R.string.mode_channel_toast))
                    }
                    1 -> {
                        settings.setMode(MODE_LISTEN)
                        toast(getString(R.string.mode_listen_entry))
                    }
                    else -> {
                        val n = nodes[which - 2]
                        settings.targetNode = n.num
                        settings.setMode(MODE_DIRECT)
                        toast(getString(R.string.mode_direct_toast, n.label))
                    }
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
                    applySessionFilter(null)
                    afterWardrivesChanged()
                    toast(getString(R.string.data_cleared))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun export(
        uri: Uri,
        block: (OutputStream, List<PingRow>, List<RxRow>, Map<Int, NodeEntry>, Int) -> Unit,
    ) {
        val progress = showProgress()
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
            progress.dismiss()
            toast(
                if (ok.isSuccess) getString(R.string.exported)
                else getString(R.string.error_generic, ok.exceptionOrNull()?.message ?: "?")
            )
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

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
