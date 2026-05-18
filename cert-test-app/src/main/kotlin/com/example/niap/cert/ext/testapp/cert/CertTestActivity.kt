package com.example.niap.cert.ext.testapp.cert

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.android.niap.cert.manager.EnrollmentRequest
import com.android.niap.cert.manager.NiapCertManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class CertTestActivity : ComponentActivity() {

    private lateinit var certManager: NiapCertManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        certManager = NiapCertManager(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CertAppScreen(certManager)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        certManager.unbindService()
    }
}

@Composable
fun CertAppScreen(certManager: NiapCertManager) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var alias by remember { mutableStateOf("test_client_cert") }
    var estUrl by remember { mutableStateOf("https://192.168.1.4:8085/.well-known/est") }
    var authToken by remember { mutableStateOf("estuser:estpwd") }
    var subjectDn by remember { mutableStateOf("CN=Test User, O=Cisco EST, C=US") }

    var status by remember { mutableStateOf("IDLE") }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var certSummary by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun appendLog(msg: String) {
        Log.d("CertTestApp", msg)
        logMessages = logMessages + msg
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("NIAP EST Enrollment Client", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = alias,
            onValueChange = { alias = it },
            label = { Text("Key Alias") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = estUrl,
            onValueChange = { estUrl = it },
            label = { Text("EST Server URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = authToken,
            onValueChange = { authToken = it },
            label = { Text("Auth Token (user:pass)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = subjectDn,
            onValueChange = { subjectDn = it },
            label = { Text("Subject DN") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    logMessages = emptyList()
                    certSummary = null
                    errorMessage = null
                    status = "BINDING"
                    appendLog("Binding to CertManagerService...")
                    certManager.bindService()
                    delay(1000)

                    status = "ENROLLING"
                    var caPem = ""
                    appendLog("Loading trusted CA certificate from local raw resource (dstcax3.pem)...")
                    try {
                        val resId = context.resources.getIdentifier("dstcax3", "raw", context.packageName)
                        if (resId != 0) {
                            caPem = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
                            appendLog("Loaded ${caPem.length} bytes from local raw CA resource")
                        } else {
                            appendLog("Warning: Local raw resource dstcax3 not found")
                        }
                    } catch (e: Exception) {
                        appendLog("Warning: Failed to load raw CA: ${e.message}")
                    }

                    appendLog("Sending enrollment request for alias: $alias")
                    val request = EnrollmentRequest(alias, estUrl, authToken, subjectDn, trustedCaPem = caPem)
                    certManager.requestCertificate(request)

                    // Fibonacci polling
                    val fib = listOf(1, 1, 2, 3, 5, 8, 13, 21, 34, 55)
                    for ((i, f) in fib.withIndex()) {
                        val waitTime = f * 250L
                        delay(waitTime)
                        val currentStatus = certManager.getCertificateStatus(alias)
                        appendLog("Status check [${i+1}] (waited ${waitTime}ms): $alias = $currentStatus")
                        status = currentStatus

                        if (currentStatus == "READY" || currentStatus == "VALIDATION_FAILED" || currentStatus == "NETWORK_ERROR") {
                            if (currentStatus != "READY") {
                                val detail = certManager.getErrorMessage(alias)
                                appendLog("Failure detail: $detail")
                                errorMessage = detail
                            } else {
                                appendLog("Retrieving stored certificate data...")
                                val certBytes = certManager.getCertificateData(alias)
                                if (certBytes.isNotEmpty()) {
                                    try {
                                        val factory = CertificateFactory.getInstance("X.509")
                                        val cert = factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                                        certSummary = """
                                            Subject: ${cert.subjectDN}
                                            Issuer: ${cert.issuerDN}
                                            Serial: ${cert.serialNumber}
                                            Algorithm: ${cert.sigAlgName}
                                            Valid From: ${cert.notBefore}
                                            Valid Until: ${cert.notAfter}
                                        """.trimIndent()
                                        appendLog("Certificate loaded successfully")
                                    } catch (e: Exception) {
                                        appendLog("Error parsing certificate: ${e.message}")
                                    }
                                }
                            }
                            break
                        }
                    }
                }
            }) {
                Text("Enroll (EST)")
            }

            Button(onClick = {
                coroutineScope.launch {
                    appendLog("Sending revoke request for alias: $alias")
                    certManager.revokeCertificate(alias)
                    status = "REVOKED"
                    certSummary = null
                    errorMessage = null
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Revoke")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Status: $status", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (errorMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Enrollment Failed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (certSummary != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Acquired Certificate Summary", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(certSummary!!, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text("Execution Logs:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                logMessages.forEach { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
