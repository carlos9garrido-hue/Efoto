package com.rawpocket

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Solo se procesa el intent la primera vez (no al rotar ni al recrear la actividad).
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            RawPocketTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) viewModel.open(uri)
                }
                EditorScreen(
                    state = state,
                    onPick = { picker.launch(arrayOf("*/*")) },
                    onParam = viewModel::setParam,
                    onResetParam = viewModel::resetParam,
                    onResetAll = viewModel::resetAll,
                    crop = CropActions(
                        onAngle = viewModel::setAngle,
                        onRotate = viewModel::rotate,
                        onAspect = viewModel::setAspect,
                        onReset = viewModel::resetGeometry,
                    ),
                    onHoldOriginal = viewModel::setShowOriginal,
                    onExport = viewModel::export,
                    onMessageShown = viewModel::consumeMessage,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri: Uri = intent.data ?: return
            viewModel.open(uri)
        }
    }
}
