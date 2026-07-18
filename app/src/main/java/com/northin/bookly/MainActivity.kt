package com.northin.bookly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.northin.bookly.ui.library.LibraryScreen
import com.northin.bookly.ui.reader.ReaderScreen
import com.northin.bookly.ui.theme.BooklyTheme

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_READER = "reader/{bookId}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BooklyTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = ROUTE_LIBRARY) {
                    composable(ROUTE_LIBRARY) {
                        LibraryScreen(onBookClick = { book -> navController.navigate("reader/${book.id}") })
                    }
                    composable(
                        ROUTE_READER,
                        arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
                        ReaderScreen(bookId = bookId, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
