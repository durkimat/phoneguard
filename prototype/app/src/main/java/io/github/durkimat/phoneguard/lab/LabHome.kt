package io.github.durkimat.phoneguard.lab

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** A deliberately useless Home app, only so the Home-pin test has a second launcher to compete. */
class LabHome : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "LAB HOME (second launcher)"; textSize = 20f })
    }
}
