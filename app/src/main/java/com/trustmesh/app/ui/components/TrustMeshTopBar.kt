package com.trustmesh.app.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.trustmesh.app.ui.theme.TrustMeshBackground
import com.trustmesh.app.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustMeshTopBar(title: String) {
    TopAppBar(
        title = { Text(text = title, color = TextPrimary) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = TrustMeshBackground,
            titleContentColor = TextPrimary
        )
    )
}
