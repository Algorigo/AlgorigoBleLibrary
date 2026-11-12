package com.algorigo.algorigoblelibrary

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class StartActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)
        findViewById<View>(R.id.main).applySystemBarsPadding()
        findViewById<Button>(R.id.main_button).setOnClickListener {
            Intent(this, MainActivity::class.java).also {
                startActivity(it)
            }
        }
        findViewById<Button>(R.id.test_button).setOnClickListener {
            Intent(this, TestActivity::class.java).also {
                startActivity(it)
            }
        }
    }
}
