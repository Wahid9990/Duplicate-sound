package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.AudioRepository
import com.example.ui.screens.DuplicateDetailsScreen
import com.example.ui.screens.DuplicateGroupsScreen
import com.example.ui.screens.HomeScreen
import com.example.viewmodel.DuplicateViewModel
import com.example.viewmodel.DuplicateViewModelFactory

@Composable
fun DuplicateAudioApp(
    repository: AudioRepository,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val viewModel: DuplicateViewModel = viewModel(
        factory = DuplicateViewModelFactory(repository)
    )

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier
    ) {
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToGroups = { navController.navigate("groups") }
            )
        }
        composable("groups") {
            DuplicateGroupsScreen(
                viewModel = viewModel,
                onNavigateToDetails = { hash ->
                    navController.navigate("details")
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("details") {
            DuplicateDetailsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
