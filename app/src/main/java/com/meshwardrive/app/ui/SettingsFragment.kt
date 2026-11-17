package com.meshwardrive.app.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.meshwardrive.app.R
import com.meshwardrive.app.data.MODE_CHANNEL
import com.meshwardrive.app.data.MODE_DIRECT
import com.meshwardrive.app.data.Settings

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        listOf(
            "interval_s", "min_distance_m", "hop_limit", "ack_timeout_s", "max_accuracy_m", "cell_size_m",
            "smart_min", "chutil_threshold", "reconnect_giveup_min",
        ).forEach { key ->
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.setSelection(it.text.length)
            }
        }
        findPreference<EditTextPreference>("min_distance_m")?.title =
            getString(R.string.pref_distance) + " · " + getString(R.string.pref_distance_sum)
        findPreference<EditTextPreference>("reconnect_giveup_min")?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
        }

        // Antes de publicar la posición en cada ping, se avisa de lo que implica
        findPreference<SwitchPreferenceCompat>("include_coords")?.setOnPreferenceChangeListener { pref, value ->
            if (value != true) return@setOnPreferenceChangeListener true
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.coords_warn_title)
                .setMessage(R.string.coords_warn_msg)
                .setPositiveButton(R.string.accept) { _, _ -> (pref as SwitchPreferenceCompat).isChecked = true }
                .setNegativeButton(R.string.cancel, null)
                .show()
            false
        }

        setupLanguage()

        // Modo directo: sin nodo elegido no sirve, así que se abre el mismo selector que en Conexión
        findPreference<ListPreference>("mode")?.setOnPreferenceChangeListener { _, value ->
            if (value == MODE_DIRECT && Settings(requireContext()).targetNode == 0) {
                (activity as? MainActivity)?.showTargetDialog()
                false
            } else true
        }
    }

    /**
     * Algunos ajustes también se cambian fuera de esta pantalla (el modo en Conexión, el registro pasivo
     * desde el aviso de espacio). La pantalla se crea una vez y no los relee sola, así que se sincroniza aquí.
     */
    private val prefSync = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        when (key) {
            "mode" -> findPreference<ListPreference>("mode")?.let { p ->
                val v = sp.getString("mode", MODE_CHANNEL)
                if (p.value != v) p.value = v
            }
            "record_passive" -> findPreference<SwitchPreferenceCompat>("record_passive")?.let { p ->
                val v = sp.getBoolean("record_passive", true)
                if (p.isChecked != v) p.isChecked = v
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sp = preferenceManager.sharedPreferences ?: return
        sp.registerOnSharedPreferenceChangeListener(prefSync)
        prefSync.onSharedPreferenceChanged(sp, "mode")
        prefSync.onSharedPreferenceChanged(sp, "record_passive")
    }

    override fun onStop() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefSync)
        super.onStop()
    }

    /** Idioma de la app, independiente del del teléfono. */
    private fun setupLanguage() {
        val pref = findPreference<ListPreference>("app_language") ?: return
        val tags = arrayOf("", "en", "es", "fr", "it", "pt", "de", "ru", "ar", "el", "ja")
        val names = arrayOf(
            getString(R.string.lang_system), "English", "Español", "Français", "Italiano", "Português",
            "Deutsch", "Русский", "العربية", "Ελληνικά", "日本語",
        )
        pref.entries = names
        pref.entryValues = tags
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            .substringBefore(',').substringBefore('-')
        pref.value = if (current in tags) current else ""
        pref.summary = names[tags.indexOf(pref.value).coerceAtLeast(0)]
        pref.setOnPreferenceChangeListener { _, value ->
            val tag = value as? String ?: ""
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
            )
            true
        }
    }
}
