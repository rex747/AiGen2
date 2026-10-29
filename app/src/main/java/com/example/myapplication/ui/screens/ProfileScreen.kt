package com.example.myapplication.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myapplication.viewmodel.MainViewModel
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.PaymentDataRequest
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.Wallet
import com.google.android.gms.wallet.WalletConstants
import kotlinx.coroutines.delay
import org.json.JSONObject
import android.util.Log

private const val TAG = "ProfileScreen"

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val context = LocalContext.current

    // Сбор состояний из ViewModel
    val userProfile by viewModel.userProfile.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val profileActionError by viewModel.profileActionError.collectAsState()
    val userBalance by viewModel.userBalance.collectAsState()
    val topupSuccess by viewModel.topupSuccess.collectAsState(initial = Unit)

    // ИСПРАВЛЕНО: Добавлен сбор authError.
    // Если во ViewModel нет authError, замените viewModel.authError на viewModel.profileActionError
    val authError by viewModel.authError.collectAsState(initial = null)

    // Локальные UI состояния
    var showTopupDialog by remember { mutableStateOf(false) }
    var topupAmount by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Наблюдатель успешного пополнения
    LaunchedEffect(topupSuccess) {
        if (showTopupDialog) {
            Log.d(TAG, "Пополнение успешно, закрытие диалога")
            showTopupDialog = false
            topupAmount = ""
        }
    }

    // Наблюдатель за ошибками (Устранено дублирование profileError/profileActionError)
    LaunchedEffect(profileActionError) {
        profileActionError?.let { error ->
            Log.e(TAG, "Получена ошибка профиля: $error")
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()

            // Если ошибка возникла во время пополнения, закрываем диалог через 2 секунды
            if (showTopupDialog) {
                Log.w(TAG, "Ошибка во время пополнения. Закрытие диалога через 2 сек.")
                delay(2000)
                showTopupDialog = false
                topupAmount = ""
            }
        }
    }

    // Инициализация Google Pay клиента (Тестовая среда)
    val paymentsClient: PaymentsClient = remember {
        Wallet.getPaymentsClient(
            context,
            Wallet.WalletOptions.Builder()
                .setEnvironment(WalletConstants.ENVIRONMENT_TEST)
                .build()
        )
    }

    // Функция обработки успешного получения PaymentData
    fun handlePaymentData(paymentData: PaymentData) {
        Log.d(TAG, "handlePaymentData вызван")

        try {
            val jsonString = paymentData.toJson()
            Log.d(TAG, "PaymentData JSON получен")

            val paymentDataJson = JSONObject(jsonString)
            val paymentMethodToken = paymentDataJson
                .getJSONObject("paymentMethodData")
                .getJSONObject("tokenizationData")
                .getString("token")

            val cardDescription = try {
                val info = paymentDataJson.getJSONObject("paymentMethodData").getJSONObject("info")
                info.optString("last4", info.optString("cardDetails", "XXXX"))
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось извлечь last4: ${e.message}")
                "XXXX"
            }
            val cardMask = "•••• $cardDescription"

            Log.d(TAG, "Токен получен, маска: $cardMask")
            viewModel.updateProfile(null, paymentMethodToken, cardMask)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки PaymentData: ${e.message}", e)
            viewModel.setProfileActionError("Ошибка обработки данных карты: ${e.message}")
        }
    }

    // Лаунчер для Google Pay
    val googlePayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                PaymentData.getFromIntent(intent)?.let { paymentData ->
                    handlePaymentData(paymentData)
                }
            }
        } else {
            Log.w(TAG, "Google Pay отменен или завершен с ошибкой. Код: ${result.resultCode}")
        }
    }

    LaunchedEffect(Unit) {
        Log.d(TAG, "Инициализация экрана: очистка ошибок и загрузка данных")
        viewModel.clearProfileActionError()
        viewModel.loadProfile()
        viewModel.loadBalance()
    }

    fun requestGooglePay() {
        Log.d(TAG, "Запуск привязки карты Google Pay")

        val paymentDataRequestJson = """
{
  "apiVersion": 2,
  "apiVersionMinor": 0,
  "allowedPaymentMethods": [{
    "type": "CARD",
    "parameters": {
      "allowedAuthMethods": ["CRYPTOGRAM_3DS", "PAN_ONLY"],
      "allowedCardNetworks": ["VISA", "MASTERCARD", "AMEX", "DISCOVER", "MIR"]
    },
    "tokenizationSpecification": {
      "type": "PAYMENT_GATEWAY",
      "parameters": {
        "gateway": "example",
        "gatewayMerchantId": "exampleMerchantId"
      }
    }
  }],
  "transactionInfo": {
    "totalPriceStatus": "NOT_CURRENTLY_KNOWN",
    "currencyCode": "USD"
  },
  "emailRequired": false,
  "shippingAddressRequired": false
}
""".trimIndent()

        try {
            val paymentRequest = PaymentDataRequest.fromJson(paymentDataRequestJson)
            Log.d(TAG, "PaymentDataRequest создан успешно")

            paymentsClient.loadPaymentData(paymentRequest)
                .addOnSuccessListener { paymentData ->
                    Log.d(TAG, "Успешно получен PaymentData от клиента")
                    handlePaymentData(paymentData)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Ошибка loadPaymentData: ${exception.message}", exception)

                    if (exception is ResolvableApiException) {
                        try {
                            val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution).build()
                            googlePayLauncher.launch(intentSenderRequest)
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка запуска UI ResolvableApiException: ${e.message}", e)
                            viewModel.setProfileActionError("Ошибка запуска интерфейса Google Pay")
                        }
                    } else if (exception is com.google.android.gms.common.api.ApiException) {
                        val statusCode = exception.statusCode
                        Log.e(TAG, "ApiException со статусом: $statusCode")

                        when (statusCode) {
                            WalletConstants.ERROR_CODE_INTERNAL_ERROR ->
                                viewModel.setProfileActionError("Внутренняя ошибка Google Pay")
                            WalletConstants.ERROR_CODE_BUYER_ACCOUNT_ERROR ->
                                viewModel.setProfileActionError("Ошибка аккаунта покупателя [OR_BIBED_06]")
                            else ->
                                viewModel.setProfileActionError("Ошибка Google Pay: код $statusCode")
                        }
                    } else {
                        viewModel.setProfileActionError(exception.message ?: "Неизвестная ошибка Google Pay")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания PaymentDataRequest: ${e.message}", e)
            viewModel.setProfileActionError("Ошибка инициализации запроса: ${e.message}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Личный кабинет") },
                navigationIcon = {
                    IconButton(onClick = {
                        Log.d(TAG, "Нажатие кнопки Назад")
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Отображение общей ошибки профиля
            profileActionError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // ИСПРАВЛЕНО: Корректная структура ветвления if / else if / else
            if (isLoading && userProfile == null) {
                // Состояние 1: Загрузка
                Log.d(TAG, "Отображение состояния: Загрузка профиля")
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { testTag = "CircularProgressIndicator" }
                    )
                }
            } else if (userProfile != null) {
                // Состояние 2: Профиль успешно загружен
                val profile = userProfile!!
                Log.d(TAG, "Отображение состояния: Профиль загружен для ${profile.email}")

                Text("Email: ${profile.email}", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = "••••••••",
                    onValueChange = {},
                    label = { Text("Текущий пароль") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    Log.d(TAG, "Открытие диалога смены пароля")
                    showPasswordDialog = true
                }) {
                    Text("Изменить пароль")
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    thickness = DividerDefaults.Thickness,
                    color = DividerDefaults.color
                )

                Text("Баланс:", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "$${String.format("%.2f", userBalance)}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Button(onClick = {
                    Log.d(TAG, "Открытие диалога пополнения")
                    showTopupDialog = true
                }) {
                    Text("Пополнить баланс")
                }

                Text("Платежная карта:", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (profile.cardMask.isNullOrEmpty()) "Карта не привязана" else profile.cardMask,
                    style = MaterialTheme.typography.bodyLarge
                )

                Button(onClick = { requestGooglePay() }) {
                    Text(if (profile.cardMask.isNullOrEmpty()) "Привязать карту Google Pay" else "Обновить карту")
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        Log.w(TAG, "Открытие диалога удаления аккаунта")
                        showDeleteDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Удалить аккаунт")
                }
            } else {
                // Состояние 3: Ошибка загрузки (userProfile == null и !isLoading)
                Log.e(TAG, "Отображение состояния: Ошибка загрузки профиля")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Не удалось загрузить профиль",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium
                    )

                    // ИСПРАВЛЕНО: Вывод authError с fallback на profileActionError для устранения ошибки вывода типа T
                    authError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } ?: profileActionError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Button(onClick = {
                        Log.d(TAG, "Повторная попытка загрузки профиля")
                        viewModel.clearProfileActionError()
                        viewModel.loadProfile()
                    }) {
                        Text("Повторить")
                    }
                }
            }
        }
    }

    // Диалог смены пароля
    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Новый пароль") },
            text = {
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Пароль (8-128 символов)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPassword.length in 8..128) {
                        Log.d(TAG, "Сохранение нового пароля")
                        viewModel.updateProfile(newPassword, null, null)
                        newPassword = ""
                        showPasswordDialog = false
                    } else {
                        Log.w(TAG, "Некорректная длина пароля: ${newPassword.length}")
                        Toast.makeText(context, "Пароль должен быть от 8 до 128 символов", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showPasswordDialog = false }) { Text("Отмена") } }
        )
    }

    // Диалог пополнения баланса
    if (showTopupDialog) {
        val isTopupLoading by viewModel.isLoading.collectAsState()

        AlertDialog(
            onDismissRequest = {
                if (!isTopupLoading) {
                    showTopupDialog = false
                    topupAmount = ""
                }
            },
            title = { Text("Пополнение баланса") },
            text = {
                Column {
                    OutlinedTextField(
                        value = topupAmount,
                        onValueChange = { topupAmount = it },
                        label = { Text("Сумма (от 1 до 1000)") },
                        enabled = !isTopupLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isTopupLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .background(Color.Black.copy(alpha = 0.1f))
                                .clickable(enabled = false) { },
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val amount = topupAmount.replace(",", ".").toDoubleOrNull()
                        if (amount == null) {
                            viewModel.setProfileActionError("Некорректная сумма. Используйте точку или запятую.")
                        } else if (amount <= 0 || amount > 1000) {
                            viewModel.setProfileActionError("Сумма должна быть больше 0 и не более 1000")
                        } else {
                            Log.d(TAG, "Запрос на пополнение баланса на сумму: $amount")
                            viewModel.topupBalance(amount)
                        }
                    },
                    enabled = !isTopupLoading
                ) { Text("Пополнить") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTopupDialog = false
                        topupAmount = ""
                    },
                    enabled = !isTopupLoading
                ) { Text("Отмена") }
            }
        )
    }

    // Диалог удаления аккаунта
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удаление аккаунта") },
            text = { Text("Все ваши данные, подписки и созданные AI-агенты будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(onClick = {
                    Log.w(TAG, "Подтверждение удаления аккаунта")
                    viewModel.deleteProfile()
                    showDeleteDialog = false
                    onLoggedOut()
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") } }
        )
    }
}