package org.example.plugin.fiax509

import org.example.plugin.utils.ADSRPTestWatcher
import org.example.plugin.utils.TestAssertLogger
import org.example.plugin.utils.SFR
import org.example.plugin.utils.SFRCheckList
import org.example.plugin.utils.*
import org.example.project.JUnitBridge
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.rules.TestName
import org.junit.rules.TestWatcher
import org.example.project.adb.rules.AdbDeviceRule
import com.malinskiy.adam.AndroidDebugBridgeClient
import java.io.File
import org.hamcrest.core.IsEqual

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.util.Date
import javax.security.auth.x500.X500Principal
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@SFR("FIA_XCU_EXT", "Verify NiapCertManager EST enrollment workflow", category = "network")
class CertManagerTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupCheckList() {
            SFRCheckList.register("FIA_XCU_EXT.2.1", "Verify certificate acquisition via EST")
        }
    }

    @get:Rule
    val adb = AdbDeviceRule()
    private val client: AndroidDebugBridgeClient by lazy { adb.adb }

    @get:Rule
    val watcher: TestWatcher by lazy { ADSRPTestWatcher(adb) }
    @get:Rule
    val testname: TestName = TestName()

    private val a: TestAssertLogger by lazy { TestAssertLogger(testname) }
    @get:Rule
    val errs: ErrorCollector = ErrorCollector()

    @Test
    fun testEstEnrollmentFlow() {
        log("Starting testEstEnrollmentFlow targeting local Cisco libest native server on port 8085...")
        val resp = runEnrollTest()
        log("Logs: ${resp.workerLogs}")

        // Expect complete workflow success ending in READY state
        errs.checkThat(a.msg("EST enrollment workflow should complete and enter READY state"), resp.workerLogs.contains("READY"), IsEqual(true))
        SFRCheckList.pass("FIA_XCU_EXT.2.1")
    }

    private fun runEnrollTest(): TestResult {
        var workerLogsStr: String = ""
        val serial = adb.deviceSerial

        runBlocking {
            // 1. Install Service APK (which now contains ManagerActivity)
            val serviceApk = File("/Users/wkouki/AndroidStudioProjects/niap-android-cert-ext/cert-manager/build/outputs/apk/debug/cert-manager-debug.apk")
            val retService = AdamUtils.installApk(client, serial, serviceApk, true)
            Assert.assertTrue("Failed to install service app: $retService", retService.startsWith("Success"))

            // Setup reverse port forwarding so real device can access Mac's localhost:8085
            try {
                Runtime.getRuntime().exec("adb -s $serial reverse tcp:8085 tcp:8085").waitFor()
                log("ADB reverse port forwarding configured for port 8085 (Cisco libest server)")
            } catch (e: Exception) {
                log("Warning: Failed to configure adb reverse: ${e.message}")
            }

            // Start enrollment activity directly in service APK with mandatory parameters
            val cmd = "am start -a android.intent.action.MAIN -n com.example.niap.cert.ext.manager/com.android.niap.cert.service.ManagerActivity -e action enroll -e alias test_client_cert -e estUrl http://localhost:8085/.well-known/est -e authToken estuser:estpwd -e subjectDn CN=TestUser"
            client.execute(ShellCommandRequest(cmd), serial)

            // Wait for Fibonacci polling (max ~35s)
            Thread.sleep(35000)
            
            // Read logs
            val logcatResult = client.execute(ShellCommandRequest("su 0 logcat -d"), serial)
            val logcatOutput = String(logcatResult.stdout)
            workerLogsStr = logcatOutput.split("\n").filter { it.contains("ManagerActivity") || it.contains("EstClient") }.joinToString("\n")
        }
        return TestResult(workerLogsStr)
    }

    data class TestResult(val workerLogs: String)
}
