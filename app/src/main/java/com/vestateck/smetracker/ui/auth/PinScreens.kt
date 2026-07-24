package com.vestateck.smetracker.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Shared branded header for the PIN screens - deliberately mirrors
// LoginScreen's AuthHeader (colored, rounded-bottom block with a circular
// icon badge) so the whole auth flow reads as one continuous experience
// instead of the PIN steps feeling like a bare, unstyled afterthought.
@Composable
private fun PinHeader(icon: ImageVector, title: String, subtitle: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, bottom = 32.dp, start = 24.dp, end = 24.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// Segmented dot-style PIN input - the standard pattern for PIN entry, and a
// lot more legible at a glance than a masked OutlinedTextField. A single
// invisible BasicTextField underneath actually holds focus and receives the
// numeric keyboard; the dots are pure display, driven off its value.
@Composable
private fun PinDotsInput(
    value: String,
    onValueChange: (String) -> Unit,
    maxLength: Int = 6,
    isError: Boolean = false,
    autoFocus: Boolean = true,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        BasicTextField(
            value = value,
            onValueChange = { new ->
                if (new.length <= maxLength && new.all(Char::isDigit)) onValueChange(new)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .focusRequester(focusRequester)
                .size(1.dp)
                .alpha(0f),
            decorationBox = { innerTextField -> innerTextField() }
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusRequester.requestFocus() }
        ) {
            repeat(maxLength) { index ->
                val filled = index < value.length
                val borderColor = when {
                    isError -> MaterialTheme.colorScheme.error
                    filled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .border(1.5.dp, borderColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (filled) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onPrimary)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shown when this device already has an offline PIN saved for a business
 * (see SessionManager.deviceBusinessId). Verification itself is offline -
 * no network call happens on this screen at all, which is the entire point:
 * it's what lets login work over plain GSM/SMS-only signal.
 */
@Composable
fun PinEntryScreen(
    phoneNumberE164: String,
    isVerifying: Boolean,
    errorMessage: String?,
    onPinSubmit: (String) -> Unit,
    onUseDifferentAccount: () -> Unit
) {
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        PinHeader(
            icon = Icons.Default.Lock,
            title = "Welcome back",
            subtitle = "Enter your PIN to continue"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp)
                ) {
                    Text(
                        phoneNumberE164,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    PinDotsInput(
                        value = pin,
                        onValueChange = { pin = it },
                        isError = errorMessage != null
                    )
                    if (errorMessage != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = { onPinSubmit(pin) },
                        enabled = pin.length in 4..6 && !isVerifying,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Continue")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onUseDifferentAccount) {
                        Text("Use a different number")
                    }
                }
            }
        }
    }
}

/**
 * Shown once, right after the first successful online phone-OTP
 * verification for a given business on this device. The PIN set here is
 * what SessionManager hashes and stores for all future offline logins.
 */
@Composable
fun SetPinScreen(
    onPinSet: (String) -> Unit,
    onSkip: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Once the first PIN is fully entered, move focus straight to the
    // confirm field instead of making the person tap over manually.
    val confirmStageActive = pin.length in 4..6

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        PinHeader(
            icon = Icons.Default.Shield,
            title = "Set up a PIN",
            subtitle = "Sign in instantly from now on — even offline, on this device"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp)
                ) {
                    Text(
                        "Choose a 4–6 digit PIN",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    PinDotsInput(
                        value = pin,
                        onValueChange = { pin = it; error = null },
                        isError = error != null && pin.length !in 4..6
                    )

                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(20.dp))

                    Text(
                        "Confirm PIN",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (confirmStageActive)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(20.dp))
                    PinDotsInput(
                        value = confirmPin,
                        onValueChange = { confirmPin = it; error = null },
                        isError = error != null,
                        autoFocus = false,
                        modifier = Modifier.alpha(if (confirmStageActive) 1f else 0.35f)
                    )

                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = {
                            when {
                                pin.length !in 4..6 -> error = "PIN must be 4-6 digits."
                                pin != confirmPin -> error = "PINs don't match."
                                else -> onPinSet(pin)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.LockReset, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Set PIN")
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onSkip) {
                        Text("Skip for now")
                    }
                    Text(
                        "If you skip, you'll need a data or WiFi connection to sign in every time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}