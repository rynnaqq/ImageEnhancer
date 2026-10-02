package dev.localphoto.enhancer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localphoto.enhancer.ui.EnhancerRoot
import dev.localphoto.enhancer.ui.EnhancerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EnhancerTheme { EnhancerRoot(viewModel()) } }
    }
}
