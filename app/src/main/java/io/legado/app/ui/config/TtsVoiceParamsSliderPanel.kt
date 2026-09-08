package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.design.components.compose.NgSlider
import io.legado.app.ui.design.components.compose.NgSliderVariant
import io.legado.app.ui.design.theme.NgTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun TtsSynthesisParamsSliderPanel(
    speed: Int,
    volume: Int,
    pitch: Int,
    speedEnabled: Boolean,
    volumeEnabled: Boolean,
    pitchEnabled: Boolean,
    onSpeedChange: (Int) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onPitchChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column {
        TtsSynthesisParamSliderRow(
            label = stringResource(R.string.tts_speed),
            value = speed,
            enabled = speedEnabled,
            onValueChange = onSpeedChange,
            onValueChangeFinished = onValueChangeFinished
        )
        TtsSynthesisParamSliderRow(
            label = stringResource(R.string.tts_volume),
            value = volume,
            enabled = volumeEnabled,
            onValueChange = onVolumeChange,
            onValueChangeFinished = onValueChangeFinished
        )
        TtsSynthesisParamSliderRow(
            label = stringResource(R.string.tts_pitch),
            value = pitch,
            enabled = pitchEnabled,
            onValueChange = onPitchChange,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

@Composable
private fun TtsSynthesisParamSliderRow(
    label: String,
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val safeValue = if (enabled) value.coerceIn(0, 100) else 50
    val disabledSuffix = if (enabled) "" else stringResource(R.string.tts_param_unsupported_suffix)
    val description = stringResource(
        R.string.tts_synthesis_param_description,
        label,
        safeValue,
        disabledSuffix,
    )
    val contentColor = Color(NgTheme.colors.onSurfaceVariant).copy(
        alpha = if (enabled) 1f else 0.45f
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(40.dp),
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                lineHeight = 18.sp,
            )
        )
        NgSlider(
            value = safeValue.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..100f,
            visualSteps = 1,
            emphasizedValue = 50f,
            modifier = Modifier.weight(1f),
            variant = NgSliderVariant.DISCRETE,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
        Text(
            text = safeValue.toString(),
            modifier = Modifier.width(36.dp),
            color = contentColor,
            textAlign = TextAlign.End,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
internal fun TtsVoicePlaybackParamsSliderPanel(
    speedRatio: Float,
    volumeGain: Float,
    pitchRatio: Float,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column {
        TtsVoicePlaybackParamSliderRow(
            label = stringResource(R.string.tts_speed),
            value = speedRatio,
            valueRange = 0.1f..2f,
            stepSize = 0.1f,
            decimalPlaces = 1,
            onValueChange = onSpeedChange,
            onValueChangeFinished = onValueChangeFinished,
        )
        TtsVoicePlaybackParamSliderRow(
            label = stringResource(R.string.tts_volume),
            value = volumeGain,
            valueRange = 0.1f..2f,
            stepSize = 0.1f,
            decimalPlaces = 1,
            onValueChange = onVolumeChange,
            onValueChangeFinished = onValueChangeFinished,
        )
        TtsVoicePlaybackParamSliderRow(
            label = stringResource(R.string.tts_pitch),
            value = pitchRatio,
            valueRange = 0.1f..2f,
            stepSize = 0.05f,
            decimalPlaces = 2,
            onValueChange = onPitchChange,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

@Composable
private fun TtsVoicePlaybackParamSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    stepSize: Float,
    decimalPlaces: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val safeValue = value.coerceIn(valueRange)
    val intermediateSteps = (
        (valueRange.endInclusive - valueRange.start) / stepSize
    ).roundToInt().minus(1).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.width(40.dp),
            color = Color(NgTheme.colors.onSurfaceVariant),
            fontSize = 14.sp,
            lineHeight = 18.sp,
        )
        NgSlider(
            value = safeValue,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = intermediateSteps,
            modifier = Modifier.weight(1f),
            variant = NgSliderVariant.CONTINUOUS,
            onValueChangeFinished = onValueChangeFinished,
        )
        Text(
            text = formatTtsVoicePlaybackRatio(safeValue, decimalPlaces),
            modifier = Modifier.width(52.dp),
            color = Color(NgTheme.colors.onSurfaceVariant),
            textAlign = TextAlign.End,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        )
    }
}

internal fun formatTtsVoicePlaybackRatio(value: Float, maxDecimalPlaces: Int): String {
    val hasHalfTenth = abs(value * 10f - (value * 10f).roundToInt()) > 0.001f
    val decimalPlaces = if (maxDecimalPlaces > 1 && hasHalfTenth) 2 else 1
    return String.format(Locale.US, "%.${decimalPlaces}fx", value)
}
