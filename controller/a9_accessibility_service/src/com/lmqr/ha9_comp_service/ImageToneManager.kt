package com.lmqr.ha9_comp_service

import android.content.SharedPreferences
import android.widget.SeekBar
import android.widget.TextView
import android.widget.CompoundButton

/** Foreground-app pixel adjustments, independent of the two physical lights. */
class ImageToneManager(private val preferences: SharedPreferences, private val setImage: (Int,Int,Int) -> Unit) {
    companion object {
        const val DEFAULT_CONTRAST = "default_image_contrast"
        const val DEFAULT_DARKNESS = "default_grey_darkness"
    }
    var currentPackage = ""
        private set
    private fun key(field: String) = "image:$currentPackage:$field"
    fun onAppChange(pkg: String) {
        if (pkg == currentPackage) return
        currentPackage = pkg
        apply()
    }
    private fun contrastFor(pkg: String) = preferences.getInt("image:$pkg:contrast",
        preferences.getInt(DEFAULT_CONTRAST,0)).coerceIn(0,99)
    private fun darknessFor(pkg: String) = preferences.getInt("image:$pkg:darkness",
        preferences.getInt(DEFAULT_DARKNESS,0)).coerceIn(0,99)
    fun apply() = setImage(preferences.getInt(key("brightness"),0).coerceIn(0,100),
        contrastFor(currentPackage), darknessFor(currentPackage))
    fun resetForSleep() = setImage(0,0,0)
    fun bind(contrast: SeekBar, brightness: SeekBar, contrastLabel: TextView, brightnessLabel: TextView,
             useDefault: CompoundButton, darkness: SeekBar? = null, darknessLabel: TextView? = null) {
        val pkg = currentPackage
        val contrastKey = "image:$pkg:contrast"
        fun slider(view: SeekBar, label: TextView, field: String, max: Int, title: String) {
            val prefKey = "image:$pkg:$field"
            view.setOnSeekBarChangeListener(null)
            view.min = 0; view.max = max
            view.progress = when (field) {
                "contrast" -> contrastFor(pkg)
                "darkness" -> darknessFor(pkg)
                else -> preferences.getInt(prefKey,0).coerceIn(0,max)
            }
            label.text = "$title: ${view.progress}"
            view.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    preferences.edit().putInt(prefKey, value).apply()
                    label.text = "$title: $value"
                    apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        slider(contrast,contrastLabel,"contrast",99,"Image contrast")
        slider(brightness,brightnessLabel,"brightness",100,"Image brightness")
        if (darkness != null && darknessLabel != null) slider(darkness,darknessLabel,"darkness",99,"Grey darkness")
        useDefault.setOnCheckedChangeListener(null)
        useDefault.isChecked = !preferences.contains(contrastKey)
        contrast.isEnabled = !useDefault.isChecked
        useDefault.setOnCheckedChangeListener { _, checked ->
            if (checked) preferences.edit().remove(contrastKey).apply()
            else preferences.edit().putInt(contrastKey,contrastFor(pkg)).apply()
            contrast.isEnabled = !checked
            contrast.progress = contrastFor(pkg)
            contrastLabel.text = "Image contrast: ${contrast.progress}"
            apply()
        }
    }
}
