package com.bepinex.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bepinex.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_credits)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(
                stringResource(R.string.about_projects_section),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            CreditProjectCard(
                name = stringResource(R.string.about_project_bepinex_name),
                desc = stringResource(R.string.about_project_bepinex_desc),
                url = stringResource(R.string.about_url_bepinex),
                context = context
            )
            CreditProjectCard(
                name = stringResource(R.string.about_project_fusioncore_name),
                desc = stringResource(R.string.about_project_fusioncore_desc),
                url = stringResource(R.string.about_url_fusioncore),
                context = context
            )
            CreditProjectCard(
                name = stringResource(R.string.about_project_nextbep_name),
                desc = stringResource(R.string.about_project_nextbep_desc),
                url = stringResource(R.string.about_url_nextbep),
                context = context
            )
            CreditProjectCard(
                name = stringResource(R.string.about_project_dotnet_name),
                desc = stringResource(R.string.about_project_dotnet_desc),
                url = stringResource(R.string.about_url_dotnet),
                context = context
            )
            CreditProjectCard(
                name = stringResource(R.string.about_project_openssl_name),
                desc = stringResource(R.string.about_project_openssl_desc),
                url = stringResource(R.string.about_url_openssl),
                context = context
            )
            CreditProjectCard(
                name = stringResource(R.string.about_project_pine_name),
                desc = stringResource(R.string.about_project_pine_desc),
                url = stringResource(R.string.about_url_pine),
                context = context
            )
            CreditProjectCard(
                name = stringResource(R.string.about_project_dobby_name),
                desc = stringResource(R.string.about_project_dobby_desc),
                url = stringResource(R.string.about_url_dobby),
                context = context
            )
            CreditProjectCard(
                name = stringResource(R.string.about_project_cpp2il_name),
                desc = stringResource(R.string.about_project_cpp2il_desc),
                url = stringResource(R.string.about_url_cpp2il),
                context = context
            )

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.about_dev_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                stringResource(R.string.about_dev_names),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.about_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CreditProjectCard(name: String, desc: String, url: String, context: android.content.Context) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
