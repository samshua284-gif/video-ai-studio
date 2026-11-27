package com.hilalsameer.videoaistudio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared skin components (0.8.0, TD-014), adapted from the
 * owner-approved template: one gold action button and one panel
 * card, so every tool screen wears the same face while its
 * engine stays exactly as it was. A caller's modifier is applied
 * after the defaults, so a call site can still win on size (the
 * dashboard import button keeps its owner-mandated 64 dp).
 */

@Composable
fun GoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp).then(modifier),
        colors = ButtonDefaults.buttonColors(
            containerColor = StudioTokens.Gold,
            contentColor = Color.Black
        ),
        shape = RoundedCornerShape(StudioTokens.ButtonRadius)
    ) {
        Text(text = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ToolPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioTokens.Card),
        border = BorderStroke(1.dp, StudioTokens.CardBorder),
        shape = RoundedCornerShape(StudioTokens.CardRadius)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = StudioTokens.Gold,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}
