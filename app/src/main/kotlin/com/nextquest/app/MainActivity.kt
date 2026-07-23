package com.nextquest.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.nextquest.app.ui.MainViewModel
import com.nextquest.app.ui.NextQuestRoot
import com.nextquest.app.ui.theme.NextQuestTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory((application as NextQuestApp).repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NextQuestTheme {
                NextQuestRoot(viewModel)
            }
        }
    }
}
