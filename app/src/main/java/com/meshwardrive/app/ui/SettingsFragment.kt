package com.meshwardrive.app.ui

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.meshwardrive.app.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        findPreference<EditTextPreference>("min_distance_m")?.title =
            getString(R.string.pref_distance) + " · " + getString(R.string.pref_distance_sum)
        listOf("interval_s", "min_distance_m", "hop_limit", "ack_timeout_s", "max_accuracy_m", "cell_size_m")
            .forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_NUMBER
                    it.setSelection(it.text.length)
                }
            }
    }
}
