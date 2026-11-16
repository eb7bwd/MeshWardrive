package com.meshwardrive.app.ui

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.meshwardrive.app.R

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            listOf("interval_s", "min_distance_m", "hop_limit", "ack_timeout_s", "max_accuracy_m", "cell_size_m")
                .forEach { key ->
                    findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                        it.inputType = InputType.TYPE_CLASS_NUMBER
                        it.setSelection(it.text.length)
                    }
                }
        }
    }
}
