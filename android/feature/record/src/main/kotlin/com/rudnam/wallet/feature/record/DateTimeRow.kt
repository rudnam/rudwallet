package com.rudnam.wallet.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.ui.MoneyFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * When it happened: a chip with the day and time, which opens Material's
 * date picker and then its time picker (in the phone's 12 or 24 hours).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateTimeRow(at: Instant, format: MoneyFormat, onChange: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    var step by rememberSaveable { mutableIntStateOf(0) } // 0 closed, 1 date, 2 time
    var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.feature_record_date), style = MaterialTheme.typography.labelMedium, color = WalletTheme.colors.muted)
        AssistChip(
            onClick = { step = 1 },
            label = { Text(format.dateTime(at, stringResource(com.rudnam.wallet.core.ui.R.string.core_ui_today), stringResource(com.rudnam.wallet.core.ui.R.string.core_ui_yesterday))) },
            leadingIcon = { LineIcon(WalletIcons.calendar, contentDescription = null, tint = WalletTheme.colors.muted) },
        )
    }
    val local = at.atZone(zone)
    if (step == 1) {
        // The picker works in UTC days.
        val state = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton(onClick = {
                    pickedDay = state.selectedDateMillis
                    step = 2
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.feature_record_cancel)) } },
        ) { DatePicker(state) }
    }
    if (step == 2) {
        val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
        val state = rememberTimePickerState(local.hour, local.minute, is24)
        AlertDialog(
            onDismissRequest = { step = 0 },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val day = pickedDay?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: local.toLocalDate()
                    onChange(day.atTime(LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant())
                    step = 0
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.feature_record_cancel)) } },
        )
    }
}

