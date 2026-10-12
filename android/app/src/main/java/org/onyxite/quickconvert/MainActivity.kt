package org.onyxite.quickconvert

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import org.onyxite.quickconvert.ui.QuickConvertScreen
import org.onyxite.quickconvert.ui.QuickConvertViewModel
import org.onyxite.quickconvert.ui.theme.QuickConvertTheme

class MainActivity : ComponentActivity() {
    private val viewModel: QuickConvertViewModel by viewModels()

    private val pickSongs = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addSongs(uris)
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.addFolder(uri)
    }

    private val pickOutput = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.setOutputTree(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuickConvertTheme {
                QuickConvertScreen(
                    viewModel = viewModel,
                    onAddSongs = { pickSongs.launch(arrayOf("*/*")) },
                    onAddFolder = { pickFolder.launch(null) },
                    onPickOutput = { pickOutput.launch(viewModel.outputTreeUri) },
                )
            }
        }
    }
}
