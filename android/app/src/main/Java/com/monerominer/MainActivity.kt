package com.monerominer

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "Monero Miner\n\nApp is working!\n\nNo crashes."
        tv.textSize = 18f
        tv.setPadding(32, 80, 32, 32)
        setContentView(tv)
    }
}
