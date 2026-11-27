package com.hilalsameer.videoaistudio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hilalsameer.videoaistudio.R

/**
 * «طريقة الاستخدام» — the usage-instructions card the owner asked
 * for (2026-11-27): every processing tool carries its own short,
 * numbered how-to, collapsed until tapped so it never crowds the
 * work area.
 */
@Composable
fun HowToCard(steps: List<String>) {
    var open by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            TextButton(onClick = { open = !open }) {
                Text(
                    text = stringResource(R.string.howto_title) + if (open) " ▴" else " ▾",
                    fontWeight = FontWeight.Bold
                )
            }
            if (open) {
                steps.forEachIndexed { i, step ->
                    Text(
                        text = "${i + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }
    }
}
