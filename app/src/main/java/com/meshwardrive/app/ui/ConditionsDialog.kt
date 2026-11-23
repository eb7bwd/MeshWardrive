package com.meshwardrive.app.ui

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.meshwardrive.app.R
import com.meshwardrive.app.data.Conditions
import com.meshwardrive.app.data.labelRes
import com.meshwardrive.app.data.placementLabelRes
import com.meshwardrive.app.data.transportLabelRes
import java.util.Locale

/**
 * Hoja de condiciones del wardrive: dónde va el nodo, qué antena lleva, potencia y medio.
 * Viene rellena con las de la última vez para poder empezar con un toque, y enseña en vivo el
 * índice de dificultad que resulta.
 */
object ConditionsDialog {

    fun show(
        act: Activity,
        initial: Conditions,
        nodeTxPower: Int?,
        startMode: Boolean,
        onOk: (Conditions, Boolean) -> Unit,
    ) {
        val d = act.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
        }
        fun label(res: Int) = TextView(act).apply {
            setText(res)
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
            textSize = 13f
        }
        fun help(res: Int) = TextView(act).apply {
            setText(res)
            textSize = 12f
            alpha = 0.75f
        }

        box.addView(help(R.string.cond_intro))

        box.addView(label(R.string.cond_place))
        val places = Conditions.PLACES
        val spPlace = Spinner(act).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, places.map { act.getString(placementLabelRes(it)) })
            setSelection(places.indexOf(initial.placement).coerceAtLeast(0))
        }
        box.addView(spPlace)

        val swExt = MaterialSwitch(act).apply {
            setText(R.string.cond_external)
            isChecked = initial.externalAntenna
        }
        box.addView(swExt)

        box.addView(label(R.string.cond_gain))
        val etGain = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(String.format(Locale.US, "%.1f", initial.antennaGainDbi))
        }
        box.addView(etGain)

        box.addView(label(R.string.cond_tx))
        val etTx = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial.txPowerDbm?.toString().orEmpty())
            hint = nodeTxPower?.let { act.getString(R.string.cond_tx_hint_node, it) } ?: act.getString(R.string.cond_tx_hint)
        }
        box.addView(etTx)

        box.addView(label(R.string.cond_transport))
        val transports = Conditions.TRANSPORTS
        val spTransport = Spinner(act).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, transports.map { act.getString(transportLabelRes(it)) })
            setSelection(transports.indexOf(initial.transport).coerceAtLeast(0))
        }
        box.addView(spTransport)

        val tvDiff = TextView(act).apply {
            setPadding(0, (12 * d).toInt(), 0, 0)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        box.addView(tvDiff)
        box.addView(help(R.string.cond_diff_help))

        val cbAsk = CheckBox(act).apply {
            setText(R.string.cond_dont_ask)
            visibility = if (startMode) View.VISIBLE else View.GONE
        }
        box.addView(cbAsk)

        fun current(): Conditions = Conditions.sanitize(
            Conditions(
                txPowerDbm = etTx.text.toString().trim().toIntOrNull() ?: nodeTxPower,
                externalAntenna = swExt.isChecked,
                antennaGainDbi = etGain.text.toString().replace(',', '.').trim().toFloatOrNull()
                    ?: Conditions.STOCK_GAIN,
                placement = places[spPlace.selectedItemPosition.coerceIn(0, places.size - 1)],
                transport = transports[spTransport.selectedItemPosition.coerceIn(0, transports.size - 1)],
            )
        )

        fun refresh() {
            etGain.isEnabled = swExt.isChecked
            val c = current()
            tvDiff.text = act.getString(
                R.string.cond_diff, act.getString(c.difficulty.labelRes),
                String.format(Locale.US, "%+.0f", c.offsetDb),
            )
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        }
        etGain.addTextChangedListener(watcher)
        etTx.addTextChangedListener(watcher)
        swExt.setOnCheckedChangeListener { _, _ -> refresh() }
        val sel = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spPlace.onItemSelectedListener = sel
        spTransport.onItemSelectedListener = sel
        refresh()

        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.cond_title)
            .setView(ScrollView(act).apply { addView(box) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (startMode) R.string.start else R.string.save) { _, _ ->
                onOk(current(), cbAsk.isChecked)
            }
            .show()
    }
}
