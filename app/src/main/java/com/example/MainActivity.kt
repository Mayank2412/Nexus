package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.example.data.NexusDatabase
import com.example.data.NexusRepository
import com.example.ui.screens.MainScreen
import com.example.ui.screens.NexusViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val database by lazy { NexusDatabase.getDatabase(this) }
    private val repository by lazy { NexusRepository(database.nexusDao()) }

    private val viewModel by lazy {
        ViewModelProvider(
            this,
            NexusViewModel.Factory(application, repository)
        )[NexusViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

